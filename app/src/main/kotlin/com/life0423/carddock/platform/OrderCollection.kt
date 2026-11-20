package com.life0423.carddock.platform

import android.graphics.Rect
import com.life0423.carddock.core.engine.MonthlyOrderLedger
import com.life0423.carddock.core.engine.OrderObservation
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal const val MONTHLY_ORDERS = "monthly-original-paid"
private val navigation = setOf("我的","个人中心","订单","全部订单","我的订单","订单中心","订单列表","历史订单","交易记录","全部交易")
private val orderId = Regex("""(?:订单号(?:码)?|订单编号)[：:]?\s*([A-Za-z0-9-]{6,64})""")
private val placed = Regex("""下单时间[：:]?\s*(\d{4}-\d{2}-\d{2})(?:\s+(\d{2}:\d{2})(:\d{2})?)?""")
private val money = Regex("""(?:退款前原实付|原实付(?:金额)?|订单实付(?:金额)?|实付(?:金额|款)?)[：:]?\s*(?:[￥¥]\s*(\d{1,9}(?:\.\d{1,2})?)|(\d{1,9}(?:\.\d{1,2})?)\s*元)""")
private val complete = setOf("已完成","订单已完成","交易完成")
private val excluded = setOf("已取消","交易关闭","待付款","配送中","进行中","待收货")
private val delivery = setOf("订单类型：外卖","订单类型：餐饮外卖","业务类型：外卖")
private val otherType = setOf("到店","酒店","电影","团购","堂食","闪购").map { "订单类型："+it }.toSet()
private val endLabels = setOf("没有更多订单","没有更多订单了","已显示全部订单","已加载全部订单","没有更多了","没有更多数据")
private val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT)

/** 在可见详情中绑定独占同一行的标签和值。 */
internal fun DetailWindow.orderSemantics(): List<String> {
    val live = nodes.filter { it.visible && it.bounds?.isEmpty == false && it.label.isNotBlank() }
    val keys = setOf("下单时间","订单号码","订单编号","订单号","原实付金额","退款前原实付","订单实付","实付金额","实付",
        "订单类型","业务类型","订单状态","合计")
    val pairs = live.filter { it.label.trimEnd('：',':') in keys }.mapNotNull { label ->
        val box = checkNotNull(label.bounds)
        // 原生和网页都可能把复制按钮放在同一行。只接受位于标签右侧且同高的唯一文本值。
        val siblings = live.filter { it !== label && it.parent == label.parent && it.label != "复制" }
        val format = when (label.label.trimEnd('：', ':')) {
            "订单号码","订单号","订单编号" -> Regex("""[a-zA-Z0-9][a-zA-Z0-9 -]{5,69}""")
            "下单时间" -> Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
            else -> null
        }
        if (format != null) {
            val value = siblings.map { it.label }.distinct().singleOrNull(format::matches)
            if (value != null) return@mapNotNull label.label.trimEnd('：', ':') + "：" + value
        }
        if (label.label == "合计") {
            val right = live.filter { it.bounds!!.left >= box.right && it.bounds.centerY() in box.top..box.bottom }
                .sortedBy { it.bounds!!.left }.map { it.label }.distinct()
            if (right.size == 2 && right[0] in setOf("¥","￥") && Regex("""\d{1,9}(?:\.\d{1,2})?""").matches(right[1]))
                return@mapNotNull "合计：" + right.joinToString("")
        }
        val values = live.filter { v -> v != label && v.label !in keys && v.label != "复制" &&
            v.bounds!!.left >= box.right && v.bounds.centerY() in box.top..box.bottom && v.role?.endsWith("TextView") == true }
            .map { it.label }.distinct()
        values.singleOrNull()?.let { label.label.trimEnd('：',':') + "：" + it }
    }
    return live.map { it.label } + pairs
}

/** 订单号只在一次本机核对期间使用。分组排版的数字订单号需要合并空格。 */
internal fun Collection<String>.orderIdentities() = mapNotNull {
    orderId.matchEntire(it.replace(Regex("""(?<=\d) (?=\d)"""), ""))?.groupValues?.get(1)
}.distinct()

