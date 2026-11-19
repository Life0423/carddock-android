package com.life0423.carddock.platform

import com.life0423.carddock.core.port.DeviceResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private val placedMinute = Regex("""下单时间[：:]\s*(\d{4}-\d{2}-\d{2} \d{2}:\d{2})""")

/** 只在筛选结果完整落于前景视口时返回唯一最新时间行。后方的旧订单页也在树中。 */
internal fun DetailWindow.latestTakeoutRow(): DetailNode? {
    val title = nodes.singleOrNull { it.visible && it.label == "搜索我的订单" } ?: return null
    val titleBox = title.bounds ?: return null
    var parent = title.parent
    while (parent >= 0) {
        val bar = nodes[parent].bounds ?: return null
        if (bar.left == 0 && bar.top <= titleBox.top && bar.width() >= 2 * titleBox.width()) {
            val list = nodes.withIndex().singleOrNull { (_, node) ->
                val box = node.bounds
                node.role == "android.widget.ScrollView" && box != null && box.width() == bar.width() &&
                    box.top in bar.bottom..(bar.bottom + titleBox.height())
            } ?: return null
            val viewport = list.value.bounds ?: return null
            if (list.value.scrollable) return null
            val content = nodes.withIndex().singleOrNull { it.value.parent == list.index } ?: return null
            val extent = content.value.bounds ?: return null
            if (!viewport.contains(extent) || extent.bottom >= viewport.bottom || extent.isEmpty) return null
            val descendants = mutableSetOf(content.index)
            val labels = nodes.mapIndexedNotNull { index, node ->
                if (node.parent !in descendants) null else {
                    descendants.add(index)
                    node.takeIf { it.visible && it.label.isNotBlank() }
                }
            }
            val rows = labels.filter { it.label.startsWith("下单时间") }
            // 美团把同一订单的状态与时间展开为兄弟节点，以内容区内整行的边界配对。
            val bands = nodes.filter { it.parent == content.index && it.visible }.mapNotNull { it.bounds }
                .filter { it.left == extent.left && it.right == extent.right && !it.isEmpty }
                .distinct().sortedBy { it.top }
            if (bands.isEmpty() || bands.zipWithNext().any { (a,b) -> a.bottom != b.top } ||
                bands.last().bottom != extent.bottom || rows.size != bands.size) return null
            // 顶部若有不属于任何已识别订单的文字，可能是另一种布局的较新订单。
            if (labels.any { node -> bands.count { node.bounds?.let(it::contains) == true } != 1 }) return null
            val dated = bands.map { band ->
                val inside = labels.filter { it.bounds?.let(band::contains) == true }
                if (inside.count { it.label == "已完成" } != 1) return null
                val time = inside.singleOrNull { it.label.startsWith("下单时间") } ?: return null
                val minute = placedMinute.matchEntire(time.label)?.groupValues?.get(1) ?: return null
                minute to time
            }
            val newest = dated.maxOf { it.first }
            return dated.singleOrNull { it.first == newest }?.second
        }
        parent = nodes[parent].parent
    }
    return null
}

/** 导航只在已登录的美团查询屏进行。两个筛选项的选中状态均在提交前复核。 */
internal class LatestTakeout(
    private val connected: () -> Boolean,
    private val observe: () -> DetailWindow?,
    private val tap: (DetailNode) -> Boolean,
    private val selection: (DetailNode) -> Boolean?,
    private val expand: () -> Boolean,
) {
    private suspend fun open(label: String, next: String): Boolean = withTimeoutOrNull(12_000) {
        var tapped = false
        var lastTap = 0L
        while (connected()) {
            val page = observe()
            if (page?.isLogin() == true) break
            val nodes = page?.nodes.orEmpty().filter { it.visible }
            fun one(text: String) = nodes.singleOrNull { it.label == text }
            val sheet = one("订单筛选") != null
            if (next.isNotEmpty() && tapped && nodes.any { it.label == next } && (label != "确认" || !sheet)) return@withTimeoutOrNull true
            val target = one(label)
            if (target != null && (label == "筛选" || sheet)) {
                val ready = when (label) {
                    "近1个月", "外卖" -> when (selection(target)) {
                        true -> return@withTimeoutOrNull true
                        false -> !tapped
                        null -> false
                    }
                    "确认" -> listOf("近1个月", "外卖").all { one(it)?.let(selection) == true }
                    else -> true
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (ready && now-lastTap > 2200 && tap(target)) {
                    tapped = true
                    lastTap = now
                }
            }
            delay(350)
        }
        false
    } == true

    suspend fun inspect(): DeviceResponse {
        for ((label, next) in listOf("筛选" to "订单筛选", "近1个月" to "", "外卖" to "", "确认" to "搜索我的订单")) {
            if (!open(label, next)) return DeviceResponse.NeedsUser("美团未确认「$label」，已保留上次结果。")
        }
        val row = withTimeoutOrNull(18_000) {
            var previous: DetailNode? = null
            while (connected()) {
                val page = observe()
                val current = page?.latestTakeoutRow()
                if (current != null && current.label == previous?.label && current.bounds == previous?.bounds) return@withTimeoutOrNull current
                previous = current
                delay(400)
            }
            null
        } ?: return DeviceResponse.NeedsUser("无法确认近月外卖列表完整且最新时间唯一，已保留上次结果。")
        if (!connected() || !tap(row)) return DeviceResponse.NeedsUser("未能打开最新订单详情。")
        val response = MeituanGateway(connected = connected, observe = observe, expand = expand,
            missing = "最新订单详情尚未核验到完成状态、订单号码和下单时间。").inspect()
        if (response !is DeviceResponse.Ready) return response
        val expected = placedMinute.matchEntire(row.label)!!.groupValues[1]
        if (response.snapshot.fields["orderTime"]?.substringBeforeLast(':') != expected)
            return DeviceResponse.Failed("列表与详情的下单时间不一致，已保留上次结果。")
        return DeviceResponse.Ready(response.snapshot.copy(targetKey = MEITUAN_LATEST, sourceLabel = "美团 · 最近外卖"))
    }
}
