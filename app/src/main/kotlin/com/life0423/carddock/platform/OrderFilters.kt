package com.life0423.carddock.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal const val FILTER_APPEARANCE_REQUIRED =
    "来源没有标明筛选项是否选中。可以允许本次试查在本机比对筛选面板的按钮外观。"

/** 重置筛选后逐项对照，再从结果页重新打开同一面板核验。像素只在本次用户同意且面板可辨认时采样。 */
internal suspend fun applyOrderFilters(
    observe: () -> DetailWindow?, tap: (DetailNode) -> Boolean,
    sample: (suspend (List<DetailNode>) -> List<Int>?)?, panelVisible: (Boolean) -> Unit
): String? {
    val ranges = listOf("近3个月","近6个月","全部时间","不限时间")
    val types = listOf("外卖","餐饮外卖")
    fun visible() = observe()?.takeUnless { it.password }?.nodes.orEmpty()
        .filter { it.visible && it.bounds?.isEmpty == false }
    fun List<DetailNode>.unique(name: String): DetailNode? {
        val nodes = filter { it.label == name }
        val inner = nodes.minByOrNull { it.bounds!!.width().toLong() * it.bounds.height() } ?: return null
        return inner.takeIf { nodes.all { n -> n.bounds!!.contains(inner.bounds!!) } }
    }
    suspend fun panel(): List<DetailNode>? = withTimeoutOrNull(4000) {
        var nodes = visible()
        while (nodes.none { it.label in ranges } || nodes.none { it.label in types } || nodes.unique("确认") == null) {
            delay(200)
            nodes = visible()
        }
        nodes
    }
    suspend fun press(node: DetailNode?): Boolean {
        val label = node?.label ?: return false
        // 弹层滑入时首次位置尚未稳定。等待连续两帧同位，成功分派后不重复点击。
        var prior = node.bounds
        repeat(6) {
            delay(180)
            val current = visible().unique(label)
            if (current != null && current.bounds == prior && tap(current)) {
                delay(550)
                return true
            }
            prior = current?.bounds
        }
        return false
    }
    val unavailable = "未能核对订单筛选面板。"
    if (!press(visible().unique("筛选"))) return "未能打开唯一可见的订单筛选入口。"
    val initial = panel() ?: return "筛选面板未同时显示时间范围、外卖和确认按钮。"
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
    if (!press(visible().unique("确认")) || !press(visible().unique("筛选"))) return "未能重新打开刚才确认的筛选面板。"
    val restored = pair() ?: return unavailable
    panelVisible(sample != null)
    val retained = semantic && marked(restored).all { it } || visual && sample?.invoke(restored) == both
    panelVisible(false)
    if (!retained || !press(visible().unique("确认"))) return "重新打开后未能确认筛选仍然生效。"
    return null
}