internal fun Collection<String>.orderTimes() = mapNotNull { field ->
    val match = placed.matchEntire(field)?.takeIf { it.groupValues[3].isNotEmpty() } ?: return@mapNotNull null
    try { LocalDateTime.parse(match.groupValues[1] + " " + match.groupValues[2] + match.groupValues[3], formatter) }
    catch (_: java.time.format.DateTimeParseException) { null }
}.distinct()

private data class WindowPage(val view: DetailNode, val markers: List<DetailNode>, val end: Boolean,
    val rowNumbers: List<Int?>, val reportedCounts: List<Int>)

/** 原生按钮常把文字放在子控件；仅当可点击祖先只含同一标签时，才将它作为点击目标。 */
internal fun DetailWindow.orderActionBounds(target: DetailNode): Rect? {
    val index = nodes.indexOfFirst { it === target }
    if (index < 0) return target.bounds
    val targetChain = mutableSetOf(index)
    var parent = target.parent
    while (parent >= 0) {
        val owner = nodes[parent]
        if (owner.clickable && owner.visible && owner.bounds?.contains(target.bounds!!) == true) {
            val subtree = mutableSetOf(parent)
            for (i in parent + 1 until nodes.size) {
                val child = nodes[i]
                if (child.parent !in subtree) break
                subtree += i
                if (child.visible && (child.clickable && i !in targetChain || child.label.isNotBlank() && child.label != target.label)) return target.bounds
            }
            return owner.bounds.takeIf { owner.label.isBlank() || owner.label == target.label } ?: target.bounds
        }
        targetChain += parent
        parent = owner.parent
    }
    return target.bounds
}

internal fun DetailWindow.orderPath(node: DetailNode): List<Int> {
    val index = nodes.indexOfFirst { it === node }
    if (index < 0) return emptyList()
    return generateSequence(index) { i -> nodes[i].parent.takeIf { it >= 0 } }.toList().asReversed()
}

/** 不把叠放的两个页面拼成一组控件。查看分叉处各自的区域，而非只比较叶节点。 */
internal fun DetailWindow.sameOrderSurface(a: DetailNode, b: DetailNode): Boolean {
    val first = orderPath(a)
    val second = orderPath(b)
    val split = first.zip(second).indexOfFirst { (x,y) -> x != y }
    if (first.isEmpty() || second.isEmpty() || split == 0) return false
    if (split < 0) return true
    val one = nodes[first[split]].bounds ?: return false
    val two = nodes[second[split]].bounds ?: return false
    return a.bounds != null && b.bounds != null && !one.contains(b.bounds) && !two.contains(a.bounds)
}

internal val orderListTitles = setOf("搜索订单","搜索我的订单","全部订单","订单列表","历史订单","我的订单")
internal val orderPanelTitles = setOf("订单筛选","筛选条件","订单过滤")

/** 在控件树中找到包含锚点的最小同页分支。页面切换期间仍可见的背景节点不能提供字段。 */
internal fun DetailWindow.orderRegion(anchor: DetailNode, matches: (List<DetailNode>) -> Boolean): List<DetailNode>? {
    val position = nodes.indexOfFirst { it === anchor }
    if (position < 0) return null
    var root = nodes[position].parent
    while (root >= 0) {
        val indices = mutableSetOf(root)
        val live = mutableListOf<DetailNode>()
        for (i in root until nodes.size) {
            val n = nodes[i]
            if (i != root && n.parent !in indices) break
            indices += i
            if (n.visible && n.bounds?.isEmpty == false) live += n
        }
        if (matches(live)) return live
        root = nodes[root].parent
    }
    return null
}

/** 同一标签可能由容器和文字子控件重复上报；相邻或不同页面的节点不能按面积合并。 */
internal fun DetailWindow.uniqueOrderNode(candidates: List<DetailNode>): DetailNode? {
    if (candidates.size <= 1) return candidates.singleOrNull()
    val deepest = candidates.maxBy { orderPath(it).size }
    val path = orderPath(deepest)
    if (candidates.all { n -> n.label == deepest.label && path.any { nodes[it] === n } }) return deepest
    return foregroundOrderNode(candidates)
}

