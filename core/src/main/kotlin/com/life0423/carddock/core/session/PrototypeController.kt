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
    private val cards = CardCollection(store, ::notify, now)
    private val lane = Mutex()
    private val runs = RunCoordinator(cards, engine, lane, scope, now, newId)
    private val creator = CreationController(compiler, engine, cards, lane, scope, now, newId, ::notify)
    private var initialized = false
    private var resetting = false

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
            if (creator.draft.value?.phase == RunPhase.NEEDS_USER) changeScenario(DemoScenario.NORMAL)
            creator.trial()
        },
        saveDraft = creator::save,
        refreshCard = { if (!resetting) runs.start(it) },
        cancelCard = runs::cancel,
        resumeCard = {
            if (!resetting) {
                changeScenario(DemoScenario.NORMAL)
                runs.start(it)
            }
        },
        deleteCard = { id -> scope.launch {
            runs.cancelAndJoin(id)
            if (cards.update { it.filterNot { card -> card.id == id } }) notify("卡片已移除")
        } },
        changeScenario = ::changeScenario,
        resetDemo = ::reset,
        dismissNotice = { notice.value = null },
    )

    suspend fun initialize() {
        if (initialized) return
        initialized = true
        cards.initialize(initialCards())
    }

    private fun initialCards(): List<CardRecord> = templates.take(2).map { template ->
        CardRecord(
            id = "starter-${template.id}", title = template.title,
            kind = template.kind, rule = template.rule,
        )
    }

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
                runs.cancelAll()
                if (cards.replace(initialCards())) {
                    changeScenario(DemoScenario.NORMAL)
                    notify("已重置演示，可以重新开始")
                }
            } finally {
                resetting = false
            }
        }
    }

    private fun notify(message: String) { notice.value = message }
}
