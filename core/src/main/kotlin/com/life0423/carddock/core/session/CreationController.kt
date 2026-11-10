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

/** A draft belongs to one editing session. Late results cannot update a reopened sheet. */
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

    fun open(template: TemplateDefinition?) {
        close()
        mutableDraft.value = CreationDraft(
            prompt = template?.examplePrompt.orEmpty(),
            title = template?.title.orEmpty(),
        )
    }

    fun close() {
        generation += 1
        work?.cancel()
        work = null
        mutableDraft.value = null
    }

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

    fun changeTitle(title: String) {
        val current = draft.value ?: return
        if (!current.phase.isBusy()) mutableDraft.value = current.copy(title = title.take(40))
    }

    fun trial() {
        val current = draft.value ?: return
        if (work?.isActive == true || current.prompt.isBlank()) return
        val token = generation
        mutableDraft.value = current.copy(
            phase = RunPhase.QUEUED, preview = null, template = null,
            message = "正在匹配演示模板…",
        )
        work = scope.launch {
            try {
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
                            ensureActive()
                            edit(token) { it.copy(phase = RunPhase.RUNNING, message = "正在读取模拟设备…") }
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
                throw cancelled
            } catch (_: Exception) {
                edit(token) { it.copy(phase = RunPhase.FAILED, message = "试运行失败，请稍后重试。") }
            }
        }
    }

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
                if (token == generation) {
                    generation += 1
                    mutableDraft.value = null
                }
                notify("“${card.title}”已加入空间")
            } else {
                edit(token) { current.copy(message = "保存失败，预览仍保留，可以重试。") }
            }
        }
    }

    private fun edit(token: Long, transform: (CreationDraft) -> CreationDraft) {
        val current = draft.value
        if (token == generation && current != null) mutableDraft.value = transform(current)
    }

    private fun RunPhase.isBusy() = this == RunPhase.QUEUED || this == RunPhase.RUNNING

    private companion object { const val MAX_CARDS = 12 }
}
