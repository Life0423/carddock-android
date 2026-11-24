package com.life0423.carddock.core.model

import com.life0423.carddock.core.rule.RuleSpec

/** 展示时从成功快照投影。模板和广场只保存字段顺序，无需迁移历史卡片。 */
enum class TicketRole { COUNT, TIME, STATUS, AMOUNT, TEXT }
data class TicketFace(val fields: List<FieldValue>, val role: TicketRole) {
    /** 重排当前可见字段，不会把用户隐藏的读数带回票面。 */
    fun primaryFirst(key: String): List<DisplayField> =
        fields.sortedByDescending { it.key == key }.map { DisplayField(it.key, it.label) }
}

private val clockValue = Regex("""(?:\d{4}-\d{2}-\d{2}[ T])?(?:[01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?""")
private val moneyValue = Regex("""(?:[¥￥$€£]\s*[+-]?[\d,]+(?:\.\d+)?|[+-]?[\d,]+(?:\.\d+)?\s*(?:元|美元|欧元))""")
private val countedValue = Regex("""[\d,]+\s*(?:件|个|笔|项|份|张|条|单|次)""")

/** 先用字段原始含义，再识别自带单位或完整钟点的值，纯数字不会被猜作金额或数量。 */
private fun FieldValue.role(): TicketRole {
    when (key.lowercase()) {
        "paid", "amount", "price", "totalpaid", "balance" -> return TicketRole.AMOUNT
        "quantity", "count", "orders" -> return TicketRole.COUNT
        "time", "ordertime", "departure", "arrival", "notifytime" -> return TicketRole.TIME
        "status", "state" -> return TicketRole.STATUS
        "name", "title", "sku", "variant", "id", "ordernumber" -> return TicketRole.TEXT
    }
    val meaning = label.substringBefore('（').substringBefore('(').trim()
    return when {
        listOf("金额", "实付", "价格", "余额", "售价", "费用", "展示价", "实付合计").any(meaning::endsWith) -> TicketRole.AMOUNT
        listOf("数量", "件数", "笔数", "总数").any(meaning::endsWith) -> TicketRole.COUNT
        listOf("时间", "时刻", "预计送达").any(meaning::endsWith) -> TicketRole.TIME
        meaning.endsWith("状态") || meaning.endsWith("进度") -> TicketRole.STATUS
        moneyValue.matches(value) -> TicketRole.AMOUNT
        clockValue.matches(value) -> TicketRole.TIME
        countedValue.matches(value) -> TicketRole.COUNT
        else -> TicketRole.TEXT
    }
}

/** 用户选定的首项优先；自动布局保留提醒、计数与既有规格卡的焦点。 */
fun CardSnapshot.ticketFace(layout: List<DisplayField>, rule: RuleSpec): TicketFace {
    val ordered = if (layout.isNotEmpty()) displayFields(layout) else {
        val preferred = when {
            rule.reminderRoute != null -> fields.firstOrNull { it.key == "通知时间" }
            rule.countRoute != null -> fields.firstOrNull { it.key == "quantity" }
            rule.sourceId == "com.jingdong.app.mall" -> fields.firstOrNull { it.key == "variant" }
            else -> null
        } ?: fields.firstOrNull { it.role() != TicketRole.TEXT }
        if (preferred == null) fields else listOf(preferred) + fields.filterNot { it.key == preferred.key }
    }
    val original = fields.firstOrNull { it.key == ordered.firstOrNull()?.key }
    return TicketFace(ordered, original?.role() ?: TicketRole.TEXT)
}
