package com.life0423.carddock.platform

import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.rule.CountRoute
import com.life0423.carddock.core.rule.FieldAnchor
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private val countText = Regex("""(?:0|[1-9]\d{0,8}|[1-9]\d{0,2}(?:,\d{3}){1,2})\s*(?:项|个|条|件|份|张|次)?""")

/** 一项实测字段，用当轮节点序号供模型选择；保存时只带结构锚点。 */
internal data class CountField(val route: CountRoute, val value: String, val index: Int)

/** 从用户指明的页签核对同一容器里的标签与数量。只允许点按有导航语义的同名页签。 */
internal suspend fun readVisibleCount(source: String, page: String?, subject: String, saved: CountRoute?,
    connected: () -> Boolean, observe: () -> DetailWindow?, tap: (DetailNode, android.graphics.Rect) -> Boolean,
    choose: ((List<CountField>, String) -> CountField?)?,
): DeviceResponse {
    var switched = false
    var waiting = "没有读到来源页面。"
    return withTimeoutOrNull(35000) {
        while (connected()) {
            delay(250)
            val window = observe() ?: continue
            if (window.password) return@withTimeoutOrNull DeviceResponse.NeedsUser("请先在来源应用完成登录。")
            val nodes = window.nodes
            fun lineage(index: Int) = generateSequence(index) { nodes[it].parent.takeIf { parent -> parent >= 0 } }
            if (page != null) {
                // 同组同类标签里须有唯一选中项。内容区的 navigation_host 不能授权点击普通文本。
                waiting = "未找到可核验的同名导航页签。"
                val tabs = nodes.indices.filter { i ->
                    val id = nodes[i].id.orEmpty().substringAfterLast('/').lowercase()
                    nodes[i].visible && nodes[i].role == "android.widget.TextView" && nodes[i].label == page &&
                        (("navigation" in id && "item" in id) || id.startsWith("tab_") || id.endsWith("_tab"))
                }
                val tab = tabs.singleOrNull() ?: continue
                fun group(index: Int) = lineage(index).drop(1).firstOrNull {
                    val id = nodes[it].id.orEmpty().substringAfterLast('/').lowercase()
                    ("navigation" in id || "tab" in id) && "item" !in id && "label" !in id
                }
                val bar = group(tab) ?: continue
                val peers = nodes.indices.filter { nodes[it].visible && nodes[it].id == nodes[tab].id && group(it) == bar }
                if (peers.map { nodes[it].label }.distinct().size < 2) continue
                waiting = "无法确认当前选中的导航页签。"
                val selected = peers.singleOrNull { i -> lineage(i).takeWhile { it != bar }.any { nodes[it].selected } } ?: continue
                if (tab != selected) {
                    waiting = if (switched) "已点选页签，但来源未显示为已选中。" else "未能点选指定的导航项。"
                    val cell = lineage(tab).takeWhile { it != bar }.lastOrNull { ancestor ->
                        peers.none { it != tab && ancestor in lineage(it) }
                    }?.let { nodes[it].bounds } ?: continue
                    if (!switched) switched = tap(nodes[tab], cell)
                    continue
                }
            }
            fun anchor(i: Int) = FieldAnchor(nodes[i].role.orEmpty(), nodes.getOrNull(nodes[i].parent)?.role.orEmpty(), nodes[i].id)
            waiting = "页面中未找到指定分类。"
            val visible = nodes.indices.filter { nodes[it].visible && nodes[it].parent >= 0 }
            val exact = visible.filter { nodes[it].label == subject }
            val fields = mutableListOf<CountField>()
            val shape = StringBuilder()
            // 同名页签或标题未必是数据行。先核对行内字段，再用完整锚点判定是否唯一。
            fun collectFields(labels: List<Int>, match: String) {
                for (label in labels) {
                    val siblings = visible.filter { nodes[it].parent == nodes[label].parent && nodes[it].label.isNotBlank() }
                    val otherLabels = siblings.filter { it != label && !countText.matches(nodes[it].label) }
                    val owned = if (otherLabels.isEmpty()) siblings else
                        siblings.dropWhile { it != label }.drop(1).takeWhile { it !in otherLabels }
                    val quantities = owned.filter { it != label && countText.matches(nodes[it].label) }
                    if (quantities.isEmpty()) continue
                    shape.appendLine("$label $match")
                    for (i in quantities) {
                        fields += CountField(CountRoute(page, nodes[label].label, anchor(label), anchor(i)), nodes[i].label, i)
                        shape.appendLine("$i quantity grouped-with=$label")
                    }
                }
            }
            collectFields(exact, "requested-label")
            if (saved == null && fields.isEmpty()) {
                collectFields(visible.filter { nodes[it].role == "android.widget.TextView" &&
                    nodes[it].label != subject && nodes[it].label.endsWith(subject) }, "partial-label")
            }
            if (fields.isEmpty()) { waiting = "未读到与指定分类对应的数量。"; continue }
            val field = if (saved != null) fields.singleOrNull { it.route == saved } else choose?.invoke(fields, shape.toString())
            if (field == null || fields.count { it.route == field.route } != 1)
                return@withTimeoutOrNull DeviceResponse.NeedsUser("无法唯一核对数量字段。")
            return@withTimeoutOrNull DeviceResponse.Ready(DeviceSnapshot(source, COUNT_TARGET, mapOf("quantity" to field.value), "本机页面核验"))
        }
        DeviceResponse.NeedsUser("卡坞离开前台或页面读取中断。")
    } ?: DeviceResponse.NeedsUser(waiting)
}

internal const val COUNT_TARGET = "visible-count"