/** 比较候选所在分支的绘制顺序。用覆盖背景的页面容器判断，不要求两个列表的视口等高。 */
internal fun DetailWindow.foregroundOrderNode(candidates: List<DetailNode>): DetailNode? {
    if (candidates.size <= 1) return candidates.singleOrNull()
    val paths = candidates.map { orderPath(it) }
    return candidates.indices.singleOrNull { front ->
        candidates.indices.filter { it != front }.all { back ->
            val frontPath = paths[front]
            val backPath = paths[back]
            val split = frontPath.zip(backPath).indexOfFirst { (a,b) -> a != b }
            if (split < 1) false else {
                val upper = nodes[frontPath[split]]
                val lower = nodes[backPath[split]]
                val area = upper.bounds
                lower.drawingOrder > 0 && upper.drawingOrder > lower.drawingOrder && area != null &&
                    area.contains(candidates[front].bounds!!) && area.contains(candidates[back].bounds!!)
            }
        }
    }?.let(candidates::get)
}

/** 筛选后，部分页面将新结果插在原列表和固定工具栏之间。三者必须同父，且保留提交前的原视口。 */
internal fun DetailWindow.ownsResultToolbar(
    control: DetailNode, result: DetailNode, lists: List<DetailNode>, previous: Rect?
): Boolean {
    if (previous == null || lists.size != 2) return false
    val old = lists.singleOrNull { it !== result && it.bounds == previous } ?: return false
    val view = result.bounds ?: return false
    val resultPath = orderPath(result)
    val barPath = orderPath(control)
    val oldPath = orderPath(old)
    val split = resultPath.zip(barPath).indexOfFirst { (a,b) -> a != b }
    if (split < 1 || oldPath.size <= split || oldPath.take(split) != resultPath.take(split)) return false
    val body = nodes[resultPath[split]]
    val bar = nodes[barPath[split]]
    val prior = nodes[oldPath[split]]
    val toolbar = bar.bounds ?: return false
    val area = body.bounds ?: return false
    return prior.drawingOrder > 0 && body.drawingOrder == prior.drawingOrder + 1 &&
        bar.drawingOrder == body.drawingOrder + 1 && area.contains(previous) && area.contains(view) &&
        area.top == toolbar.top && toolbar.left == view.left && toolbar.right == view.right &&
        toolbar.contains(control.bounds!!) && view.top - toolbar.bottom in 0..control.bounds.height() &&
        view != previous
}

/** 一个查询的内存态。详情和列表通过独立后台屏打开，不向主屏发送返回键。 */
internal class OrderCollection(private val source: String, until: ZonedDateTime, path: List<String>?, val compareFilter: Boolean = false) {
    private val ledger = MonthlyOrderLedger(until)
    val steps = path.orEmpty().toMutableList()
    private var learning = path == null
    var stage: String = "打开来源"
        private set
    private val signatures = mutableListOf<List<String>>()
    private val current = mutableListOf<OrderObservation>()
    private var awaitingReceipt: Pair<OrderObservation, Long>? = null
    private val seenRows = mutableMapOf<Int, String>()
    private var expectedRows: Int? = null
    private var everyRowNumbered = true
    private var pageIndex = 0
    var anotherWindow = false
        private set
    var digest: String? = null
        private set

