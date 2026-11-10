package com.life0423.carddock.core.session

import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.port.CardStore
import com.life0423.carddock.core.port.RuleCompiler
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
 * Application-scoped coordinator. Call its actions on the same dispatcher as [scope].
 * It contains no Android types, so lifecycle, storage and devices can be replaced independently.
 *
 * 动作必须和 [scope] 在同一调度器上调用。重置标志、初始化标志和创建代际都是普通变量，靠这个单线程约束避免并发写。
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

    private val creator = CreationController(compiler, engine, cards, lane, scope, now, newId, ::notify)
    private var initialized = false
    // 重置完成前拒绝打开、刷新和继续，避免新的读取插进种子卡替换之前。
    private var resetting = false

    // Eagerly：应用 scope 一直订阅，不依赖界面是否在收集。初始值只有模板，卡片要等 initialize。
    val state: StateFlow<PrototypeState> = combine(cards.state, creator.draft, scenario, notice) {
            collection, draft, selectedScenario, message ->
        PrototypeState(
            loading = collection.loading, cards = collection.cards, templates = templates,
            draft = draft, scenario = selectedScenario, notice = message,
        )
    }.stateIn(scope, SharingStarted.Eagerly, PrototypeState(templates = templates))

    val actions = PrototypeActions(
        openCreator = { if (!resetting && !cards.state.value.loading) creator.open(it) },
        closeCreator = creator::close,
        changePrompt = creator::changePrompt,
        changeTitle = creator::changeTitle,
        trialDraft = {
            // 演示里的接管没有真实设备操作，只是把场景拨回正常再读一次。不拨的话仍会得到需要接管。
            if (creator.draft.value?.phase == RunPhase.NEEDS_USER) changeScenario(DemoScenario.NORMAL)
            creator.trial()
        },
        saveDraft = creator::save,
        refreshCard = { if (!resetting) runs.start(it) },
        cancelCard = runs::cancel,
        resumeCard = {
            if (!resetting) {
                // 继续会把场景拨回正常再读。否则若仍停在接管、读取失败或错目标，这次刷新不会核验通过。
                changeScenario(DemoScenario.NORMAL)
                runs.start(it)
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
