package com.life0423.carddock.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.style.TextOverflow
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
import com.life0423.carddock.data.SquareState
import com.life0423.carddock.ui.theme.DockColors

/** 模板页管理个人定义。使用模板和空间加号走同一配置入口。 */
@Composable
internal fun TemplatesScreen(
    personal: List<PersonalTemplate>, square: SquareState,
    enabled: Boolean, onUse: (TemplateDefinition) -> Unit,
    onEdit: (String?) -> Unit, onDelete: (String) -> Unit, onDiscard: (String) -> Unit,
    showDrafts: Boolean, onSection: (Boolean) -> Unit,
) {
    val routes = rememberedRoutes().filter { if (showDrafts) !it.saved else it.isTemplate }
    val entries = personal.filter { if (showDrafts) it.edit != null else it.published != null }
    var deleting by remember { mutableStateOf<PersonalTemplate?>(null) }
    LazyColumn(
        Modifier.testTag("templates_list"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                listOf("模板", "草稿").forEachIndexed { index, label ->
                    SegmentedButton(selected = showDrafts == (index == 1), onClick = { onSection(index == 1) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2), icon = {},
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = DockColors.Surface,
                            activeContentColor = DockColors.Mint, inactiveContainerColor = DockColors.Raised,
                            inactiveContentColor = DockColors.Muted),
                        modifier = Modifier.testTag("library_section_$index")) { Text(label) }
                }
            }
        }
        items(routes, key = { "route:" + it.id }) { row -> RouteTicket(row, enabled, library = true) }
        if (!showDrafts) {
            items(square.installed, key = { "download:" + it.id }) { entry ->
                val template = entry.definition()
                TemplateRow(entry.title, "已下载 · ${entry.author} · 第 ${entry.version} 版", template, enabled,
                    "建卡", { onUse(template) }, tag = "use_downloaded_${entry.id}")
            }
        }
        if (entries.isEmpty() && routes.isEmpty() && (showDrafts || square.installed.isEmpty())) item {
            Text(if (showDrafts) "暂无草稿" else "暂无可用模板", color = DockColors.Muted)
        }
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            val definition = entry.published.takeUnless { showDrafts }
            val title = definition?.title ?: entry.edit?.let { it.title.ifBlank { it.prompt.orEmpty().take(32) } }.orEmpty()
            var menu by remember { mutableStateOf(false) }
            TemplateRow(
                title, when {
                    definition == null -> entry.edit?.prompt.orEmpty().ifBlank { "尚未完成试运行" }
                    entry.edit != null -> "第 ${definition.revision} 版 · 有编辑草稿"
                    definition.pickupEntry != null -> "到站可出示取件码"
                    definition.rule.countRoute != null -> definition.rule.countRoute?.page.orEmpty()
                    definition.rule.monitorComparison != null -> definition.description
                    else -> ""
                }, definition, enabled,
                if (definition == null) "继续编辑" else "建卡",
                { if (definition == null) onEdit(entry.id) else onUse(definition) },
                tag = "use_template_${entry.id}", first = index == 0, last = index == entries.lastIndex,
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
                            DropdownMenuItem(text = { Text(if (showDrafts) "删除草稿" else "删除模板") },
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
            title = { Text(if (showDrafts) "删除草稿？" else "删除模板？") },
            text = { Text(if (showDrafts) "将移除这份未保存的编辑内容。" else "已从此模板创建的卡片可以继续使用。") },
            confirmButton = { TextButton(onClick = { if (showDrafts) onDiscard(entry.id) else onDelete(entry.id); deleting = null }) { Text("删除") } },
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val routes = rememberedRoutes().filter { it.isTemplate && it.demand().contains(query.trim(), ignoreCase = true) }
    val matches = ready.filter { it.title.contains(query.trim(), ignoreCase = true) }
    val downloads = downloaded.filter { it.title.contains(query.trim(), ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DockColors.Background) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 540.dp).testTag("template_picker"),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("选择卡片模板", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item { FlowSteps(listOf("选模板", "配置试查", "添加到空间"), 0) }
            item {
                OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("picker_new_template"),
                    shape = RoundedCornerShape(14.dp)) {
                    Text("去创建新模板")
                }
                OutlinedTextField(
                    query, { query = it }, label = { Text("搜索模板") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (matches.isEmpty() && downloads.isEmpty() && routes.isEmpty()) item {
                Text(if (query.isBlank()) "暂无可用模板" else "没有匹配的模板")
            }
            items(routes, key = { "route:" + it.id }) { row ->
                TemplateRow(row.demand(), row.scope, null, true, "选择", {
                    onDismiss()
                    context.openRoutes(id = row.id)
                }, tag = "pick_route_${row.id}")
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
    first: Boolean = true, last: Boolean = true, rank: Int? = null,
    extra: @Composable () -> Unit = {},
) {
    val featured = rank == 1
    Surface(color = if (featured) DockColors.Mint else DockColors.Surface,
        contentColor = if (featured) MaterialTheme.colorScheme.onPrimary else DockColors.Text,
        shape = RoundedCornerShape(if (first) 16.dp else 3.dp, if (first) 16.dp else 3.dp,
        if (last) 16.dp else 3.dp, if (last) 16.dp else 3.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (rank != null) Text(rank.toString().padStart(2, '0'),
                color = if (featured) MaterialTheme.colorScheme.onPrimary else DockColors.Muted, style = MaterialTheme.typography.titleSmall)
            if (template != null) KindIcon(template.kind, template.rule, theme = template.theme)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = if (featured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (subtitle.isNotBlank()) Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (featured) MaterialTheme.colorScheme.onPrimary else DockColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            // 小胶囊独立绘制，外层完整区域响应点按；长文案和大字可自然撑高。
            Box(Modifier.widthIn(min = 60.dp).heightIn(min = 48.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onUse).testTag(tag),
                contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(20.dp),
                    color = if (enabled) MaterialTheme.colorScheme.primaryContainer else DockColors.Raised) {
                    Box(Modifier.widthIn(min = 56.dp).heightIn(min = 32.dp).padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center) {
                        Text(action, style = MaterialTheme.typography.labelLarge,
                            color = if (enabled) DockColors.Mint else DockColors.Muted)
                    }
                }
            }
            extra()
        }
    }
}
