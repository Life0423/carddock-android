package com.life0423.carddock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.model.TemplateDefinition
import com.life0423.carddock.ui.theme.DockColors

/** 模板页不执行规则。点试用会带着该模板打开草稿，提示词由控制器预填，规则仍要试运行后才保存。 */
@Composable
internal fun TemplatesScreen(
    templates: List<TemplateDefinition>,
    onUse: (TemplateDefinition?) -> Unit,
    openModelCreator: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = Modifier.testTag("templates_list"),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("从一个好用的模板开始", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("先试运行，再把功能放进你的空间。", color = DockColors.Muted, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (openModelCreator != null) item {
            FilledTonalButton(onClick = openModelCreator, modifier = Modifier.fillMaxWidth()) { Text("用模型生成菜鸟候选") }
        }
        items(templates, key = { it.id }) { template ->
            TemplateCard(template, onUse = { onUse(template) })
        }
        item {
            Text(
                "演示模板使用模拟数据；真机模板需先启用页面读取。",
                style = MaterialTheme.typography.bodySmall,
                color = DockColors.Muted,
            )
        }
    }
}

@Composable
private fun TemplateCard(template: TemplateDefinition, onUse: () -> Unit) {
    Surface(color = DockColors.Surface, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KindIcon(template.kind)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(template.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (template.rule.sourceId.startsWith("demo.")) "内置演示模板" else "真机只读模板", style = MaterialTheme.typography.labelSmall, color = DockColors.Muted)
                }
            }
            Text(template.description, style = MaterialTheme.typography.bodyMedium, color = DockColors.Muted)
            Surface(color = DockColors.Background.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp)) {
                Text("“${template.examplePrompt}”", Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            FilledTonalButton(onUse, Modifier.fillMaxWidth().testTag("use_template_${template.id}")) {
                Text("试用这个模板")
            }
        }
    }
}
