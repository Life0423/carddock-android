package com.life0423.carddock.core.session

import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.CardSnapshot
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition

enum class DemoScenario { NORMAL, NEEDS_USER, READ_FAILURE, WRONG_TARGET }

data class CreationDraft(
    val prompt: String = "",
    val title: String = "",
    val template: TemplateDefinition? = null,
    val phase: RunPhase = RunPhase.IDLE,
    val preview: CardSnapshot? = null,
    val message: String = "",
    val ruleJson: String = "",
)

data class PrototypeState(
    val loading: Boolean = true,
    val cards: List<CardRecord> = emptyList(),
    val templates: List<TemplateDefinition> = emptyList(),
    val draft: CreationDraft? = null,
    val scenario: DemoScenario = DemoScenario.NORMAL,
    val notice: String? = null,
)

/** UI events are plain callbacks so previews and tests need no Android services. */
data class PrototypeActions(
    val openCreator: (TemplateDefinition?) -> Unit,
    val closeCreator: () -> Unit,
    val changePrompt: (String) -> Unit,
    val changeTitle: (String) -> Unit,
    val trialDraft: () -> Unit,
    val saveDraft: () -> Unit,
    val refreshCard: (String) -> Unit,
    val cancelCard: (String) -> Unit,
    val resumeCard: (String) -> Unit,
    val deleteCard: (String) -> Unit,
    val changeScenario: (DemoScenario) -> Unit,
    val resetDemo: () -> Unit,
    val dismissNotice: () -> Unit,
)
