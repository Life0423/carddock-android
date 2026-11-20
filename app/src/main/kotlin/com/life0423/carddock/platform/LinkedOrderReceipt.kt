package com.life0423.carddock.platform

import com.life0423.carddock.core.engine.OrderObservation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.format.DateTimeFormatter

/** 日期须在交易视口；回链须属于当前成功支付凭证。两者都要求唯一标签及无遮挡的点击区域。 */
internal fun DetailWindow.exposesReceiptTarget(target: DetailNode): Boolean {
    val box = target.bounds ?: return false
    if (password || box.isEmpty) return false
    val field = nodes.singleOrNull { it.visible && it.role == target.role &&
        it.label == target.label && it.bounds == box } ?: return false
    val path = orderPath(field)
    if (field.label == "查看原订单") {
        val status = uniqueOrderNode(nodes.filter { it.visible && it.label == "支付成功" && it.bounds?.isEmpty == false }) ?: return false
        if (!sameOrderSurface(status,field)) return false
    } else if (path.none { i -> nodes[i].scrollable && nodes[i].bounds?.contains(box) == true }) return false
    val x = box.centerX()
    val y = box.centerY()
    return path.drop(1).none { i ->
        val branch = nodes[i]
        nodes.any { other -> other !== branch && other.parent == branch.parent && other.visible &&
            other.bounds?.contains(x,y) == true &&
            (branch.drawingOrder <= 0 || other.drawingOrder <= 0 || other.drawingOrder >= branch.drawingOrder) }
    }
}

/** 从账单反查原订单。日期只定位候选，订单身份、下单时间及扣款与订单合计都要对上。 */
internal class LinkedOrderReceipt(private val target: OrderObservation, private val orderTotal: Long) {
    var stage = "打开账单入口"
        private set

    suspend fun read(
        connected: () -> Boolean, observe: () -> DetailWindow?, tap: (DetailNode) -> Boolean,
        scroll: (DetailNode) -> Boolean, tapReceipt: (DetailNode) -> Boolean = tap
    ): Long? = withTimeoutOrNull(45000) {
        val day = target.placed.format(DateTimeFormatter.ofPattern("MM-dd"))
        val date = Regex("""(?:\d{4}-)?\d\d-\d\d \d\d:\d\d(?::\d\d)?""")
        var amount: Long? = null
        var inBills = false
        var openedCandidate = false
        var openedFullLedger = false
        var pages = 0
        val used = mutableSetOf<String>()
        while (connected()) {
            delay(500)
            val window = observe() ?: continue
            if (window.password) return@withTimeoutOrNull null
            val live = window.nodes.filter { it.visible && it.bounds?.isEmpty == false }
            val labels = live.map { it.label }
            if (amount != null) {
                stage = "核对原订单身份和下单时间"
                val fields = window.orderSemantics()
                val ids = fields.orderIdentities()
                val times = fields.orderTimes()
                if (ids.isNotEmpty() && times.isNotEmpty())
                    return@withTimeoutOrNull amount.takeIf { ids.singleOrNull() == target.identity &&
                        times.singleOrNull() == target.placed && "订单已完成" in fields }
                val expand = live.singleOrNull { it.label.endsWith("等") && "支付方式" in it.label && it.label !in used }
                if (expand != null && tap(expand)) used.add(expand.label)
                continue
            }
            if (openedCandidate) {
                stage = "核对支付状态和查看原订单入口"
                val status = window.uniqueOrderNode(live.filter { it.label == "支付成功" }) ?: continue
                val backLink = window.uniqueOrderNode(live.filter { it.label == "查看原订单" }) ?: continue
                if (!window.sameOrderSurface(status,backLink)) return@withTimeoutOrNull null
                if (labels.any { it.contains("分次支付") || it.contains("组合支付") || it.contains("补付") }) return@withTimeoutOrNull null
                // 只取支付状态正上方水平重叠的扣款，与原单币种明确的合计交叉核验。
                val box = checkNotNull(status.bounds)
                val debits = live.filter { window.sameOrderSurface(status,it) && window.sameOrderSurface(backLink,it) &&
                    it.bounds!!.left < box.right && it.bounds.right > box.left &&
                    it.bounds.bottom <= box.top && box.top - it.bounds.bottom <= box.height() * 2 }
                    .mapNotNull { Regex("""[-−]\s*(?:[¥￥]\s*)?(\d{1,9}(?:\.\d{1,2})?)""").matchEntire(it.label)
                        ?.groupValues?.get(1)?.toBigDecimal()?.movePointRight(2)?.longValueExact() }.distinct()
                stage = "核对支付成功金额（候选 ${debits.size}）"
                val paid = debits.singleOrNull() ?: return@withTimeoutOrNull null
                if (paid != orderTotal) {
                    stage = "该笔扣款与原订单合计不同"
                    return@withTimeoutOrNull null
                }
                stage = "金额一致，打开官方原单回链"
                if (!tapReceipt(backLink)) return@withTimeoutOrNull null
                amount = paid
                continue
            }
            if (inBills) {
                stage = "等候带日期的账单列表"
                // 账单首屏可能是当月收支摘要，交易日期在其下方。摘要只用于定位，只从支付凭证取金额。
                val ledgerScreen = labels.any { it in setOf("全部账单","账单明细","账单") } ||
                    "收支统计" in labels && "支出" in labels && "收入" in labels
                if (!ledgerScreen) continue
                // 摘要的三条交易和完整流水是两个页面。点入口后等到流水页标记出现再读取日期。
                if (openedFullLedger && ("筛选" !in labels || "查看全部账单" in labels)) {
                    stage = "等待完整账单列表"
                    continue
                }
                val rows = live.filter { date.matches(it.label) && it.label.substringBefore(' ').endsWith(day) }
                    .distinctBy { it.bounds }
                stage = "核对同日交易（候选 ${rows.size}，第 ${pages + 1} 屏）"
                if (rows.size > 1) return@withTimeoutOrNull null
                if (rows.size == 1) {
                    stage = "打开已定位的同日交易"
                    if (!tapReceipt(rows.single())) return@withTimeoutOrNull null
                    openedCandidate = true
                    continue
                }
                val allBills = window.uniqueOrderNode(live.filter { it.label == "查看全部账单" })
                if (!openedFullLedger && allBills != null) {
                    stage = "从交易摘要打开完整账单"
                    if (!tapReceipt(allBills)) return@withTimeoutOrNull null
                    openedFullLedger = true
                    pages = 0
                    continue
                }
                val pane = live.filter { it.scrollable && it.canGoForward }.maxByOrNull { it.bounds!!.height() }
                if (pane == null) {
                    stage = "等待可滚动的账单记录"
                    continue
                }
                if (pages++ >= 12 || !scroll(pane)) return@withTimeoutOrNull null
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
