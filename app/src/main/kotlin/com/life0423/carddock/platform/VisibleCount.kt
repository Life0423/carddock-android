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
            val labels = nodes.indices.filter { nodes[it].visible && nodes[it].label == subject && nodes[it].parent >= 0 }
            if (labels.size > 1) return@withTimeoutOrNull DeviceResponse.NeedsUser("页面里有多个同名分类，请指定更明确的页面。")
            val label = labels.singleOrNull() ?: continue
            val siblings = nodes.indices.filter { nodes[it].parent == nodes[label].parent && nodes[it].visible }
            val textSiblings = siblings.filter { nodes[it].label.isNotBlank() }
            val otherLabels = textSiblings.filter { it != label && !countText.matches(nodes[it].label) }
            val owned = if (otherLabels.isEmpty()) textSiblings else
                textSiblings.dropWhile { it != label }.drop(1).takeWhile { it !in otherLabels }
            val fields = owned.filter { it != label && countText.matches(nodes[it].label) }.map {
                CountField(CountRoute(page, subject, anchor(label), anchor(it)), nodes[it].label, it)
            }
            if (fields.isEmpty()) { waiting = "分类旁未读到可核验的数量。"; continue }
            val field = if (saved != null) fields.singleOrNull { it.route == saved } else {
                // 只发送本组节点的序号、控件大类和取值形态。实际文字、数字和包名都留在设备上。
                val shape = siblings.joinToString("\n") {
                    val type = when {
                        it == label -> "requested-label"
                        fields.any { candidate -> candidate.index == it } -> "quantity"
                        else -> "other"
                    }
                    val role = if (nodes[it].role == "android.widget.TextView") "text" else "view"
                    "$it sibling $role $type"
                }
                choose?.invoke(fields, shape)
            }
            if (field == null || fields.count { it.route == field.route } != 1)
                return@withTimeoutOrNull DeviceResponse.NeedsUser("无法唯一核对数量字段。")
            return@withTimeoutOrNull DeviceResponse.Ready(DeviceSnapshot(source, COUNT_TARGET, mapOf("quantity" to field.value), "本机页面核验"))
        }
        DeviceResponse.NeedsUser("卡坞离开前台或页面读取中断。")
    } ?: DeviceResponse.NeedsUser(waiting)
}

internal const val COUNT_TARGET = "visible-count"
