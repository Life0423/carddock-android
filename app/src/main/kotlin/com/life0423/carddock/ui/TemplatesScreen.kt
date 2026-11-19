package com.life0423.carddock.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.model.PersonalTemplate
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.data.SquareEntry
import com.life0423.carddock.data.SquareState
import com.life0423.carddock.ui.theme.DockColors

/** 模板页管理个人定义。使用模板和空间加号走同一配置入口。 */
@Composable
internal fun TemplatesScreen(
    personal: List<PersonalTemplate>, square: SquareState,
    enabled: Boolean, onUse: (TemplateDefinition) -> Unit,
    onEdit: (String?) -> Unit, onDelete: (String) -> Unit,
    onDownload: (SquareEntry) -> Unit, onUnfavorite: (SquareEntry) -> Unit,
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<PersonalTemplate?>(null) }
    LazyColumn(
        Modifier.testTag("templates_list"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TabRow(selectedTabIndex = section, containerColor = DockColors.Background) {
                listOf("我的", "已下载", "收藏").forEachIndexed { index, label ->
                    Tab(selected = section == index, onClick = { section = index },
                        text = { Text(label) }, modifier = Modifier.testTag("library_section_$index"))
                }
            }
        }
        if (section == 1) {
            if (square.installed.isEmpty()) item { Text("暂无已下载模板", color = DockColors.Muted) }
            items(square.installed, key = { it.id }) { entry ->
                val template = entry.definition()
                TemplateRow(entry.title, "${entry.author} · 第 ${entry.version} 版", template, enabled,
                    "使用", { onUse(template) }, tag = "use_downloaded_${entry.id}")
            }
        }
        if (section == 2) {
            if (square.favorites.isEmpty()) item { Text("暂无收藏模板", color = DockColors.Muted) }
            square.message?.let { message -> item { Text(message, color = DockColors.Muted) } }
            items(square.favorites, key = { it.id }) { entry ->
                TemplateRow(entry.title, "${entry.author} · 第 ${entry.version} 版", entry.definition(),
                    enabled && !square.busy, "下载", { onDownload(entry) }, tag = "download_favorite_${entry.id}",
                    extra = { TextButton({ onUnfavorite(entry) }, enabled = !square.busy) { Text("取消收藏") } })
            }
        }
        if (section == 0) item {
            DockPrimaryAction("新建模板", { onEdit(null) }, Modifier.testTag("new_template"), enabled)
        }
        if (section == 0 && personal.isEmpty()) item {
            Text("新建模板后，可从空间添加卡片。", color = DockColors.Muted)
        }
        items(if (section == 0) personal else emptyList(), key = { it.id }) { entry ->
            val definition = entry.published
            val title = definition?.title ?: entry.edit?.let { it.title.ifBlank { it.prompt.orEmpty().take(32) } }.orEmpty()
            var menu by remember { mutableStateOf(false) }
            TemplateRow(
                title, when {
                    definition == null -> "草稿 · 尚未发布"
                    entry.edit != null -> "第 ${definition.revision} 版 · 有编辑草稿"
                    else -> "第 ${definition.revision} 版 · 可复用"
                }, definition, enabled,
                if (definition == null) "继续编辑" else "使用",
                { if (definition == null) onEdit(entry.id) else onUse(definition) },
                tag = "use_template_${entry.id}",
                extra = {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = enabled) {
                            Icon(DockIcons.MoreHoriz, "$title 的模板操作")
                        }
                        DropdownMenu(menu, { menu = false }) {
                            if (definition != null) DropdownMenuItem(
                                text = { Text(if (entry.edit == null) "编辑模板" else "继续编辑") },
                                onClick = { menu = false; onEdit(entry.id) },
                            )
                            DropdownMenuItem(text = { Text("删除模板") },
                                onClick = { menu = false; deleting = entry })
                        }
                    }
                },
            )
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除模板？") },
            text = { Text("已从此模板创建的卡片可以继续使用。") },
            confirmButton = { TextButton(onClick = { onDelete(entry.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

/** 选择器只展示可用版本，选定后立即转入独立配置页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardTemplatePicker(
    ready: List<TemplateDefinition>, downloaded: List<TemplateDefinition>,
    onDismiss: () -> Unit, onUse: (TemplateDefinition) -> Unit, onCreate: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = ready.filter { it.title.contains(query.trim(), ignoreCase = true) }
    val downloads = downloaded.filter { it.title.contains(query.trim(), ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DockColors.Background) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 540.dp).testTag("template_picker"),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("添加卡片", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item {
                OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("picker_new_template"),
                    shape = RoundedCornerShape(14.dp)) {
                    Text("去创建新模板")
                }
                if (ready.isNotEmpty() || downloaded.isNotEmpty()) OutlinedTextField(
                    query, { query = it }, label = { Text("搜索模板") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (matches.isEmpty() && downloads.isEmpty()) item {
                Text(if (ready.isEmpty() && downloaded.isEmpty()) "暂无可用模板" else "没有匹配的模板")
            }
            if (matches.isNotEmpty()) item { Text("我的模板", style = MaterialTheme.typography.titleMedium) }
            items(matches, key = { it.id }) { template ->
                TemplateRow(template.title, "第 ${template.revision} 版", template, true, "选择",
                    { onUse(template) }, tag = "pick_template_${template.id}")
            }
            if (downloads.isNotEmpty()) item { Text("已下载", style = MaterialTheme.typography.titleMedium) }
            items(downloads, key = { it.id }) { template ->
                TemplateRow(template.title, "第 ${template.revision} 版", template, true, "选择",
                    { onUse(template) }, tag = "pick_downloaded_${template.id}")
            }
        }
    }
}

@Composable
internal fun TemplateRow(
    title: String, subtitle: String, template: TemplateDefinition?, enabled: Boolean,
    action: String, onUse: () -> Unit, tag: String,
    extra: @Composable () -> Unit = {},
) {
    Surface(color = DockColors.Surface, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, DockColors.Outline)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (template != null) KindIcon(template.kind)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, color = DockColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onUse, enabled = enabled, modifier = Modifier.heightIn(min = 40.dp).testTag(tag),
                contentPadding = PaddingValues(horizontal = 12.dp)) { Text(action) }
            extra()
        }
    }
}
