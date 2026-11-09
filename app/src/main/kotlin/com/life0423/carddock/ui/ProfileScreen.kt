package com.life0423.carddock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.session.DemoScenario
import com.life0423.carddock.core.session.PrototypeActions
import com.life0423.carddock.ui.theme.DockColors

@Composable
internal fun ProfileScreen(scenario: DemoScenario, actions: PrototypeActions) {
    var confirmReset by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.testTag("profile_list"),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("我的", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("探索卡坞的完整使用流程。", style = MaterialTheme.typography.bodyLarge, color = DockColors.Muted)
            }
        }
        item {
            Surface(color = DockColors.Surface, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextPill("CardDock · 原型", DockColors.Mint)
                    Text("你的卡片，保存在这台设备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "本原型通过模拟设备演示功能创建与执行。它不会读取其他应用，也不会向模型服务发送内容。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DockColors.Muted,
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("演示场景", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("选择后，尝试创建或刷新一张卡片。", color = DockColors.Muted, style = MaterialTheme.typography.bodyMedium)
                ScenarioSelector(scenario, actions.changeScenario)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("重新体验", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("恢复初始演示卡片，并清除本机添加的原型卡片。", color = DockColors.Muted, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { confirmReset = true },
                    modifier = Modifier.fillMaxWidth().testTag("reset_demo"),
                ) { Text("重置演示内容") }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复初始演示内容？") },
            text = { Text("这会清除你在原型中添加的卡片，并停止当前运行。") },
            confirmButton = {
                TextButton(
                    onClick = { confirmReset = false; actions.resetDemo() },
                    modifier = Modifier.testTag("confirm_reset"),
                ) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ScenarioSelector(selected: DemoScenario, onSelect: (DemoScenario) -> Unit) {
    Surface(color = DockColors.Surface, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.selectableGroup()) {
            DemoScenario.entries.forEachIndexed { index, scenario ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .selectable(selected = scenario == selected, onClick = { onSelect(scenario) }, role = Role.RadioButton)
                        .testTag("scenario_${scenario.name}")
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(scenario.title(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(scenario.description(), style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
                    }
                    RadioButton(selected = scenario == selected, onClick = null)
                }
                if (index < DemoScenario.entries.lastIndex) {
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = DockColors.Outline.copy(alpha = 0.45f))
                }
            }
        }
    }
}

private fun DemoScenario.title(): String = when (this) {
    DemoScenario.NORMAL -> "正常获取"
    DemoScenario.NEEDS_USER -> "需要接管"
    DemoScenario.READ_FAILURE -> "读取失败"
    DemoScenario.WRONG_TARGET -> "对象不匹配"
}

private fun DemoScenario.description(): String = when (this) {
    DemoScenario.NORMAL -> "通过核验，更新卡片与获取时间"
    DemoScenario.NEEDS_USER -> "暂停运行，体验手动接管后继续"
    DemoScenario.READ_FAILURE -> "提示失败，保留上次有效结果"
    DemoScenario.WRONG_TARGET -> "阻止错误对象的数据覆盖卡片"
}
