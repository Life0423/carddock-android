package com.life0423.carddock.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal const val FILTER_APPEARANCE_REQUIRED =
    "来源没有标明筛选项是否选中。可以允许本次试查在本机比对筛选面板的按钮外观。"

/** 重置筛选后逐项对照，再从结果页重新打开同一面板核验。像素只在本次用户同意且面板可辨认时采样。 */
internal suspend fun applyOrderFilters(
    observe: () -> DetailWindow?, tap: (DetailNode) -> Boolean,
    sample: (suspend (List<DetailNode>) -> List<Int>?)?, panelVisible: (Boolean) -> Unit,
    resultFilterButton: (DetailWindow) -> DetailNode?
): String? {
    val ranges = listOf("近3个月","近6个月","全部时间","不限时间")
    val types = listOf("外卖","餐饮外卖")
    var snapshot = DetailWindow(null, emptyList(), false)
    fun visible(): List<DetailNode> {
        snapshot = observe()?.takeUnless { it.password } ?: DetailWindow(null, emptyList(), false)
        return snapshot.nodes.filter { it.visible && it.bounds?.isEmpty == false }
    }
    fun List<DetailNode>.unique(name: String) = snapshot.uniqueOrderNode(filter { it.label == name })
    fun toolbarFilter(all: List<DetailNode>): DetailNode? {
        if (all.any { it.label in orderPanelTitles }) return null
        val title = snapshot.uniqueOrderNode(all.filter { it.label in orderListTitles }) ?: return null
        val button = all.unique("筛选") ?: return null
        if (!snapshot.sameOrderSurface(title,button)) return null
        return button.takeIf {
            snapshot.orderRegion(title) { region ->
                region.any { n -> n === button } && region.none { n -> n.label.startsWith("下单时间：") }
            } != null
        }
    }
    fun panelFields(all: List<DetailNode>): List<DetailNode> {
        val heading = snapshot.foregroundOrderNode(all.filter { it.label in orderPanelTitles }) ?: return emptyList()
        val owner = snapshot.orderRegion(heading) { region ->
            region.count { it.label in orderPanelTitles } == 1 && region.none {
                it.label.startsWith("下单时间：") || it.label in setOf("搜索订单","搜索我的订单")
            } && region.any { it.label in ranges } && region.any { it.label in types } &&
                region.unique("确认") != null && (region.unique("清空") != null || region.unique("重置") != null)
        } ?: return emptyList()
        val body = owner.filter { node -> node === heading || snapshot.sameOrderSurface(heading,node) }
        val confirm = owner.unique("确认") ?: return emptyList()
        val reset = owner.unique("清空") ?: owner.unique("重置") ?: return emptyList()
        if (confirm in body && reset in body) return body
        // 部分表单用固定底栏覆盖可滚动正文的底部。只接受同一底栏内成对的重置与确认。
        val headingPath = snapshot.orderPath(heading)
        val actionPath = snapshot.orderPath(confirm)
        val split = headingPath.zip(actionPath).indexOfFirst { (a,b) -> a != b }
        if (split < 1 || actionPath[split] !in snapshot.orderPath(reset)) return emptyList()
        val contentBranch = snapshot.nodes[headingPath[split]]
        val footerBranch = snapshot.nodes[actionPath[split]]
        if (contentBranch.drawingOrder <= 0 || footerBranch.drawingOrder <= contentBranch.drawingOrder) return emptyList()
        val content = contentBranch.bounds ?: return emptyList()
        val footer = footerBranch.bounds ?: return emptyList()
        val options = body.filter { it.label in ranges || it.label in types }
        if (options.none { it.label in ranges } || options.none { it.label in types } ||
            !content.contains(footer) || content.bottom != footer.bottom ||
            !footer.contains(confirm.bounds!!) || !footer.contains(reset.bounds!!) ||
            footer.top < heading.bounds!!.bottom || options.any { it.bounds!!.bottom > footer.top }) return emptyList()
        return body + listOf(confirm,reset).filter { it !in body }
    }
    suspend fun panel(): List<DetailNode>? = withTimeoutOrNull(4000) {
        var nodes = panelFields(visible())
        while (nodes.none { it.label in ranges } || nodes.none { it.label in types } || nodes.unique("确认") == null) {
            delay(200)
            nodes = panelFields(visible())
        }
        nodes
    }
    suspend fun press(node: DetailNode?, initialFilter: Boolean = false): Boolean {
        val label = node?.label ?: return false
        // 弹层滑入时首次位置尚未稳定。等待连续两帧同位，成功分派后不重复点击。
        var prior = node.bounds
        repeat(6) {
            delay(180)
            val all = visible()
            val current = when {
                initialFilter -> toolbarFilter(all)
                label == "筛选" -> resultFilterButton(snapshot)
                else -> panelFields(all).unique(label)
            }
            if (current != null && current.bounds == prior && tap(current)) {
                delay(550)
                return true
            }
            prior = current?.bounds
        }
        return false
    }
    val unavailable = "未能核对订单筛选面板。"
    if (!press(toolbarFilter(visible()), initialFilter = true)) return "未能打开唯一可见的订单筛选入口。"
    val initial = panel() ?: run {
        val nodes = visible()
        val unchanged = nodes.any { it.label in setOf("搜索订单","搜索我的订单","全部订单","订单列表") } &&
            nodes.none { it.label in ranges || it.label in setOf("订单筛选","确认") }
        if (!unchanged || !press(toolbarFilter(nodes), initialFilter = true)) return "筛选面板未同时显示时间范围、外卖和确认按钮。"
        panel() ?: return "筛选面板再次打开后仍缺少时间范围、外卖或确认按钮。"
    }
    val period = ranges.firstOrNull { initial.unique(it) != null } ?: return unavailable
    val type = types.firstOrNull { initial.unique(it) != null } ?: return unavailable
    val reset = initial.unique("清空") ?: initial.unique("重置") ?: return "未能找到唯一的清空筛选按钮。"
    panelVisible(sample != null)
    if (!press(reset)) return "未能重置筛选条件。"
    val names = listOf(period, type)
    suspend fun pair(): List<DetailNode>? {
        val current = panel() ?: return null
        return names.map { current.unique(it) ?: return null }
    }
    val resetPair = pair() ?: return unavailable
    val base = sample?.invoke(resetPair)
    if (!press(resetPair.first())) return unavailable
    val timePair = pair() ?: return unavailable
    val timeOnly = sample?.invoke(timePair)
    if (!press(timePair.last())) return unavailable
    val selected = pair() ?: return unavailable
    val both = sample?.invoke(selected)
    fun marked(nodes: List<DetailNode>) = nodes.map { it.selected || it.checked }
    val semantic = marked(resetPair) == listOf(false,false) && marked(timePair) == listOf(true,false) &&
        marked(selected) == listOf(true,true)
    val visual = base != null && timeOnly != null && both != null &&
        base[0] != timeOnly[0] && base[1] == timeOnly[1] && timeOnly[0] == both[0] && timeOnly[1] != both[1]
    if (!semantic && !visual) return when {
        sample == null -> FILTER_APPEARANCE_REQUIRED
        base == null || timeOnly == null || both == null -> "本机未取得筛选面板的完整外观样本。"
        base[0] == timeOnly[0] -> "未确认时间范围的外观发生变化。"
        timeOnly[1] == both[1] -> "未确认订单类型的外观发生变化。"
        else -> "选择一项时另一项外观也发生变化，无法核对筛选。"
    }
    panelVisible(false)
    if (!press(visible().unique("确认"))) return "未能提交筛选条件。"
    // 新集合为独立页面。等待弹层消失且结果页的筛选入口出现，再复核刚提交的状态。
    var resultFilter: DetailNode? = null
    var nodes = emptyList<DetailNode>()
    // 读取完整树可能跨过局部超时。按完整观察次数等待，查询本身已有总时限。
    for (attempt in 1..8) {
        delay(180)
        nodes = visible()
        if (nodes.any { it.label in orderPanelTitles }) continue
        resultFilter = resultFilterButton(snapshot)
        if (resultFilter != null) break
    }
    if (resultFilter == null) {
        return when {
            nodes.any { it.label in orderPanelTitles } -> "已点击确认，页面读取服务仍读到筛选面板，本轮未更新。"
            nodes.none { it.label in orderListTitles } -> "已提交筛选，但来源暂未显示订单结果页标题。"
            nodes.none { it.label == "筛选" } -> "已提交筛选，但结果页没有可供复核的筛选入口。"
            else -> "已提交筛选，但结果页标题、筛选入口与列表无法唯一对应。"
        }
    }
    if (!press(resultFilter)) return "订单结果页已出现，但未能重新打开筛选。"
    val restored = pair() ?: return unavailable
    panelVisible(sample != null)
    val retained = semantic && marked(restored).all { it } || visual && sample?.invoke(restored) == both
    panelVisible(false)
    if (!retained || !press(visible().unique("确认"))) return "重新打开后未能确认筛选仍然生效。"
    return null
}
