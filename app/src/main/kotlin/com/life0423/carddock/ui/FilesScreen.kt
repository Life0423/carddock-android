package com.life0423.carddock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.ui.theme.DockColors
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 可导出的 Skill 定义，不包含快照、运行记录和用户原始描述中的私人信息。 */
@Serializable
private data class SkillExport(val format: String = "carddock.skill", val version: Int = 1, val title: String, val rule: RuleSpec)

/** 文件页目前提供系统文档导出；导入和关联文件预览尚未接入，不展示假的文件记录。 */
@Composable
internal fun FilesScreen(cards: List<CardRecord>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val json = remember { Json { prettyPrint = true; encodeDefaults = true } }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pending
        pending = null
        if (uri != null && text != null) scope.launch {
            message = try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: throw IOException("无法打开文件")
                    output.bufferedWriter().use { it.write(text) }
                }
                "规则已导出"
            } catch (_: IOException) { "导出失败，请重新选择保存位置" }
              catch (_: SecurityException) { "无法写入所选位置，请重新选择" }
        }
    }
    LazyColumn(Modifier.testTag("files_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("规则文件", style = MaterialTheme.typography.headlineMedium)
            Text("导出卡片名称与规则，不包含运行结果。导入与关联文件预览尚未开放。", color = DockColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        message?.let { item { Text(it) } }
        if (cards.isEmpty()) item { Text("空间里还没有可导出的卡片") }
        items(cards, key = { it.id }) { card ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(card.title, style = MaterialTheme.typography.titleMedium)
                    Text("规则 v${card.rule.schemaVersion} · ${card.rule.sourceId}", style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
                }
                TextButton({
                    pending = json.encodeToString(SkillExport(title = card.title, rule = card.rule))
                    export.launch("carddock-skill.json")
                }, enabled = pending == null) { Text("导出") }
            }
        }
    }
}
