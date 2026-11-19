package com.life0423.carddock.platform

import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import com.life0423.carddock.core.rule.FieldAnchor
import com.life0423.carddock.core.rule.ReminderRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.LocalTime

private val clockText = Regex("""(?<!\d)(?:[01]?\d|2[0-3]):[0-5]\d(?!\d)""")
private val dayHeader = Regex("""\d{1,2}/\d{1,2}""")
private val minutesBefore = Regex("""(\d{1,3})\s*分钟前""")
private val leadList = Regex("""\d{1,3}\s*分钟前(?:[、，,]\s*\d{1,3}\s*分钟前)*""")
private data class AgendaItem(val name: String, val start: String)

/** 按今日分组逐条打开详情。只有在看见该组末尾、且每个定时项都已核对后才提交结果。 */
internal class UpcomingReminder(
    private val source: String, private var anchors: ReminderRoute?,
    private val learn: ((List<String>) -> List<Int>)?, private val learned: (ReminderRoute) -> Unit,
) {
    private var expected: List<AgendaItem>? = null
    private val checked = mutableMapOf<AgendaItem, List<LocalDateTime>>()
    val hasMore: Boolean get() = expected?.let { checked.size < it.size } == true

    suspend fun readOne(connected: () -> Boolean, observe: () -> DetailWindow?, tap: (DetailNode) -> Boolean): DeviceResponse {
        var pending: AgendaItem? = null
        val today = LocalDateTime.now().toLocalDate()
        var openedList = false
        return withTimeoutOrNull(60000) {
            while (connected()) {
                delay(250)
                val page = observe() ?: continue
                if (page.password) return@withTimeoutOrNull DeviceResponse.NeedsUser("请先在来源应用登录。")
                val nodes = page.nodes.filter { it.visible && it.label.isNotBlank() }
                val item = pending
                if (item == null) {
                    val rowContainers = nodes.filter { it.label.split(',', '，').size >= 3 && it.bounds != null }
                    val headers = nodes.filter { node -> node.role == "android.widget.TextView" && dayHeader.matches(node.label) &&
                        rowContainers.none { row -> node.bounds?.let { row.bounds!!.contains(it) } == true } }
                    val start = headers.firstOrNull {
                        val (month, day) = it.label.split('/')
                        month.toInt() == today.monthValue && day.toInt() == today.dayOfMonth
                    }
                    if (start == null) {
                        if (headers.isNotEmpty()) return@withTimeoutOrNull DeviceResponse.NeedsUser("当前日程列表没有可核对的今日分组。")
                        val nav = nodes.firstOrNull { it.role == "android.widget.TextView" && it.label in setOf("日程","提醒列表","即将到来") }
                        if (!openedList && nav != null) openedList = tap(nav)
                        continue
                    }
                    val next = headers.dropWhile { it != start }.getOrNull(1)
                    if (next == null && page.nodes.any { it.visible && it.scrollable })
                        return@withTimeoutOrNull DeviceResponse.NeedsUser("今日分组超出当前页面，无法确认已读全。")
                    val group = nodes.subList(nodes.indexOf(start) + 1, next?.let(nodes::indexOf) ?: nodes.size)
                    val rows = rowContainers.filter { it in group }
                    val timeLabels = group.filter { it.role == "android.widget.TextView" && clockText.containsMatchIn(it.label) }.toMutableSet()
                    val items = mutableMapOf<AgendaItem, DetailNode>()
                    for (row in rows) {
                        val parts = row.label.split(',', '，')
                        val children = group.filter { it.role == "android.widget.TextView" && it.label in parts &&
                            it.bounds?.let { bounds -> row.bounds!!.contains(bounds) } == true }
                        val time = children.singleOrNull { clockText.containsMatchIn(it.label) }
                        val name = children.singleOrNull { it != time }
                        val clock = time?.let { clockText.find(it.label)?.value }
                        if (name == null || clock == null || !timeLabels.remove(time) || items.put(AgendaItem(name.label,clock),name) != null)
                            return@withTimeoutOrNull DeviceResponse.NeedsUser("今日有无法区分的日程，尚不能确认下一次通知。")
                    }
                    if (timeLabels.isNotEmpty()) return@withTimeoutOrNull DeviceResponse.NeedsUser("有可见时间未能归入日程，无法确定下一次通知。")
                    if (items.isEmpty()) return@withTimeoutOrNull DeviceResponse.NeedsUser("今日没有可核验的定时日程。")
                    if (expected == null) expected = items.keys.toList()
                    if (expected != items.keys.toList()) return@withTimeoutOrNull DeviceResponse.NeedsUser("试查期间列表发生变化，请重试。")
                    val remaining = items.entries.first { it.key !in checked }
                    if (tap(remaining.value)) pending = remaining.key
                    continue
                }
                if (nodes.none { it.label == item.name } || nodes.none { it.label == "提醒" }) continue
                fun anchor(node: DetailNode) = FieldAnchor(node.role.orEmpty(), page.nodes.getOrNull(node.parent)?.role.orEmpty(), node.id)
                val labels = nodes.filter { it.label == "提醒" }
                val noAlarm = nodes.any { it.label in setOf("无","无提醒","不提醒") && labels.any { label -> label.parent == it.parent } }
                if (noAlarm) {
                    checked[item] = emptyList()
                } else {
                    // 先从本机列表/详情取相互印证的语义范围，字段由模型选择。回放只使用学到的锚点。
                    val titles = nodes.filter { it.label == item.name }
                    val periods = nodes.filter { "今天" in it.label && clockText.find(it.label)?.value == item.start }
                    val leads = nodes.filter { leadList.matches(it.label) && labels.any { label -> label.parent == it.parent } }
                    if (periods.isEmpty() || leads.isEmpty()) continue
                    val saved = anchors
                    val lead: DetailNode
                    if (saved == null) {
                        val structure = page.nodes.mapIndexed { index, node ->
                            val shape = when {
                                !node.visible || node.label.isBlank() -> "container"
                                node.label in setOf("日程详情","提醒","类型","提醒方式") -> node.label
                                node in titles -> "text(same-as-selected-list-item)"
                                leadList.matches(node.label) -> "duration-before"
                                clockText.findAll(node.label).count() == 2 -> "date-and-time-range"
                                else -> "text"
                            }
                            "$index parent=${node.parent} ${node.role?.substringAfterLast('.')} $shape"
                        }
                        val ids = learn?.invoke(structure).orEmpty()
                        val fields = ids.mapNotNull { page.nodes.getOrNull(it) }
                        if (ids.size != 3 || fields.size != 3 || fields[0] !in titles || fields[1] !in periods || fields[2] !in leads)
                            return@withTimeoutOrNull DeviceResponse.NeedsUser("模型选择的字段与本机详情证据不一致。")
                        lead = fields[2]
                        val selected = ReminderRoute(anchor(fields[0]), anchor(fields[1]), anchor(lead))
                        anchors = selected
                        learned(selected)
                    } else {
                        lead = leads.singleOrNull { anchor(it) == saved.advance }
                            ?: return@withTimeoutOrNull DeviceResponse.NeedsUser("通知设置结构有变，请重新试运行模板。")
                        if (titles.count { anchor(it) == saved.title } != 1 || periods.count { anchor(it) == saved.start } != 1)
                            return@withTimeoutOrNull DeviceResponse.NeedsUser("名称或时间结构有变，请重新试运行模板。")
                    }
                    val event = today.atTime(LocalTime.parse(item.start.padStart(5,'0')))
                    checked[item] = minutesBefore.findAll(lead.label).map { event.minusMinutes(it.groupValues[1].toLong()) }.distinct().toList()
                }
                // 每个详情在其专属后台屏内结束。该机型的“返回”按钮可能向主屏发送系统返回键。
                val now = LocalDateTime.now()
                val upcoming = checked.flatMap { (key, times) ->
                    times.filter { it > now }.map { key to it }
                }.sortedBy { it.second }
                if (hasMore) return@withTimeoutOrNull DeviceResponse.Ready(DeviceSnapshot(source,"visible-reminder",
                    mapOf("名称" to item.name), "本机页面核验"))
                val first = upcoming.firstOrNull() ?: return@withTimeoutOrNull DeviceResponse.NeedsUser("今日已核对日程没有后续通知。")
                if (upcoming.getOrNull(1)?.second == first.second) return@withTimeoutOrNull DeviceResponse.NeedsUser("多条日程的通知时间相同，无法唯一确定。")
                return@withTimeoutOrNull DeviceResponse.Ready(DeviceSnapshot(source,"visible-reminder",
                    mapOf("名称" to first.first.name, "通知时间" to first.second.toString().replace('T',' ')), "本机页面核验"))
            }
            DeviceResponse.NeedsUser("后台试查中断，请回到卡坞后重试。")
        } ?: DeviceResponse.NeedsUser("未能读全今日列表并核对详情，请重试。")
    }
}
