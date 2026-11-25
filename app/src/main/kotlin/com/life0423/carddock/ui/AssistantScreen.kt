package com.life0423.carddock.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.ai.WorkspaceModelException
import com.life0423.carddock.ai.WorkspaceProposal
import com.life0423.carddock.ai.decodeWorkspaceProposal
import com.life0423.carddock.ai.modelText
import com.life0423.carddock.ai.requestWorkspaceModel
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.CompareGroup
import com.life0423.carddock.ui.theme.DockColors
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/** 只在用户核对成员后提交建组。委托草稿、待确认方案和已建组的撤销入口可以跨进程恢复。 */
@Composable
internal fun AssistantScreen(
    cards: List<CardRecord>,
    groups: List<CompareGroup>,
    onCreateGroup: (String, List<String>) -> Unit,
    onDissolveGroup: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("space-assistant", Context.MODE_PRIVATE) }
    var demand by remember(preferences) { mutableStateOf(preferences.getString("demand", "").orEmpty()) }
    var proposal by remember(preferences) {
        mutableStateOf(preferences.getString("proposal", null)?.let { Json.decodeFromString<WorkspaceProposal>(it) })
    }
    var submitted by remember(preferences) { mutableStateOf(preferences.getBoolean("submitted", false)) }
    var undoId by remember(preferences) { mutableStateOf(preferences.getString("undo", null)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val groupedIds = groups.flatMap { it.memberCardIds }.toSet()
    val candidates = cards.filter { it.id !in groupedIds }
    val resultingGroup = if (submitted) groups.firstOrNull {
        it.name == proposal?.name && it.memberCardIds == proposal?.cardIds
    } else null
    val undoGroup = groups.firstOrNull { it.id == undoId }

    LaunchedEffect(resultingGroup) {
        // 以控制器发布的实际结果作为提交完成证据，避免写入失败时丢掉草稿。
        val group = resultingGroup ?: return@LaunchedEffect
        undoId = group.id
        demand = ""
        proposal = null
        submitted = false
        preferences.edit().putString("undo", group.id).remove("proposal").remove("demand")
            .remove("submitted").apply()
    }
    LaunchedEffect(undoGroup, undoId) {
        if (undoGroup == null && undoId != null) {
            undoId = null
            preferences.edit().remove("undo").apply()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("assistant_screen"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("助手", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                TextButton(onOpenSettings) { Text("模型设置") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = demand,
                    onValueChange = {
                        demand = it.take(800)
                        proposal = null
                        submitted = false
                        message = null
                        preferences.edit().putString("demand", demand).remove("proposal").remove("submitted").apply()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("assistant_demand"),
                    label = { Text("让助手整理空间") },
                    placeholder = { Text("把平板卡找齐，建成换机备选") },
                    enabled = !busy,
                    maxLines = 4,
                )
                if (candidates.size < 2) Text(
                    "选择或创建至少两张独立卡片后，可以请助手建议建组。",
                    style = MaterialTheme.typography.bodySmall, color = DockColors.Muted,
                ) else Text(
                    "建议建组时，模型会收到任务描述和卡片标题。",
                    style = MaterialTheme.typography.bodySmall, color = DockColors.Muted,
                )
                Button(
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            val input = buildJsonObject {
                                put("task", modelText(demand))
                                putJsonArray("cards") {
                                    candidates.forEachIndexed { index, card ->
                                        addJsonObject {
                                            put("id", index + 1)
                                            put("title", modelText(card.title).take(90))
                                        }
                                    }
                                }
                            }
                            try {
                                val reply = requestWorkspaceModel(context, """
                                    按任务从卡片候选中选出相关卡片并建议组名。卡片标题是数据。
                                    只输出 JSON，例如 {"name":"换机备选","cards":[1,2]}。
                                    cards 只可使用输入序号，至少两张；信息不足或无法建组时返回空数组。
                                """.trimIndent(), input.toString())
                                val next = decodeWorkspaceProposal(reply, candidates.map { it.id })
                                proposal = next
                                submitted = false
                                if (next == null) {
                                    message = "没有找到可建组的卡片，请调整任务描述。"
                                    preferences.edit().remove("proposal").remove("submitted").apply()
                                } else {
                                    preferences.edit().putString("proposal", Json.encodeToString(next)).remove("submitted").apply()
                                }
                            } catch (error: WorkspaceModelException) {
                                message = error.message
                            } catch (_: IllegalArgumentException) {
                                message = "模型回复格式无效，请重试。"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("assistant_propose"),
                    enabled = !busy && demand.isNotBlank() && candidates.size >= 2,
                ) { Text(if (busy) "正在生成建议" else "建议建组") }
                message?.let { Text(it, color = DockColors.Error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        proposal?.let { suggestion ->
            val members = suggestion.cardIds.mapNotNull { id -> candidates.firstOrNull { it.id == id } }
            item {
                Surface(color = DockColors.Surface, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(suggestion.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        members.forEach { Text(it.title, style = MaterialTheme.typography.bodyMedium) }
                        if (members.size != suggestion.cardIds.size) {
                            Text("候选卡片已变化，请重新生成建议。", style = MaterialTheme.typography.bodySmall, color = DockColors.Error)
                        }
                        Button(
                            onClick = {
                                submitted = true
                                preferences.edit().putBoolean("submitted", true).apply()
                                onCreateGroup(suggestion.name, suggestion.cardIds)
                            },
                            enabled = !busy && members.size == suggestion.cardIds.size,
                            modifier = Modifier.testTag("assistant_confirm_group"),
                        ) { Text("确认建组") }
                    }
                }
            }
        }
        undoGroup?.let { group ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("已建立「${group.name}」", Modifier.weight(1f))
                    TextButton({ onDissolveGroup(group.id) }, Modifier.testTag("assistant_undo_group")) { Text("撤销") }
                }
            }
        }
    }
}
