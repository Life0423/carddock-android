package com.life0423.carddock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.R
import com.life0423.carddock.MEITUAN_HISTORY
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleStep
import com.life0423.carddock.platform.MEITUAN_PACKAGE
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

/** 模板通过既有创建流程试运行；规则文件通过系统文档选择器写入。 */
@Composable
internal fun TemplatesScreen(
    cards: List<CardRecord>,
    templates: List<TemplateDefinition>,
    onUseTemplate: (TemplateDefinition) -> Unit,
    onUseOrder: () -> Unit,
) {
    var files by rememberSaveable { mutableStateOf(false) }
    val (examples, deviceTemplates) = templates.partition { it.rule.sourceId.startsWith("demo.") }
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
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("templates_list"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("模板", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)
                    .background(DockColors.Outline.copy(alpha = .55f), RoundedCornerShape(14.dp)).padding(3.dp)
                    .selectableGroup(),
            ) {
                listOf("卡片模板", "规则文件").forEachIndexed { index, label ->
                    val selected = files == (index == 1)
                    Surface(
                        color = if (selected) DockColors.Surface else Color.Transparent,
                        shape = RoundedCornerShape(11.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(Modifier.selectable(selected, role = Role.Tab, onClick = { files = index == 1 })
                            .heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) DockColors.Text else DockColors.Muted)
                        }
                    }
                }
            }
        }
        if (!files) {
            if (deviceTemplates.isNotEmpty()) item {
                Text("应用模板", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            items(deviceTemplates, key = { it.id }) { TemplateEntry(it, demo = false, onUseTemplate) }
            item {
                Surface(onClick = onUseOrder, modifier = Modifier.fillMaxWidth().testTag("use_template_meituan"),
                    color = DockColors.Raised, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        KindIcon(TemplateKind.DELIVERY)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(MEITUAN_HISTORY, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("核验订单已完成 · 需登录美团", color = DockColors.Muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("使用", color = DockColors.Mint, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (examples.isNotEmpty()) item {
                Text("演示模板", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            items(examples, key = { it.id }) { TemplateEntry(it, demo = true, onUseTemplate) }
        } else {
            item {
                Text("导出包含卡片名称和规则。运行结果保存在本机。", Modifier.padding(vertical = 8.dp),
                    color = DockColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            message?.let { item { Text(it) } }
            if (cards.none { it.rule != null }) item { Text("还没有可导出的卡片", color = DockColors.Muted) }
            items(cards.filter { it.rule != null }, key = { it.id }) { card ->
                val rule = requireNotNull(card.rule)
                Surface(color = DockColors.Surface, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(DockIcons.Folder, null, tint = DockColors.Mint, modifier = Modifier.size(23.dp))
                        Column(Modifier.weight(1f)) {
                            Text(card.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("JSON · 规则 v${rule.schemaVersion}", style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
                        }
                        if (rule.sourceId == MEITUAN_PACKAGE) {
                            Text("仅本机", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelMedium, color = DockColors.Muted)
                        } else {
                            TextButton({
                                pending = json.encodeToString(SkillExport(title = card.title, rule = rule))
                                export.launch("carddock-skill.json")
                            }, enabled = pending == null) { Text("导出") }
                        }
                    }
                }
            }
        }
    }
}

/** 真实模板说明授权条件，演示模板沿用交接图作索引，字段名取自实际规则。 */
@Composable
private fun TemplateEntry(template: TemplateDefinition, demo: Boolean, onUse: (TemplateDefinition) -> Unit) {
    Surface(
        onClick = { onUse(template) }, modifier = Modifier.fillMaxWidth().testTag("use_template_${template.id}"),
        color = if (demo) DockColors.Surface else DockColors.Raised, shape = RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (demo) {
                val photo = when (template.kind) {
                    TemplateKind.EXPRESS -> R.drawable.demo_parcels
                    TemplateKind.DELIVERY -> R.drawable.demo_meal
                    TemplateKind.TRAVEL -> R.drawable.demo_canal
                }
                Image(painterResource(photo), null, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop)
            } else {
                KindIcon(template.kind)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(template.title.removeSuffix(" · 真机"), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(if (demo) template.rule.steps.filterIsInstance<RuleStep.ReadField>().joinToString(" · ") { it.label }
                    else if (template.id == "cainiao-empty") "仅核验无待取包裹 · 需开启页面读取" else template.description,
                    color = DockColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("使用", color = DockColors.Mint, style = MaterialTheme.typography.labelLarge)
        }
    }
}