    suspend fun runWindow(connected: () -> Boolean, observe: () -> DetailWindow?, tap: (DetailNode) -> Boolean,
        scroll: (DetailNode) -> Boolean, choose: ((List<String>) -> String?)?,
        sample: (suspend (List<DetailNode>) -> List<Int>?)? = null, panelVisible: (Boolean) -> Unit = {},
        tapReceipt: (DetailNode) -> Boolean = tap): DeviceResponse {
        anotherWindow = false
        awaitingReceipt?.let { (order, total) ->
            stage = "从账单反查原订单"
            val receipt = LinkedOrderReceipt(order, total)
            val paid = receipt.read(connected, observe, tap, scroll, tapReceipt)
                ?: return DeviceResponse.NeedsUser("账单扣款与原订单未能逐项核对（${receipt.stage}），未提交总额。")
            current += order.copy(originalPaidCents = paid)
            awaitingReceipt = null
            anotherWindow = true
            return DeviceResponse.NeedsUser("已核对本笔，继续读取订单列表。")
        }
        val trial = withTimeoutOrNull(75000) {
            var filtered = false
            var originalViewport: Rect? = null
            var step = 0
            var pageNumber = 0
            var previousPage: List<String>? = null
            var detailMarker: String? = null
            val expanded = mutableSetOf<String>()
            var scrolls = 0
            var idleReads = 0
            val detail = mutableSetOf<String>()
            while (connected()) {
                delay(400)
                val window = observe() ?: continue
                if (window.password) return@withTimeoutOrNull DeviceResponse.NeedsUser("来源正在要求身份验证。")
                val live = window.nodes.filter { it.visible && it.bounds?.isEmpty == false }
                val labels = live.map { it.label }
                val marker = detailMarker
                if (marker != null) {
                    // 首先核对确实离开了订单集合。只累计同一详情中的观察。
                    if (listWindows(window).isNotEmpty()) continue
                    if (labels.none { it in complete || it in excluded || it in setOf("订单信息","订单详情","费用明细","订单号码","下单时间") ||
                            orderId.matches(it) }) continue
                    val changed = detail.addAll(window.orderSemantics())
                    if (changed) idleReads = 0
                    val ids = detail.orderIdentities()
                    val dates = detail.orderTimes()
                    // 普通“实付”可能已反映退款；只有明确原实付可直接进入账本。
                    val amounts = detail.filter { it.startsWith("退款前原实付") || it.startsWith("原实付") }.mapNotNull {
                        money.matchEntire(it)?.groupValues?.drop(1)?.firstOrNull(String::isNotEmpty)?.toBigDecimal()?.movePointRight(2)?.longValueExact()
                    }.distinct()
                    val status = detail.any { it == "订单已完成" || it == "订单状态：已完成" }
                    val hasState = status.xor(detail.any { it.removePrefix("订单状态：") in excluded && it.startsWith("订单状态：") })
                    val isDelivery = filtered || detail.any { it in delivery }
                    val hasType = isDelivery.xor(detail.any { it in otherType })
                    val at = dates.singleOrNull()
                    val prefix = placed.matchEntire(marker)?.value?.substringAfter("下单时间")?.trimStart('：',':',' ')
                    if (at != null && prefix != null && !at.format(formatter).startsWith(prefix))
                        return@withTimeoutOrNull DeviceResponse.NeedsUser("列表时间与详情不一致，本轮未更新。")
                    if (ids.size == 1 && at != null && hasState &&
                        (at < ledger.since.toLocalDateTime() || at >= ledger.until.toLocalDateTime() ||
                         !status || hasType && (!isDelivery || amounts.size == 1))) {
                        current += OrderObservation(ids.single(),at,status,isDelivery,amounts.singleOrNull())
                        anotherWindow = true
                        return@withTimeoutOrNull DeviceResponse.NeedsUser("继续核对下一笔。")
                    }
                    if (idleReads++ < 3) continue
                    if (ids.size == 1 && at != null && hasState && status && isDelivery && hasType && amounts.isEmpty()) {
                        val totals = detail.mapNotNull { field ->
                            Regex("""合计：[¥￥](\d{1,9}(?:\.\d{1,2})?)""").matchEntire(field)?.groupValues?.get(1)
                                ?.toBigDecimal()?.movePointRight(2)?.longValueExact()
                        }.distinct()
                        totals.singleOrNull()?.let { total ->
                            awaitingReceipt = OrderObservation(ids.single(), at, true, true, null) to total
                            anotherWindow = true
                            return@withTimeoutOrNull DeviceResponse.NeedsUser("继续核对原订单支付记录。")
                        }
                    }
                    val expand = live.filter { it.label in setOf("展开订单信息","展开详情","查看明细","费用明细","金额明细","支付明细") ||
                        (it.label.endsWith("等") && it.label.contains("支付方式")) }.firstOrNull { it.label !in expanded }
                    if (expand != null && tap(expand)) { expanded += expand.label; delay(300); continue }
                    val body = live.filter { it.scrollable && (it.canGoForward || it.role?.endsWith("ScrollView") == true) }.maxByOrNull { it.bounds!!.height() }
                    if (body != null && scrolls++ < 7 && scroll(body)) { idleReads = 0; delay(650); continue }
                    val missing = buildList {
                        if (ids.size != 1) add("唯一订单号")
                        if (at == null) add("精确下单时间")
                        if (!hasState) add("明确订单状态")
                        if (!hasType) add("订单类型")
                        if (amounts.size != 1) add("退款前原实付")
                    }
                    return@withTimeoutOrNull DeviceResponse.NeedsUser("订单详情未能唯一核对：" + missing.joinToString("、") + "。未提交总额。")
                }

                val located = listWindows(window)
                val listHeading = labels.any { it in orderListTitles }
                val page = activeOrderPage(window, located, originalViewport)?.takeIf { listHeading }
                if (!filtered && pageNumber == 0 && listHeading && located.isNotEmpty() && live.any { it.label == "筛选" }) {
                    originalViewport = located.singleOrNull()?.view?.bounds
                    stage = "重新核对时间和外卖筛选"
                    val reason = try {
                        applyOrderFilters({ if (connected()) observe() else null }, tap, sample, panelVisible) { view ->
                            val lists = listWindows(view)
                            val result = activeOrderPage(view, lists, originalViewport) ?: return@applyOrderFilters null
                            val viewports = lists.map { it.view }
                            val filters = view.nodes.filter { n ->
                                if (!n.visible || n.label != "筛选" || n.bounds?.isEmpty != false ||
                                    n.bounds.bottom > result.view.bounds!!.top) return@filter false
                                view.ownsResultToolbar(n,result.view,viewports,originalViewport) ||
                                    view.sameOrderSurface(n, result.view) && view.orderRegion(n) { region ->
                                        region.any { it === result.view } &&
                                            lists.none { other -> other !== result && region.any { it === other.view } }
                                    } != null
                            }
                            view.foregroundOrderNode(filters)
                        }
                    } finally { panelVisible(false) }
                    if (reason != null) return@withTimeoutOrNull DeviceResponse.NeedsUser(reason)
                    filtered = true
                    continue
                }
                if (listHeading && located.isNotEmpty() && page == null)
                    return@withTimeoutOrNull DeviceResponse.NeedsUser("无法将当前页标题与唯一的订单列表对应，本轮未更新。")
                if (page == null) {
                    // 提交后可能继续读到旧弹层，直到结果页可读之前不再点击任何导航。
                    if (filtered) {
                        stage = "已提交筛选，等待来源提供与当前页标题同层的订单列表"
                        continue
                    }
                    if (pageNumber != 0 || previousPage != null) continue
                    val candidates = live.filter { it.label in navigation && !it.selected }.groupBy { it.label }
                        .mapNotNull { (name, group) ->
                            val entry = group.minByOrNull { it.bounds!!.width() * it.bounds.height().toLong() } ?: return@mapNotNull null
                            if (group.all { it.bounds!!.contains(entry.bounds!!) }) name to entry else null
                        }.toMap()
                    val names = candidates.keys.filter { it !in steps.take(step) }
                    stage = "路径 " + steps.take(step).joinToString(" → ") + "；可选入口 " + names.joinToString("、")
                    if (step >= 6) return@withTimeoutOrNull DeviceResponse.NeedsUser("未能从入口确认订单列表。")
                    if (names.isEmpty()) continue
                    val next = if (step < steps.size) steps[step] else if (learning)
                        names.singleOrNull { it == "全部订单" } ?: choose?.invoke(names) else null
                    if (next == null || next !in names) return@withTimeoutOrNull DeviceResponse.NeedsUser("无法确认下一步只读入口。$stage")
                    if (tap(candidates.getValue(next))) {
                        if (step == steps.size) steps += next
                        step++;delay(1100)
                    }
                    continue
                }
                if (step != steps.size) return@withTimeoutOrNull DeviceResponse.NeedsUser("入口路径与试查时不同。")
                learning = false
                stage = "已到订单集合，核对第 ${pageNumber + 1} 屏"
                val sig = page.markers.map { it.label }
                if (previousPage == sig) continue
                if (pageNumber < signatures.size && signatures[pageNumber] != sig)
                    return@withTimeoutOrNull DeviceResponse.NeedsUser("重新打开页面后订单列表有变化，请重试。")
                if (pageNumber == 0 && page.view.canGoBack) return@withTimeoutOrNull DeviceResponse.NeedsUser("无法确认从列表顶部开始。")
                if (pageNumber < pageIndex) {
                    if (!scroll(page.view)) return@withTimeoutOrNull DeviceResponse.NeedsUser("未能重新定位订单页。")
                    previousPage=sig;pageNumber++;delay(650);continue
                }
                if (pageNumber == signatures.size) signatures += sig
                if (current.size < page.markers.size) {
                    val item = page.markers[current.size]
                    if (!tap(item)) return@withTimeoutOrNull DeviceResponse.NeedsUser("未能打开指定的订单详情。")
                    detailMarker=item.label;delay(500);continue
                }
                // 列表预览、同屏隐藏层以及只露出时间的半条记录均不能证明完整性。
                val reported = page.reportedCounts.singleOrNull()
                val collectionCount = page.view.collectionRows?.takeIf { it >= 0 }
                if (page.reportedCounts.size > 1 || reported != null && collectionCount != null && reported != collectionCount)
                    return@withTimeoutOrNull DeviceResponse.NeedsUser("来源中的订单总笔数相互冲突。")
                val total = reported ?: collectionCount
                val numbers = page.rowNumbers.filterNotNull()
                val scope = filtered || window.orderRegion(page.view) { region ->
                    val owned = region.filter { it.selected && window.sameOrderSurface(it,page.view) }
                    located.none { other -> other !== page && region.any { it === other.view } } &&
                        owned.any { it.label in setOf("全部时间","不限时间","近3个月") } &&
                        owned.any { it.label in setOf("全部类型","外卖","全部订单类型") }
                } != null
                if (!scope || total != null && expectedRows?.let { it != total } == true ||
                    numbers.any { it < 0 || total != null && it >= total } ||
                    (numbers.size == current.size && numbers.zipWithNext().any { (a,b) -> b != a + 1 }))
                    return@withTimeoutOrNull DeviceResponse.NeedsUser("未能核对筛选范围及订单行号，未提交总额。")
                if (total != null) expectedRows = total
                everyRowNumbered = everyRowNumbered && numbers.size == current.size
                for ((index, order) in page.rowNumbers.zip(current)) {
                    if (index == null) continue
                    if (seenRows.put(index, order.identity)?.let { it != order.identity } == true)
                        return@withTimeoutOrNull DeviceResponse.NeedsUser("重新读取时订单位置发生变化。")
                }
                // 部分来源只在末页给出总笔数。无总笔数时，必须从第零行逐行连到明确的末行。
                val endCount = expectedRows ?: if (page.end && everyRowNumbered) numbers.lastOrNull()?.plus(1) else null
                if (page.end && (endCount == null || everyRowNumbered &&
                    (seenRows.size != endCount || seenRows.keys.any { it !in 0 until endCount } ||
                        seenRows.values.distinct().size != endCount)))
                    return@withTimeoutOrNull DeviceResponse.NeedsUser("没有足够的总笔数或连续行号证明已覆盖全部订单。")
                val reason = ledger.accept(current, pageIndex == 0 && !page.view.canGoBack, page.end)
                if (reason != null) return@withTimeoutOrNull DeviceResponse.NeedsUser(reason)
                val summary = ledger.result()
                if (summary != null) {
                    if (ledger.observedCount != endCount) return@withTimeoutOrNull DeviceResponse.NeedsUser("逐单数量与来源总笔数不一致。")
                    digest = summary.digest
                    return@withTimeoutOrNull DeviceResponse.Ready(DeviceSnapshot(source, MONTHLY_ORDERS,
                        mapOf("orders" to summary.count.toString(),"paid" to summary.total,
                            "period" to "${ledger.since.format(formatter)} 至 ${ledger.until.format(formatter)}"),
                        "本机逐单核验", evidenceDigest=summary.digest))
                }
                if (pageIndex >= 40 || !page.view.canGoForward || !scroll(page.view))
                    return@withTimeoutOrNull DeviceResponse.NeedsUser("没有读到可核对的列表末尾。")
                current.clear();previousPage=sig;pageIndex++;pageNumber++;delay(650)
            }
            DeviceResponse.NeedsUser("卡坞离开前台或助手断开。")
        }
        return trial ?: DeviceResponse.NeedsUser("多步试查未取得完整证据。$stage")
    }

