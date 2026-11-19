package com.life0423.carddock.core.engine

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.ZonedDateTime

/** 已经绑定到同一订单的字段。金额缺失与真实零金额分别处理。 */
data class OrderObservation(val identity: String, val placed: java.time.LocalDateTime, val completed: Boolean,
    val takeout: Boolean, val originalPaidCents: Long?)

/** 每次查询固定一次截止点，同一账本只接收从顶部到显式末尾的连续页面。 */
class MonthlyOrderLedger(val until: ZonedDateTime) {
    val since = until.minusMonths(1)
    private val orders = linkedMapOf<String, OrderObservation>()
    private var previous = emptyList<String>()
    private var pages = 0
    private var finished = false
    val observedCount: Int get() = orders.size

    /** 只接受唯一行且页间有序重叠。页面校验失败时本页不写入账本。 */
    fun accept(rows: List<OrderObservation>, atStart: Boolean, atEnd: Boolean): String? {
        if (finished || (pages == 0 && !atStart)) return "无法核对列表起点。"
        val ids = rows.map { it.identity }
        if (ids.any { it.isBlank() } || ids.distinct().size != ids.size) return "单页缺少唯一的订单标识。"
        if (rows.isEmpty() && !(pages == 0 && atStart && atEnd)) return "没有取得完整订单页。"
        if (pages > 0) {
            val overlap = (minOf(previous.size, ids.size) downTo 1).firstOrNull { previous.takeLast(it) == ids.take(it) }
                ?: return "相邻页面缺少连续订单，请重试。"
            if (ids.drop(overlap).any { it in orders } || overlap == ids.size && !atEnd) return "列表顺序改变或滚动没有进展。"
        }
        for (row in rows) {
            if (orders[row.identity]?.let { it != row } == true) return "同一订单在两次读取间发生变化。"
            if (included(row) && (row.originalPaidCents == null || row.originalPaidCents < 0)) return "缺少可核对的退款前原实付金额。"
        }
        rows.forEach { orders[it.identity] = it }
        previous = ids
        pages++
        finished = atEnd
        return null
    }

    private fun included(order: OrderObservation) = order.completed && order.takeout &&
        order.placed >= since.toLocalDateTime() && order.placed < until.toLocalDateTime()

    /** 指纹用于双轮逐单对比。模板和卡片都不保存原订单号。 */
    fun result(): Summary? {
        if (!finished) return null
        val selected = orders.values.filter(::included).sortedBy { it.identity }
        val cents = selected.fold(0L) { n, row -> Math.addExact(n, checkNotNull(row.originalPaidCents)) }
        val digest = MessageDigest.getInstance("SHA-256")
        for (row in selected) digest.update("${row.identity.length}:${row.identity}|${row.placed}|${row.originalPaidCents}\n".toByteArray(Charsets.UTF_8))
        return Summary(selected.size, BigDecimal.valueOf(cents,2).toPlainString(), digest.digest().joinToString("") { "%02x".format(it) })
    }
    data class Summary(val count: Int, val total: String, val digest: String)
}
