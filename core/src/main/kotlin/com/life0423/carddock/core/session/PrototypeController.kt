package com.life0423.carddock.core.session

import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.port.CardStore
import com.life0423.carddock.core.port.RuleCompiler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

/**
 * Application-scoped coordinator. Actions and work on [scope] must share one serial dispatcher.
 * It contains no Android types, so lifecycle, storage and devices can be replaced independently.
 *
 * 动作和 [scope] 上的控制器工作必须跑在同一个串行调度器上。生产用 Dispatchers.Main.immediate。
 * 只是换成同一个多线程调度器，例如 Dispatchers.Default，并不能串行化这些写入。
 * 初始化标志、重置标志和创建代际是普通变量，没有多线程保护。
 */
class PrototypeController(
    store: CardStore,
    compiler: RuleCompiler,
    engine: RuleEngine,
    private val templates: List<TemplateDefinition>,
    private val scope: CoroutineScope,
    private val setDeviceScenario: (DemoScenario) -> Unit = {},
    now: () -> Long = System::currentTimeMillis,
    newId: () -> String = { UUID.randomUUID().toString() },
    modelCompiler: RuleCompiler? = null,
    private val needsManualSequenceStart: (CardRecord) -> Boolean = { false },
) {
    private val notice = MutableStateFlow<String?>(null)
    private val scenario = MutableStateFlow(DemoScenario.NORMAL)
    // 构造属性按顺序初始化，runs 这时还不存在。查找放到 init，initialize 只会在构造完成之后调用。
    private var liveJob: (String) -> Boolean = { false }
    private val cards = CardCollection(store, ::notify, now) { liveJob(it) }
    // 试运行和卡片刷新共用这一把锁，同一时刻只有一次设备读取。
    private val lane = Mutex()
    private val runs = RunCoordinator(cards, engine, lane, scope, now, newId)

    init {
        liveJob = runs::hasLiveJob
    }

    private val creator = CreationController(compiler, modelCompiler, engine, cards, lane, scope, now, newId, ::notify)
    private val sequence = MutableStateFlow<SequenceStatus?>(null)
    private var sequenceJob: Job? = null
    private var nextItem: CompletableDeferred<Unit>? = null
    private var initialized = false
    // 重置完成前拒绝打开、刷新和继续，避免新的读取插进种子卡替换之前。
    private var resetting = false

    // Eagerly：应用 scope 一直订阅，不依赖界面是否在收集。初始值只有模板，卡片要等 initialize。
    val state: StateFlow<PrototypeState> = combine(cards.state, creator.draft, scenario, notice, sequence) {
            collection, draft, selectedScenario, message, runningSequence ->
        PrototypeState(
            loading = collection.loading, cards = collection.cards, templates = templates,
            draft = draft, scenario = selectedScenario, notice = message, sequence = runningSequence,
        )
    }.stateIn(scope, SharingStarted.Eagerly, PrototypeState(templates = templates))

    val actions = PrototypeActions(
        openCreator = { if (!resetting && !cards.state.value.loading && sequence.value == null) creator.open(it) },
        closeCreator = creator::close,
        changeOrderNumber = creator::changeOrderNumber,
        generateCandidate = { creator.trial(generate = true) },
        verifyPrice = { if (!resetting && sequence.value == null) runs.start(it, appPrice = true) },
        refreshSequence = ::refreshSequence,
        advanceSequence = { nextItem?.complete(Unit) },
        cancelSequence = { sequenceJob?.cancel() },
        changePrompt = creator::changePrompt,
        changeTitle = creator::changeTitle,
        trialDraft = {
            // 演示里的接管没有真实设备操作，只是把场景拨回正常再读一次。不拨的话仍会得到需要接管。
            if (creator.draft.value?.phase == RunPhase.NEEDS_USER) changeScenario(DemoScenario.NORMAL)
            creator.trial()
        },
        saveDraft = creator::save,
        refreshCard = { if (!resetting && sequence.value == null) runs.start(it) },
        cancelCard = runs::cancel,
        resumeCard = {
            if (!resetting && sequence.value == null) {
                // 继续会把场景拨回正常再读。否则若仍停在接管、读取失败或错目标，这次刷新不会核验通过。
                changeScenario(DemoScenario.NORMAL)
                runs.start(it, appPrice = cards.find(it)?.run?.appPrice == true)
            }
        },
        deleteCard = { id -> scope.launch {
            // 先取消并等到协程结束再移除。不等的话，这次读取仍占着与试运行共用的通道。
            runs.cancelAndJoin(id)
            if (cards.update { it.filterNot { card -> card.id == id } }) notify("卡片已移除")
        } },
        changeScenario = ::changeScenario,
        resetDemo = ::reset,
        dismissNotice = { notice.value = null },
    )

    /** 模型草稿与普通创建共用同一个试运行和保存入口。 */
    fun openModelCreator(template: TemplateDefinition) {
        if (!resetting && !cards.state.value.loading && sequence.value == null) creator.open(template, model = true)
    }

    /** 手动切页项由用户确认开始；完成、超时或取消当前项后才推进。 */
    private fun refreshSequence(ids: List<String>) {
        if (resetting || sequenceJob?.isCompleted == false || creator.draft.value != null ||
            cards.state.value.cards.any { runs.hasLiveJob(it.id) }) return
        val selected = ids.distinct().mapNotNull(cards::find)
        sequenceJob = scope.launch {
            var active: Job? = null
            try {
                selected.forEachIndexed { index, card ->
                    if (needsManualSequenceStart(card)) {
                        val proceed = CompletableDeferred<Unit>()
                        nextItem = proceed
                        sequence.value = SequenceStatus(card.title, index + 1, selected.size, awaitingStart = true)
                        proceed.await()
                        nextItem = null
                    }
                    sequence.value = SequenceStatus(card.title, index + 1, selected.size, awaitingStart = false)
                    active = runs.start(card.id)
                    active?.join()
                    active = null
                }
            } finally {
                active?.cancel()
                nextItem = null
                sequence.value = null
            }
        }
    }

    /** 只执行一次。重复调用直接返回，避免再次用磁盘或种子盖掉已经在跑的内存状态。 */
    suspend fun initialize() {
        if (initialized) return
        initialized = true
        cards.initialize(initialCards())
    }

    /** 前两张模板作为种子，不带快照，界面上是待运行。 */
    private fun initialCards(): List<CardRecord> = templates.take(2).map { template ->
        CardRecord(
            id = "starter-${template.id}", title = template.title,
            kind = template.kind, rule = template.rule,
        )
    }

    /** 先写入网关再发布到界面。同一次调用里紧接着的刷新会读到新场景。 */
    private fun changeScenario(value: DemoScenario) {
        setDeviceScenario(value)
        scenario.value = value
    }

    private fun reset() {
        if (resetting) return
        resetting = true
        creator.close()
        sequenceJob?.cancel()
        scope.launch {
            try {
                // 替换前停掉并等全部运行结束。否则还在通道里的读取会继续占锁，重置后的试运行要排在它后面。
                runs.cancelAll()
                if (cards.replace(initialCards())) {
                    changeScenario(DemoScenario.NORMAL)
                    notify("已重置演示，可以重新开始")
                }
            } finally {
                // 无论替换是否成功都要解开，否则界面会一直拒绝打开和刷新。
                resetting = false
            }
        }
    }

    private fun notify(message: String) { notice.value = message }
}