    /** 重叠全屏页面以最近公共祖先处各分支的绘制次序裁决，无法确定覆盖关系时停止。 */
    private fun activeOrderPage(window: DetailWindow, pages: List<WindowPage>, previous: Rect?): WindowPage? {
        val front = window.foregroundOrderNode(pages.map { it.view }) ?: return null
        val page = pages.single { it.view === front }
        val frontBox = checkNotNull(front.bounds)
        var ancestor = window.nodes.indexOfFirst { it === front }
        while (ancestor >= 0) {
            val branch = window.nodes[ancestor]
            val over = window.nodes.withIndex().filter { (_,n) -> n.parent == branch.parent && n !== branch &&
                n.drawingOrder > branch.drawingOrder && n.bounds?.contains(frontBox) == true }
            for ((at, _) in over) {
                val below = mutableSetOf(at)
                for (i in at until window.nodes.size) {
                    val n = window.nodes[i]
                    if (i != at && n.parent !in below) break
                    below += i
                    if (n.visible && (n.label in orderListTitles || n.label in orderPanelTitles)) return null
                }
            }
            ancestor = branch.parent
        }
        val titles = window.nodes.filter { it.visible && it.bounds?.isEmpty == false && it.label in orderListTitles }
        val title = window.uniqueOrderNode(titles) ?: return null
        val bar = title.bounds ?: return null
        if (bar.bottom > frontBox.top || bar.centerX() !in frontBox.left..frontBox.right) return null
        if (window.ownsResultToolbar(title,front,pages.map { it.view },previous)) return page
        if (!window.sameOrderSurface(title,front)) return null
        if (window.orderRegion(title) { region ->
            region.any { it === front } && region.none { it.label in orderPanelTitles } &&
                pages.none { other -> other !== page && region.any { it === other.view } }
        } == null) return null
        return page
    }

