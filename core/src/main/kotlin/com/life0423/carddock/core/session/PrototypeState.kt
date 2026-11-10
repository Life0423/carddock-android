package com.life0423.carddock.core.session

import com.life0423.carddock.core.model.CardRecord
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
    val title: String = "",
    val template: TemplateDefinition? = null,
    val phase: RunPhase = RunPhase.IDLE,
    val preview: CardSnapshot? = null,
    val message: String = "",
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
)

/**
 * UI events are plain callbacks so previews and tests need no Android services.
 * 这些是普通函数，不含 Android 类型。调用线程必须和控制器的 scope 相同，代际和重置标志不是线程安全的。
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
)
