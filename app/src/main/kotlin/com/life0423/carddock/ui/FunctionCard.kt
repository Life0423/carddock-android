package com.life0423.carddock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.ui.theme.DockColors

/** The same card component renders fresh, stale, running and interrupted states. */
@Composable
internal fun FunctionCard(
    card: CardRecord,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val phase = card.run?.phase ?: if (card.snapshot == null) RunPhase.IDLE else RunPhase.SUCCEEDED
    var menuOpen by remember(card.id) { mutableStateOf(false) }
    var confirmDelete by remember(card.id) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("card_${card.id}"),
        color = DockColors.Surface,
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KindIcon(card.kind)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    StatusBadge(phase)
                }
                Column {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("menu_${card.id}")) {
                        Icon(DockIcons.MoreHoriz, contentDescription = "${card.title}的更多操作", tint = DockColors.Muted)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("移除卡片") },
                            onClick = { menuOpen = false; confirmDelete = true },
                            modifier = Modifier.testTag("remove_${card.id}"),
                        )
                    }
                }
            }

            card.snapshot?.let { snapshot ->
                if (phase != RunPhase.SUCCEEDED && phase != RunPhase.IDLE) {
                    Text("暂显示上次有效结果", color = DockColors.Amber, style = MaterialTheme.typography.labelMedium)
                }
                SnapshotFields(snapshot.fields)
            } ?: Text("还没有有效结果。点击刷新，执行并核验这张功能卡。", color = DockColors.Muted)

            card.run?.message?.takeIf { it.isNotBlank() && phase != RunPhase.SUCCEEDED }?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (phase == RunPhase.FAILED) DockColors.Error else DockColors.Amber,
                )
            }
            if (phase.isBusy()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DockColors.Mint, trackColor = DockColors.Raised)
            }

            HorizontalDivider(color = DockColors.Outline.copy(alpha = 0.5f))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(card.snapshot?.sourceLabel ?: "模拟设备 · 演示数据", style = MaterialTheme.typography.labelSmall, color = DockColors.Muted)
                card.snapshot?.let {
                    Text("上次成功 ${formatTimestamp(it.fetchedAt)}", style = MaterialTheme.typography.labelSmall, color = DockColors.Muted)
                }
            }
            CardActions(card.id, phase, onRefresh, onCancel, onResume)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("移除这张卡片？") },
            text = { Text("“${card.title}”将从空间移除，之后可以从模板重新添加。") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onDelete() },
                    modifier = Modifier.testTag("confirm_remove_${card.id}"),
                ) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("保留") } },
        )
    }
}

@Composable
private fun CardActions(
    id: String,
    phase: RunPhase,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
) {
    when {
        phase.isBusy() -> OutlinedButton(onCancel, Modifier.fillMaxWidth().testTag("cancel_$id")) {
            Text("取消运行")
        }
        phase == RunPhase.NEEDS_USER -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onResume, Modifier.weight(1f).testTag("resume_$id")) { Text("完成模拟接管") }
            TextButton(onCancel, Modifier.testTag("cancel_$id")) { Text("取消") }
        }
        else -> FilledTonalButton(onRefresh, Modifier.fillMaxWidth().testTag("refresh_$id")) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
            Text(if (phase == RunPhase.FAILED || phase == RunPhase.INTERRUPTED) "重新运行" else "刷新卡片", Modifier.padding(start = 6.dp))
        }
    }
}
