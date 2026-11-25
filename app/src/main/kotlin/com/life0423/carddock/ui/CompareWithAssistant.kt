package com.life0423.carddock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.ai.WorkspaceModelException
import com.life0423.carddock.ai.comparisonMetrics
import com.life0423.carddock.ai.decodeMetricSelection
import com.life0423.carddock.ai.modelText
import com.life0423.carddock.ai.requestWorkspaceModel
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.ui.theme.DockColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 模型只挑选用户任务所需的指标。对比读数、来源与时间都取各卡的上一次核验结果。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompareWithAssistant(members: List<CardRecord>, initiallyExpanded: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var goal by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val metrics = comparisonMetrics(members)
    val visible = selected.mapNotNull { id -> metrics.firstOrNull { it.id == id } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("按任务对比", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton({ expanded = !expanded }, Modifier.testTag("compare_with_assistant")) {
                Text(if (expanded) "收起" else "选择指标")
            }
        }
        if (expanded) {
            OutlinedTextField(
                goal, { goal = it.take(800); message = null },
                modifier = Modifier.fillMaxWidth().testTag("compare_goal"),
                label = { Text("你想做什么决定？") },
                placeholder = { Text("选一本今晚能读完的书") },
                enabled = !busy, maxLines = 3,
            )
            if (metrics.isEmpty()) Text(
                "先核验卡片，获得可对比的结果。",
                color = DockColors.Muted, style = MaterialTheme.typography.bodySmall,
            ) else {
                Button(
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            val input = buildJsonObject {
                                put("task", modelText(goal))
                                putJsonArray("metrics") {
                                    metrics.forEachIndexed { index, metric ->
                                        addJsonObject {
                                            put("id", index + 1)
                                            put("label", modelText(metric.label).take(90))
                                        }
                                    }
                                }
                            }
                            try {
                                val reply = requestWorkspaceModel(context, """
                                    为用户的选择目标推荐有用指标，按重要程度排序，最多八项。
                                    只使用输入列出的指标序号，只输出 JSON，例如 {"metrics":[2,1]}。
                                    没有匹配项时返回空数组。
                                """.trimIndent(), input.toString())
                                val next = decodeMetricSelection(reply, metrics)
                                selected = next
                                if (next.isEmpty()) message = "当前卡片缺少与任务相符的指标。"
                            } catch (error: WorkspaceModelException) {
                                message = error.message
                            } catch (_: IllegalArgumentException) {
                                message = "模型回复格式无效，请重试。"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && goal.isNotBlank(),
                    modifier = Modifier.testTag("compare_suggest"),
                ) { Text(if (busy) "正在推荐" else "推荐指标") }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DockColors.Error) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    metrics.forEach { metric ->
                        val duplicateLabel = metrics.count { it.label == metric.label } > 1
                        FilterChip(
                            selected = metric.id in selected,
                            onClick = {
                                selected = if (metric.id in selected) selected - metric.id else selected + metric.id
                            },
                            enabled = !busy,
                            label = { Text(if (duplicateLabel) "${metric.label} · ${metric.key}" else metric.label) },
                        )
                    }
                }
                if (visible.isNotEmpty()) {
                    members.forEach { card ->
                        Surface(shape = RoundedCornerShape(14.dp), color = DockColors.Surface) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(card.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                val snapshot = card.snapshot
                                if (snapshot != null) Text(
                                    "${snapshot.sourceLabel} · ${formatTimestamp(snapshot.fetchedAt)}",
                                    style = MaterialTheme.typography.labelSmall, color = DockColors.Muted,
                                )
                                visible.forEach { metric ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(metric.label, Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
                                        Text(metric.value(card) ?: "未取得", Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
