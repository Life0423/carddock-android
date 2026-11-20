package com.life0423.carddock.core.session

import com.life0423.carddock.core.model.PickupEntry
import com.life0423.carddock.core.engine.EngineResult
import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.LocalTarget
import com.life0423.carddock.core.model.PersonalTemplate
import com.life0423.carddock.core.model.TemplateEdit
import com.life0423.carddock.core.port.ReadOptions
import com.life0423.carddock.core.model.RunInfo
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.port.CompileResult
import com.life0423.carddock.core.port.RuleCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import com.life0423.carddock.core.port.DiscoveryResult
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
    private val personalCompiler: RuleCompiler,
    private val modelCompiler: RuleCompiler?,
    private val engine: RuleEngine,
    private val cards: CardCollection,
    private val lane: Mutex,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val newId: () -> String,
    private val notify: (String) -> Unit,
    private val library: TemplateLibrary,
    private val discover: (suspend (String, Boolean) -> DiscoveryResult?)?,
    private val verifyPickup: suspend (PickupEntry) -> String?,
) {
    private val mutableDraft = MutableStateFlow<CreationDraft?>(null)
    val draft = mutableDraft.asStateFlow()
    private var generation = 0L
    private var work: Job? = null

    /** 作废上一轮预览。已选模板直接提供候选规则，描述需求则在试运行时匹配。 */
    fun open(template: TemplateDefinition?, model: Boolean = false) {
        if (draft.value?.saving == true) return
        close()
        mutableDraft.value = CreationDraft(
            prompt = template?.examplePrompt.orEmpty(),
            title = template?.title.orEmpty(),
            template = template.takeUnless { model },
            modelCandidate = model,
        )
    }

    /** 新编辑使用固定 ID。重开时只恢复定义，不恢复订单参数和试运行资格。 */
    fun openTemplateEditor(id: String?) {
        if (draft.value?.saving == true) return
        val entry = id?.let { key -> library.state.value.firstOrNull { it.id == key } ?: return }
        val edit = entry?.edit
        val candidate = if (edit != null) edit.candidate else entry?.published
        open(candidate)
        mutableDraft.value = checkNotNull(draft.value).copy(
            personalTemplateId = entry?.id ?: newId(),
            prompt = edit?.prompt ?: candidate?.examplePrompt.orEmpty(),
            title = edit?.title ?: candidate?.title.orEmpty(),
            description = edit?.description ?: candidate?.description.orEmpty(),
        )
    }

    /** 选中的模板直接提供规则，切换后清掉旧参数和预览。 */
    fun chooseTemplate(template: TemplateDefinition) {
        val current = draft.value ?: return
        if (current.phase.isBusy() || current.ruleChangeCard != null) return
        mutableDraft.value = current.copy(
            template = template, prompt = template.examplePrompt, demonstrations = emptyList(),
            title = current.title.ifBlank { template.title },
            description = current.description.ifBlank { template.description },
            modelCandidate = false, orderNumber = "", routeOid = "",
            phase = RunPhase.IDLE, preview = null, message = "",
        )
    }

    /** 变更展示只更新模板布局，试运行仍用完整的原始规则读取设备。 */
    fun changeDisplayFields(fields: List<com.life0423.carddock.core.model.DisplayField>) {
        val current = draft.value ?: return
        val template = current.template ?: return
        if (current.phase.isBusy() || current.personalTemplateId == null || fields == template.display) return
        mutableDraft.value = current.copy(template = template.copy(display = fields),
            phase = RunPhase.IDLE, preview = null, message = "")
    }

    fun changeDescription(value: String) {
        val current = draft.value ?: return
        if (!current.phase.isBusy()) mutableDraft.value = current.copy(description = value.take(160))
    }

    fun changeRouteOid(value: String) {
        val current = draft.value ?: return
        if (!current.phase.isBusy()) mutableDraft.value = current.copy(
            routeOid = value.trim(), phase = RunPhase.IDLE, preview = null, message = "",
        )
    }

    /** 从已保存的菜鸟规则打开草稿，原卡在确认保存前继续使用旧规则。 */
    fun openRuleChange(card: CardRecord, template: TemplateDefinition, rollback: Boolean) {
        if (draft.value?.saving == true) return
        open(template)
        mutableDraft.value = checkNotNull(draft.value).copy(
            title = card.title, ruleChangeCard = card, rollingBackRule = rollback,
        )
    }

    /** 取消试运行并作废其代际。持久化进行时保留编辑器，直到写入完成。 */
    fun close() {
        if (draft.value?.saving == true) return
        generation += 1
        work?.cancel()
        work = null
        mutableDraft.value = null
    }

    /** 提示词参与分流，所以改动会废除已编译模板和预览。长度在这里截断，界面不再做第二道校验。 */
    fun changePrompt(prompt: String) {
        val current = draft.value ?: return
        if (current.phase.isBusy() || current.ruleChangeCard != null) return
        mutableDraft.value = current.copy(
            prompt = prompt.take(500), demonstrations = emptyList(),
            title = current.title.takeUnless { it == current.template?.title }.orEmpty(),
            description = current.description.takeUnless { it == current.template?.description }.orEmpty(),
            orderNumber = "",
            routeOid = "",
            template = null,
            modelCandidate = false,
            phase = RunPhase.IDLE,
            preview = null,
            message = "",
        )
    }

    /** 在录入时统一数字格式，试运行和保存使用同一个号码。 */
    fun changeOrderNumber(value: String) {
        val current = draft.value ?: return
        if (current.phase.isBusy()) return
        val number = buildString(value.length) {
            for (character in value) {
                when {
                    character.isWhitespace() || character.category == CharCategory.FORMAT -> Unit
                    character in '０'..'９' -> append('0' + (character - '０'))
                    else -> append(character)
                }
            }
        }
        mutableDraft.value = current.copy(
            orderNumber = number, routeOid = if (number == current.orderNumber) current.routeOid else "", phase = RunPhase.IDLE, preview = null, message = "",
        )
    }

    /** 标题不参与规则。试运行成功后仍可改标题，不必重新核验；忙碌时丢掉修改。 */
    fun changeTitle(title: String) {
        val current = draft.value ?: return
        if (!current.phase.isBusy()) mutableDraft.value = current.copy(title = title.take(40))
    }

    /** 已有仍活跃的试运行或保存时直接返回。取消收尾中 isActive 为 false，这道检查让开。空白提示词不改阶段。 */
    fun trial(generate: Boolean = false, prepare: Boolean = false, demonstration: Int? = null, compareFilter: Boolean = false) {
        val current = draft.value ?: return
        val shown = demonstration?.let { current.demonstrations.getOrNull(it) ?: return }
        if (work?.isActive == true || current.prompt.isBlank()) return
        if (generate && current.template?.pickupEntry != null) return
        if (generate && !current.modelCandidate && current.template?.rule?.targetKey != "cainiao.pickup.empty") return
        val token = generation
        if (current.modelCandidate && !generate && current.template == null) return
        mutableDraft.value = current.copy(
            demonstrations = emptyList(), phase = RunPhase.QUEUED, preview = null,
            template = if (generate) null else current.template,
            modelCandidate = current.modelCandidate || generate,
            message = if (generate) "正在生成菜鸟候选…" else "正在匹配模板…",
        )
        work = scope.launch {
            try {
                // 编译放在通道锁外，匹配模板时不堵住正在刷新的卡片。
                var discovery = shown
                var observed = shown?.observed
                var compiled = when {
                    shown != null -> CompileResult.Success(shown.template)
                    generate -> modelCompiler?.compile(current.prompt)
                        ?: CompileResult.Unsupported("请先在“我的”配置模型。")
                    current.template != null -> CompileResult.Success(current.template)
                    current.personalTemplateId != null -> personalCompiler.compile(current.prompt.trim())
                    else -> compiler.compile(current.prompt.trim())
                }
                if (prepare && compiled is CompileResult.Unsupported && discover != null) {
                    edit(token) { it.copy(phase = RunPhase.RUNNING, message = "正在后台试查需求…") }
                    when (val found = lane.withLock { discover.invoke(current.prompt.trim(), compareFilter) }) {
                        is DiscoveryResult.Found -> {
                            discovery = found.candidate
                            observed = found.candidate.observed
                            compiled = CompileResult.Success(found.candidate.template)
                        }
                        is DiscoveryResult.Demonstrate -> {
                            edit(token) { it.copy(phase = RunPhase.NEEDS_USER, demonstrations = found.choices,
                                message = if (found.choices.any { c -> c.template.pickupEntry != null }) "请选择要示范的官方亮码入口。" else "页面中有多个数量。请按来源页面的显示顺序示范一次，点选要读取的字段。") }
                            return@launch
                        }
                        is DiscoveryResult.Incomplete -> compiled = CompileResult.Unsupported(found.reason)
                        null -> Unit
                    }
                }
                when (compiled) {
                    is CompileResult.Unsupported -> edit(token) {
                        it.copy(phase = RunPhase.FAILED, message = compiled.message)
                    }
                    is CompileResult.Success -> {
                        edit(token) { it.copy(
                            template = compiled.template,
                            title = it.title.ifBlank { compiled.template.title },
                            description = it.description.ifBlank { compiled.template.description },
                            message = "等待设备操作通道",
                        ) }
                        if (generate || (prepare && observed == null)) {
                            edit(token) { it.copy(phase = RunPhase.IDLE, message = "候选已生成，请确认后试运行。") }
                            return@launch
                        }
                        val target = if (compiled.template.requiresOrderNumber) {
                            if (!current.orderNumber.matches(Regex("[0-9]{15,30}"))) {
                                edit(token) { it.copy(phase = RunPhase.FAILED, message = "请复制美团「订单信息」中的「订单号码」（15–30 位数字）。") }
                                return@launch
                            }
                            if (current.routeOid.isNotEmpty() && !current.routeOid.matches(Regex("[0-9]{19}"))) {
                                edit(token) { it.copy(phase = RunPhase.FAILED, message = "后台路由号须为 19 位数字，或留空使用手动核验。") }
                                return@launch
                            }
                            LocalTarget(current.orderNumber, current.routeOid.ifBlank { null })
                        } else null
                        lane.withLock {
                            // 排队等锁期间可能已被关闭。拿到锁后再检查，避免把取消写成一次读取。
                            ensureActive()
                            edit(token) { it.copy(phase = RunPhase.RUNNING, message = "正在读取页面…") }
                            compiled.template.pickupEntry?.let { entry ->
                                edit(token) { it.copy(message = "正在从菜鸟首页独立核验亮码入口…") }
                                repeat(if (current.personalTemplateId == null) 1 else 2) {
                                    val missing = verifyPickup(entry)
                                    if (missing != null) {
                                        edit(token) { it.copy(phase = RunPhase.NEEDS_USER, message = missing) }
                                        return@launch
                                    }
                                }
                            }
                            val result = engine.execute(compiled.template.rule, ReadOptions(localTarget = target, requestedAt = discovery?.cutoff, compareFilter = compareFilter)) { progress ->
                                edit(token) { it.copy(message = progress.message) }
                            }
                            ensureActive()
                            edit(token) {
                                when (result) {
                                    is EngineResult.Success -> if (observed != null &&
                                        (result.snapshot.fields.associate { field -> field.key to field.value } != observed ||
                                            result.snapshot.evidenceDigest != discovery?.evidenceDigest)) {
                                        it.copy(phase = RunPhase.NEEDS_USER, template = null, preview = null,
                                            message = "两次独立读取结果不同，请重新生成候选。")
                                    } else it.copy(
                                        phase = RunPhase.SUCCEEDED, preview = result.snapshot,
                                        message = "核验通过，请检查预览并确认保存。",
                                    )
                                    is EngineResult.NeedsUser -> it.copy(
                                        phase = RunPhase.NEEDS_USER, template = it.template.takeIf { observed == null }, message = result.message,
                                    )
                                    is EngineResult.Failure -> it.copy(
                                        phase = RunPhase.FAILED, template = it.template.takeIf { observed == null }, message = result.message,
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
     * 写入期间锁住关闭操作，写入失败时恢复当前预览。
     */
    fun save() {
        val current = draft.value ?: return
        if (current.personalTemplateId != null) {
            saveTemplate(verified = true)
            return
        }
        val preview = current.preview ?: return
        val template = current.template ?: return
        if (current.phase != RunPhase.SUCCEEDED || current.title.isBlank() || work?.isActive == true) return
        val original = current.ruleChangeCard
        if (original == null && cards.state.value.cards.size >= MAX_CARDS) {
            mutableDraft.value = current.copy(message = "空间已满（$MAX_CARDS 张）。请先移除不需要的卡片。")
            return
        }
        val token = generation
        mutableDraft.value = current.copy(phase = RunPhase.RUNNING, saving = true, message = "正在保存卡片…")
        work = scope.launch {
            val run = RunInfo(
                id = newId(), phase = RunPhase.SUCCEEDED,
                message = "试运行已核验 · ${preview.sourceLabel}",
                currentStep = template.rule.steps.size, totalSteps = template.rule.steps.size,
                startedAt = preview.fetchedAt, endedAt = now(),
            )
            val card = original?.copy(rule = template.rule, previousRule = original.rule, snapshot = preview, run = run)
                ?: CardRecord(
                    id = newId(), title = current.title.trim(), kind = template.kind,
                    rule = template.rule, snapshot = preview, run = run,
                    localTarget = if (template.requiresOrderNumber) LocalTarget(current.orderNumber, current.routeOid.ifBlank { null }) else null,
                    sourceTemplateId = template.id, sourceTemplateRevision = template.revision, display = template.display, pickupEntry = template.pickupEntry,
                )
            if (cards.update { existing ->
                    if (original == null) existing + card
                    else existing.map { stored -> if (stored == original) card else stored }
                }) {
                // 代际不符说明弹层已经关掉或重开过。卡片可以留下，但不能清掉新的草稿。
                if (token == generation) {
                    generation += 1
                    mutableDraft.value = null
                }
                notify(when {
                    original == null -> "“${card.title}”已加入空间"
                    current.rollingBackRule -> "菜鸟规则已回退"
                    else -> "菜鸟规则已替换"
                })
            } else {
                // 写回的是保存前抓住的草稿，不是现在这份 RUNNING。阶段因此回到试运行成功，预览还在。
                val message = if (original != null && cards.find(original.id) != original) {
                    "卡片已变化，请关闭后重新试运行。"
                } else "保存失败，预览仍保留，可以重试。"
                edit(token) { current.copy(message = message) }
            }
        }
    }

    /** 草稿和可用版只写定义。订单、路由和预览不进入模板库。 */
    fun saveTemplate(verified: Boolean) {
        val current = draft.value ?: return
        val id = current.personalTemplateId ?: return
        if (current.phase.isBusy() || work?.isActive == true) return
        if (current.title.isBlank() && (verified || current.prompt.isBlank())) return
        val candidate = current.template
        if (verified && (current.phase != RunPhase.SUCCEEDED || current.preview == null || candidate == null)) return
        val token = generation
        val title = current.title.trim()
        val description = current.description.trim()
        mutableDraft.value = current.copy(phase = RunPhase.RUNNING, saving = true, message = "正在保存模板…")
        work = scope.launch {
            val saved = library.update { list ->
                val previous = list.firstOrNull { it.id == id } ?: PersonalTemplate(id)
                val next = if (verified) previous.copy(
                    published = checkNotNull(candidate).copy(id = id, title = title, description = description,
                        revision = (previous.published?.revision ?: 0) + 1),
                    edit = null,
                ) else previous.copy(edit = TemplateEdit(title, description, candidate, current.prompt))
                if (previous in list) list.map { if (it.id == id) next else it } else list + next
            }
            if (saved) {
                if (generation == token) {
                    generation += 1
                    mutableDraft.value = null
                }
                notify(if (verified) "模板已保存" else "模板草稿已保存")
            } else {
                edit(token) { current.copy(message = "保存失败，编辑内容仍保留，可以重试。") }
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