    /** 只处理每个完整记录都有一个独立时间行的集合，嵌套或缺字段的布局不能据此发布合计。 */
    private fun listWindows(window: DetailWindow): List<WindowPage> {
        val nodes = window.nodes
        return nodes.mapIndexedNotNull { index, node ->
            if (!node.visible || node.bounds?.isEmpty != false || !(node.scrollable || node.role?.endsWith("ScrollView")==true ||
                    node.role?.endsWith("RecyclerView")==true)) return@mapIndexedNotNull null
            val descendants = mutableSetOf(index)
            val contents = nodes.mapIndexedNotNull { i,n ->
                if (n.parent !in descendants) return@mapIndexedNotNull null
                descendants.add(i)
                n.takeIf { it.visible && it.bounds?.isEmpty == false && node.bounds.contains(it.bounds) }
            }
            val markers = contents.filter { placed.matches(it.label) }
            // 显式末尾之外，还要求每条完整记录的状态一一配对。未证实的布局不产生完整账本。
            val states = contents.count { it.label in complete || it.label in excluded }
            val end = !node.canGoForward && states == markers.size && contents.any { it.label in endLabels }
            val positions = markers.map { marker ->
                var parent = marker.parent
                var row: Int? = marker.rowIndex
                while (row == null && parent > index) {
                    row = nodes[parent].rowIndex
                    parent = nodes[parent].parent
                }
                row
            }
            val totals = contents.mapNotNull { n ->
                Regex("""(?:共|全部订单[：:])\s*(\d{1,6})\s*[笔条]订单""").matchEntire(n.label)?.groupValues?.get(1)?.toInt()
            }.distinct()
            val empty = markers.isEmpty() && !node.canGoBack && !node.canGoForward &&
                (node.collectionRows == 0 || totals == listOf(0)) && contents.any { it.label in setOf("暂无订单","暂无符合条件的订单","没有符合条件的订单") }
            if (markers.isEmpty() && !empty) return@mapIndexedNotNull null
            WindowPage(node,markers,end || empty,positions,totals)
        }
    }
}
