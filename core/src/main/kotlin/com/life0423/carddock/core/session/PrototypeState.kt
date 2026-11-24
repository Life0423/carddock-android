package com.life0423.carddock.core.session

import com.life0423.carddock.core.model.PhoneCapability

import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.PersonalTemplate
import com.life0423.carddock.core.model.CardSnapshot
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateDefinition

/** 演示设备的四种观察。已经进入延时的那次读取用进入时的值，不受等待期间切换的影响。 */
enum class DemoScenario { NORMAL, NEEDS_USER, READ_FAILURE, WRONG_TARGET }

/**
 * 创建弹层里的一份草稿，还不是已保存的卡片。
 * 排队或运行中时，提示词和标题的修改会被丢掉。
 * 提示词一旦改动，模板和预览都会清掉，必须重新试运行才能保存。
 */
data class CreationDraft(
    val prompt: String = "",
    val mealMenu: com.life0423.carddock.core.rule.MealMenu? = null,
    val preferredTheme: com.life0423.carddock.core.model.CardTheme? = null,
    val title: String = "",
    val template: TemplateDefinition? = null,
    val phase: RunPhase = RunPhase.IDLE,
    val preview: CardSnapshot? = null,
    val message: String = "",
    val orderNumber: String = "",
    val routeOid: String = "",
    val personalTemplateId: String? = null,
    val description: String = "",
    val saving: Boolean = false,
    val modelCandidate: Boolean = false,
    val ruleChangeCard: CardRecord? = null,
    val demonstrations: List<com.life0423.carddock.core.port.DiscoveredTemplate> = emptyList(),
    val rollingBackRule: Boolean = false,
    val plans: List<TemplateDefinition> = emptyList(),
    val missingCapability: PhoneCapability? = null,
)

/**
 * 界面收集到的整屏状态。loading 为真时控制器拒绝打开创建。刷新没有这道检查，加载中界面不画出卡片。
 * draft 非空表示创建弹层开着。notice 由界面显示一次后清掉，不是持久错误位。
 */
data class PrototypeState(
    val loading: Boolean = true,
    val cards: List<CardRecord> = emptyList(),
    val templates: List<TemplateDefinition> = emptyList(),
    val draft: CreationDraft? = null,
    val scenario: DemoScenario = DemoScenario.NORMAL,
    val notice: String? = null,
    val sequence: SequenceStatus? = null,
    val personalTemplates: List<PersonalTemplate> = emptyList(),
    val cardsReadable: Boolean = true,
)

/** 顺序核验停在需要用户切页的项目之前，明确展示当前进度。 */
data class SequenceStatus(val title: String, val index: Int, val total: Int, val awaitingStart: Boolean)

/**
 * UI events are plain callbacks so previews and tests need no Android services.
 * 这些是普通函数，不含 Android 类型。必须和控制器跑在同一个串行调度器上。
 * 生产用 Dispatchers.Main.immediate。共用一个多线程调度器并不够，代际和重置标志没有多线程保护。
 */
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
    val openTemplateEditor: (String?) -> Unit = {},
    val chooseDraftTemplate: (TemplateDefinition) -> Unit = {},
    val editReminder: (String, com.life0423.carddock.core.rule.LocalReminder) -> Unit = { _, _ -> },
    val changeTheme: (com.life0423.carddock.core.model.CardTheme?) -> Unit = {},
    val changeDisplayFields: (List<com.life0423.carddock.core.model.DisplayField>) -> Unit = {},
    val changeTemplateDescription: (String) -> Unit = {},
    val keepTemplateDraft: () -> Unit = {},
    val deleteTemplate: (String) -> Unit = {},
    val changeRouteOid: (String) -> Unit = {},
    val changeOrderNumber: (String) -> Unit = {},
    val demonstrateField: (Int) -> Unit = {},
    val cancelTrial: () -> Unit = {},
    val prepareTemplate: () -> Unit = {},
    val compareFilterForTrial: () -> Unit = {},
    val compareFilterForCard: (String) -> Unit = {},
    val generateCandidate: () -> Unit = {},
    val verifyPrice: (String) -> Unit = {},
    val refreshSequence: (List<String>) -> Unit = {},
    val advanceSequence: () -> Unit = {},
    val cancelSequence: () -> Unit = {},
    val toggleFavorite: (String) -> Unit = {},
    val setCardPrimary: (String, String) -> Unit = { _, _ -> },
    val changeCardRule: (String, Boolean) -> Unit = { _, _ -> },

)
