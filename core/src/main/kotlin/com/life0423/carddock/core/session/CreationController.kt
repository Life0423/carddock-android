package com.life0423.carddock.core.session

import com.life0423.carddock.core.engine.EngineResult
import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunInfo
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.core.port.RuleCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A draft belongs to one editing session. Late results cannot update a reopened sheet.
 * [generation] 在关闭和成功保存时加一。edit 只写代际仍匹配的草稿，重开的弹层收不到上一轮的结果。
 * 试运行和卡片刷新共用 [lane]，编译本身不占这把锁。
 */
internal class CreationController(
    private val compiler: RuleCompiler,
    private val engine: RuleEngine,
    private val cards: CardCollection,
    private val lane: Mutex,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val newId: () -> String,
    private val notify: (String) -> Unit,
) {
    private val mutableDraft = MutableStateFlow<CreationDraft?>(null)
    val draft = mutableDraft.asStateFlow()
    private var generation = 0L
    private var work: Job? = null

    /** 先关掉上一轮并作废其代际。模板只预填提示词和标题，规则要等试运行编译成功才进草稿。 */
    fun open(template: TemplateDefinition?) {
        close()
        mutableDraft.value = CreationDraft(
            prompt = template?.examplePrompt.orEmpty(),
            title = template?.title.orEmpty(),
        )
    }

    /** 代际加一并取消任务，然后丢掉草稿。已经进集合提交段的保存仍可能写完，但不会再改当前草稿。 */
    fun close() {
        generation += 1
        work?.cancel()
        work = null
        mutableDraft.value = null
    }

    /** 提示词参与分流，所以改动会废除已编译模板和预览。长度在这里截断，界面不再做第二道校验。 */
    fun changePrompt(prompt: String) {
        val current = draft.value ?: return
        if (current.phase.isBusy()) return
        mutableDraft.value = current.copy(
            prompt = prompt.take(500),
            template = null,
            phase = RunPhase.IDLE,
            preview = null,
            message = "",
        )
    }

    /** 标题不参与规则。试运行成功后仍可改标题，不必重新核验；忙碌时丢掉修改。 */
    fun changeTitle(title: String) {
        val current = draft.value ?: return
        if (!current.phase.isBusy()) mutableDraft.value = current.copy(title = title.take(40))
    }

    /** 已有仍活跃的试运行或保存时直接返回。取消收尾中 isActive 为 false，这道检查让开。空白提示词不改阶段。 */
    fun trial() {
        val current = draft.value ?: return
        if (work?.isActive == true || current.prompt.isBlank()) return
        val token = generation
        mutableDraft.value = current.copy(
            phase = RunPhase.QUEUED, preview = null, template = null,
            message = "正在匹配模板…",
        )
        work = scope.launch {
            try {
                // 编译放在通道锁外，匹配模板时不堵住正在刷新的卡片。
                when (val compiled = compiler.compile(current.prompt.trim())) {
                    is CompileResult.Unsupported -> edit(token) {
                        it.copy(phase = RunPhase.FAILED, message = compiled.message)
                    }
                    is CompileResult.Success -> {
                        edit(token) { it.copy(
                            template = compiled.template,
                            title = it.title.ifBlank { compiled.template.title },
                            message = "等待设备操作通道",
                        ) }
                        lane.withLock {
                            // 排队等锁期间可能已被关闭。拿到锁后再检查，避免把取消写成一次读取。
                            ensureActive()
                            edit(token) { it.copy(phase = RunPhase.RUNNING, message = "正在读取设备…") }
                            val result = engine.execute(compiled.template.rule) { progress ->
                                edit(token) { it.copy(message = progress.message) }
                            }
                            ensureActive()
                            edit(token) {
                                when (result) {
                                    is EngineResult.Success -> it.copy(
                                        phase = RunPhase.SUCCEEDED, preview = result.snapshot,
                                        message = "核验通过，请检查预览并确认保存。",
                                    )
                                    is EngineResult.NeedsUser -> it.copy(
                                        phase = RunPhase.NEEDS_USER, message = result.message,
                                    )
                                    is EngineResult.Failure -> it.copy(
                                        phase = RunPhase.FAILED, message = result.message,
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // 取消不是失败。不先分开再抛，会落到下面的 FAILED。
                // 关闭弹层时代际已经加一，edit 不会改已关掉的草稿。重抛是为了弹层还开着时不要写成失败，并让协程以取消结束。
                throw cancelled
            } catch (_: Exception) {
                edit(token) { it.copy(phase = RunPhase.FAILED, message = "试运行失败，请稍后重试。") }
            }
        }
    }

    /**
     * 只有试运行成功、预览和模板都在、标题非空白且没有进行中的任务才写卡片。
     * 关闭会取消这个协程，但集合的提交段不可取消，所以晚到的成功仍可能入列。
     */
    fun save() {
        val current = draft.value ?: return
        val preview = current.preview ?: return
        val template = current.template ?: return
        if (current.phase != RunPhase.SUCCEEDED || current.title.isBlank() || work?.isActive == true) return
        if (cards.state.value.cards.size >= MAX_CARDS) {
            notify("原型最多保存 $MAX_CARDS 张卡片，请先移除不需要的卡片。")
            return
        }
        val token = generation
        mutableDraft.value = current.copy(phase = RunPhase.RUNNING, message = "正在保存卡片…")
        work = scope.launch {
            val card = CardRecord(
                id = newId(), title = current.title.trim(), kind = template.kind,
                rule = template.rule, snapshot = preview,
                run = RunInfo(
                    id = newId(), phase = RunPhase.SUCCEEDED,
                    message = "试运行已核验 · 演示数据",
                    currentStep = template.rule.steps.size, totalSteps = template.rule.steps.size,
                    startedAt = preview.fetchedAt, endedAt = now(),
                ),
            )
            if (cards.update { it + card }) {
                // 代际不符说明弹层已经关掉或重开过。卡片可以留下，但不能清掉新的草稿。
                if (token == generation) {
                    generation += 1
                    mutableDraft.value = null
                }
                notify("“${card.title}”已加入空间")
            } else {
                // 写回的是保存前抓住的草稿，不是现在这份 RUNNING。阶段因此回到试运行成功，预览还在。
                edit(token) { current.copy(message = "保存失败，预览仍保留，可以重试。") }
            }
        }
    }

    /** token 不是当前代，或草稿已经关掉，就整次丢掉。 */
    private fun edit(token: Long, transform: (CreationDraft) -> CreationDraft) {
        val current = draft.value
        if (token == generation && current != null) mutableDraft.value = transform(current)
    }

    private fun RunPhase.isBusy() = this == RunPhase.QUEUED || this == RunPhase.RUNNING

    private companion object { const val MAX_CARDS = 12 }
}
