package com.life0423.carddock.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.platform.CAINIAO_PACKAGE
import com.life0423.carddock.platform.CardDockAccessibilityService
import com.life0423.carddock.platform.MEITUAN_PACKAGE
import com.life0423.carddock.ui.theme.DockColors

internal fun CardRecord.resultSource(): String = snapshot?.sourceLabel ?: when (val source = rule?.sourceId) {
    CAINIAO_PACKAGE -> "菜鸟"
    MEITUAN_PACKAGE -> "美团"
    null -> page?.let { Uri.parse(it.url).host }.orEmpty()
    else -> if (source.startsWith("demo.")) "模拟设备 · 演示数据" else source
}

internal fun CardRecord.latestPhase(): RunPhase =
    run?.phase ?: if (snapshot == null) RunPhase.IDLE else RunPhase.SUCCEEDED

/**
 * The same card component renders fresh, stale, running and interrupted states.
 * 没有 run 时：无快照为待运行，有快照视为已更新。
 * 只要有上一份快照，排队、运行、失败、取消、中断和接管都会显示它，并标明不是本次结果。
 * 移除先确认。确认框只活在这里，取消确认不会碰会话。
 */
@Composable
internal fun FunctionCard(
    card: CardRecord,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val phase = card.latestPhase()
    val fields = card.snapshot?.fields.orEmpty()
    // 按卡片 id 记住。列表复用到另一张卡时丢掉上一张的删除确认。
    var menuOpen by remember(card.id) { mutableStateOf(false) }
    var confirmDelete by remember(card.id) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("card_${card.id}"),
        color = DockColors.Surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, DockColors.Outline),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(card.kind.icon(), null, Modifier.size(20.dp), tint = card.kind.accent())
                Text(card.resultSource(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = DockColors.Muted)
                StatusBadge(phase)
                Box {
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
            val primary = fields.firstOrNull()
            if (primary != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(primary.label, style = MaterialTheme.typography.labelLarge, color = DockColors.Muted)
                    Text(primary.value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            } else {
                Text("尚无核验结果", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            card.snapshot?.let {
                Text("上次成功 ${formatTimestamp(it.fetchedAt)}", style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
                if (phase != RunPhase.SUCCEEDED && phase != RunPhase.IDLE) {
                    Text("暂显示上次有效结果", color = DockColors.Amber, style = MaterialTheme.typography.labelMedium)
                }
            }
            card.run?.message?.takeIf { it.isNotBlank() && phase != RunPhase.SUCCEEDED }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = if (phase == RunPhase.FAILED) DockColors.Error else DockColors.Amber)
            }
            if (phase.isBusy()) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = DockColors.Mint, trackColor = DockColors.Raised)
            }
            CardActions(card.id, phase, onRefresh, onCancel, onResume)
            val sourceId = card.rule?.sourceId
            if (phase == RunPhase.NEEDS_USER && (sourceId == CAINIAO_PACKAGE || sourceId == MEITUAN_PACKAGE) &&
                CardDockAccessibilityService.instance == null) {
                val context = LocalContext.current
                TextButton({ context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("打开无障碍设置") }
            }
            if (fields.size > 1) {
                HorizontalDivider(color = DockColors.Outline)
                SnapshotFields(fields.drop(1))
            }
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
    // NEEDS_USER 不算忙碌：设备读取已经返回，要同时给出接管和取消。忙碌时只能取消。
    when {
        phase.isBusy() -> OutlinedButton(onCancel, Modifier.fillMaxWidth().testTag("cancel_$id")) {
            Text("取消运行")
        }
        phase == RunPhase.NEEDS_USER -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onResume, Modifier.weight(1f).testTag("resume_$id")) { Text("重新核验") }
            TextButton(onCancel, Modifier.testTag("cancel_$id")) { Text("取消") }
        }
        else -> Button(onRefresh, Modifier.fillMaxWidth().testTag("refresh_$id")) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
            Text(if (phase == RunPhase.FAILED || phase == RunPhase.INTERRUPTED) "重新运行" else "刷新卡片", Modifier.padding(start = 6.dp))
        }
    }
}
