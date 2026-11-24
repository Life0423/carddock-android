package com.life0423.carddock.core.rule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versioned, data-only rule format. No scripts, reflection or arbitrary code execution.
 * schemaVersion 当前只接受 1。步骤顺序不在类型里，而在 RuleValidator：先核对目标，再读字段，必填只能引用已读输出。
 */
@Serializable
data class RuleSpec(
    val schemaVersion: Int = 1,
    val id: String,
    val sourceId: String,
    val targetKey: String,
    val steps: List<RuleStep>,
    val countRoute: CountRoute? = null,
    val reminderRoute: ReminderRoute? = null,
    val orderPath: List<String>? = null,
    val monitorComparison: MonitorComparison? = null,
    val officialAction: OfficialAction? = null,
    val meal: MealTarget? = null,
    val localReminder: LocalReminder? = null,
)

/** 用户主动触发的官方页面动作。旧卡没有此字段，仍由既有入口参数还原。 */
@Serializable
enum class OfficialAction { PICKUP_LIST, PICKUP_CODE, OPEN_MCDONALDS, SELECT_MEAL, REPEAT_MEAL, ADD_MEAL, OPEN_APP }

/** 精确商品名称及用户明确指定的门店。留空门店时沿用官方即时菜单。 */
@Serializable
data class MealTarget(val product: String, val store: String? = null, val choices: Map<String, String> = emptyMap(),
    val searchByDescription: Boolean = false, val optionHints: List<String> = emptyList())

/** 仅在本次试查中把官方门店与可读分组交给创建页；保存时只保留本人选的搭配。 */
data class MealMenu(val store: String, val groups: Map<String, List<String>>, val products: List<String> = emptyList(), val defaults: Map<String, String> = emptyMap())

/** 模板和空间卡只存用途和预算；每次试查重新读取两款商品并生成建议。 */
@Serializable
enum class MonitorUse(val label: String) { OFFICE("办公"), GAME("游戏"), PHOTO("修图"), GENERAL("综合") }

@Serializable
enum class MonitorFocus(val label: String, val use: MonitorUse) {
    TEXT("编程与文档", MonitorUse.OFFICE), MOTION("竞技与高刷", MonitorUse.GAME), DARK("暗场画面", MonitorUse.GAME)
}

@Serializable
data class MonitorComparison(val use: MonitorUse, val budgetYuan: Int? = null, val focus: MonitorFocus? = null)

/** 持久化用的步骤。序列化名字是稳定协议，不能随 Kotlin 类名变化。 */
@Serializable
sealed interface RuleStep {
    /** 核对观察到的对象键。不读取字段。 */
    @Serializable
    @SerialName("assertTarget")
    data class AssertTarget(val expectedKey: String) : RuleStep

    /** 指定读取 sourceField。引擎去掉首尾空白后写入 outputKey，数据类本身不取设备值。 */
    @Serializable
    @SerialName("readField")
    data class ReadField(
        val sourceField: String,
        val outputKey: String,
        val label: String,
    ) : RuleStep

    /** 要求此前读取的 outputKey 非空白。不能用来读取新字段。 */
    @Serializable
    @SerialName("requireField")
    data class RequireField(val outputKey: String) : RuleStep
}


/** 模型确认后的三个字段锚点。只持久化控件类型与结构，不保存节点序号或某次事件的文字。 */
@Serializable
data class ReminderRoute(val title: FieldAnchor, val start: FieldAnchor, val advance: FieldAnchor)

@Serializable
data class FieldAnchor(val role: String, val parentRole: String, val resource: String? = null)

/** 用户指定的页面及分类和本机学到的同组字段锚点。数值从每次查询的页面读取。 */
@Serializable
data class CountRoute(val page: String?, val subject: String, val label: FieldAnchor, val quantity: FieldAnchor)
