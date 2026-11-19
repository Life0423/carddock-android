package com.life0423.carddock.platform

import com.life0423.carddock.core.engine.OrderObservation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.format.DateTimeFormatter

/** 从账单反查原订单。日期只定位候选，订单身份、下单时间及扣款与订单合计都要对上。 */
internal class LinkedOrderReceipt(private val target: OrderObservation, private val orderTotal: Long) {
    suspend fun read(
        connected: () -> Boolean, observe: () -> DetailWindow?, tap: (DetailNode) -> Boolean,
        scroll: (DetailNode) -> Boolean
    ): Long? = withTimeoutOrNull(45000) {
        val day = target.placed.format(DateTimeFormatter.ofPattern("MM-dd"))
        val date = Regex("""(?:\d{4}-)?\d\d-\d\d \d\d:\d\d(?::\d\d)?""")
        var amount: Long? = null
        var inBills = false
        var openedCandidate = false
        var pages = 0
        val used = mutableSetOf<String>()
        while (connected()) {
            delay(500)
            val window = observe() ?: continue
            if (window.password) return@withTimeoutOrNull null
            val live = window.nodes.filter { it.visible && it.bounds?.isEmpty == false }
            val labels = live.map { it.label }
            if (amount != null) {
                val fields = window.orderSemantics()
                val ids = fields.orderIdentities()
                val times = fields.orderTimes()
                if (ids.isNotEmpty() && times.isNotEmpty())
                    return@withTimeoutOrNull amount.takeIf { ids.singleOrNull() == target.identity &&
                        times.singleOrNull() == target.placed && "订单已完成" in fields }
                val expand = live.singleOrNull { it.label.endsWith("等") && "支付方式" in it.label }
                if (expand != null && used.add(expand.label)) tap(expand)
                continue
            }
            if (openedCandidate) {
                val status = live.filter { it.label == "支付成功" }.distinctBy { it.bounds }.singleOrNull() ?: continue
                val backLink = live.filter { it.label == "查看原订单" }.distinctBy { it.bounds }.singleOrNull() ?: continue
                if (labels.any { it.contains("分次支付") || it.contains("组合支付") || it.contains("补付") }) return@withTimeoutOrNull null
                // 只取支付状态正上方水平重叠的扣款，与原单币种明确的合计交叉核验。
                val box = checkNotNull(status.bounds)
                val debits = live.filter { it.bounds!!.left < box.right && it.bounds.right > box.left &&
                    it.bounds.bottom <= box.top && box.top - it.bounds.bottom <= box.height() * 2 }
                    .mapNotNull { Regex("""[-−]\s*(?:[¥￥]\s*)?(\d{1,9}(?:\.\d{1,2})?)""").matchEntire(it.label)
                        ?.groupValues?.get(1)?.toBigDecimal()?.movePointRight(2)?.longValueExact() }.distinct()
                val paid = debits.singleOrNull()?.takeIf { it == orderTotal } ?: return@withTimeoutOrNull null
                if (tap(backLink)) amount = paid
                continue
            }
            if (inBills) {
                if (labels.none { it in setOf("我的账单","全部账单","账单明细","账单") }) continue
                val rows = live.filter { date.matches(it.label) && it.label.substringBefore(' ').endsWith(day) }
                    .distinctBy { it.bounds }
                if (rows.size > 1) return@withTimeoutOrNull null
                if (rows.size == 1 && tap(rows.single())) { openedCandidate = true; continue }
                val pane = live.filter { it.scrollable && it.canGoForward }.maxByOrNull { it.bounds!!.height() }
                if (pane == null || pages++ > 12 || !scroll(pane)) return@withTimeoutOrNull null
                delay(600)
                continue
            }
            // 只读入口名称均来自当前树。不会进入支付、转账或经过其他应用。
            val next = listOf("我的账单","账单明细","账单","我的","个人中心").firstNotNullOfOrNull { name ->
                live.filter { it.label == name && name !in used }.distinctBy { it.bounds }.singleOrNull()
            } ?: continue
            if (tap(next)) {
                used += next.label
                inBills = next.label !in setOf("我的","个人中心")
                delay(800)
            }
        }
        null
    }
}
