package com.life0423.carddock.ui

import com.life0423.carddock.isBookQuery
import com.life0423.carddock.core.model.ticketFace
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.ui.theme.DockColors

/**
 * The same card component renders fresh, stale, running and interrupted states.
 * 没有 run 时：无快照为待运行，有快照视为已更新。
 * 只要有上一份快照，排队、运行、失败、取消、中断和接管都会显示它，并标明不是本次结果。
 * 移除先确认。确认框只活在这里，取消确认不会碰会话。
 */
@Composable
internal fun FunctionCard(
    card: CardRecord,
    onDelete: () -> Unit,
    onPrice: (() -> Unit)? = null,
    canStart: Boolean = true,
    onConfigureRoute: (() -> Unit)? = null,
    pickupShortcut: (@Composable () -> Unit)? = null,
    listCode: com.life0423.carddock.core.model.PickupEntry? = null,
    onReplaceRule: (() -> Unit)? = null,
    onRollbackRule: (() -> Unit)? = null,
) {
    val phase = card.run?.phase ?: if (card.snapshot == null) RunPhase.IDLE else RunPhase.SUCCEEDED
    // 按卡片 id 记住。列表复用到另一张卡时丢掉上一张的删除确认。
    var menuOpen by remember(card.id) { mutableStateOf(false) }
    var confirmDelete by remember(card.id) { mutableStateOf(false) }

    val snapshot = card.snapshot
    val previousBooks = card.rule.isBookQuery && snapshot != null && phase !in setOf(RunPhase.SUCCEEDED, RunPhase.IDLE)
    val face = snapshot?.ticketFace(card.display, card.rule)
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("card_${card.id}"),
        color = DockColors.Surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, DockColors.Outline),
    ) {
        Column {
            Column(Modifier.fillMaxWidth().background(face?.let { ticketPaper(it.role) } ?: DockColors.Surface)
                .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KindIcon(card.kind, card.rule, theme = card.theme)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        StatusBadge(phase)
                    }
                    Column {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("menu_${card.id}")) {
                            Icon(DockIcons.MoreHoriz, contentDescription = "${card.title}的更多操作", tint = DockColors.Muted)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (onConfigureRoute != null) DropdownMenuItem(
                                text = { Text("设置后台订单路由") }, enabled = canStart && !phase.isBusy(),
                                onClick = { menuOpen = false; onConfigureRoute() },
                            )
                            if (onReplaceRule != null) DropdownMenuItem(
                                text = { Text("替换菜鸟规则") }, enabled = canStart && !phase.isBusy(),
                                onClick = { menuOpen = false; onReplaceRule() },
                            )
                            if (onRollbackRule != null) DropdownMenuItem(
                                text = { Text("回退菜鸟规则") }, enabled = canStart && !phase.isBusy(),
                                onClick = { menuOpen = false; onRollbackRule() },
                            )
                            DropdownMenuItem(
                                text = { Text("移除卡片") },
                                onClick = { menuOpen = false; confirmDelete = true },
                                modifier = Modifier.testTag("remove_${card.id}"),
                            )
                        }
                    }
                }

                if (previousBooks) {
                    Text("暂显示上次有效结果 · " + formatTimestamp(requireNotNull(snapshot).fetchedAt),
                        color = DockColors.Amber, style = MaterialTheme.typography.bodyMedium)
                    card.run?.message?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                if (card.rule.isBookQuery && face != null) BookFields(face.fields)
                else face?.fields?.firstOrNull()?.let { TicketReading(it, face.role, textMaxLines = Int.MAX_VALUE) }
                if (snapshot == null) Text("还没有有效结果。点击刷新，执行并核验这张功能卡。", color = DockColors.Muted)
            }
            Canvas(Modifier.fillMaxWidth().height(1.dp)) {
                drawLine(DockColors.Outline, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
            }
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 共用首页的字段投影。订单时间来自来源字段，卡坞核验时间只取成功快照。
                val stacked = LocalDensity.current.fontScale >= 1.3f
                if (!card.rule.isBookQuery) face?.fields?.drop(1)?.forEach { field ->
                    if (stacked) Column {
                        Text(field.label, style = MaterialTheme.typography.labelMedium, color = DockColors.Muted)
                        Text(field.value, style = MaterialTheme.typography.bodyLarge)
                    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(field.label, Modifier.weight(0.36f), style = MaterialTheme.typography.bodyMedium, color = DockColors.Muted)
                        Text(field.value, Modifier.weight(0.64f), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                    }
                }
                if (snapshot != null) {
                    if (face != null && face.fields.size > 1) HorizontalDivider(color = DockColors.Outline)
                    // 只折叠已出现在可见价格来源字段中的同一条证据，用户隐藏该字段后仍显示来源。
                    if (face?.fields.orEmpty().none { it.key == "basis" && it.value == snapshot.sourceLabel }) {
                        Text(snapshot.sourceLabel, style = MaterialTheme.typography.bodySmall, color = DockColors.Muted)
                    }
                    Column {
                        Text("卡坞上次成功核验", style = MaterialTheme.typography.labelMedium, color = DockColors.Muted)
                        Text(formatTimestamp(snapshot.fetchedAt, includeYear = true), style = MaterialTheme.typography.titleMedium)
                    }
                    if (card.rule.isBookQuery && face != null) BookEvidence(face.fields)
                    if (!previousBooks && phase != RunPhase.SUCCEEDED && phase != RunPhase.IDLE) {
                        Text("暂显示上次有效结果", color = DockColors.Amber, style = MaterialTheme.typography.labelMedium)
                    }
                }
                card.run?.message?.takeIf { it.isNotBlank() && !previousBooks && phase != RunPhase.SUCCEEDED }?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (phase == RunPhase.FAILED) DockColors.Error else DockColors.Amber,
                    )
                }
                if (phase == RunPhase.NEEDS_USER) PhoneRecoveryButton(card.run?.missingCapability)
                if (phase.isBusy()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DockColors.Mint, trackColor = DockColors.Raised)
                }

                card.rule.localReminder?.let { ReminderOptions(it, card.id) }
                OfficialActionButtons(card.rule, card.opensPickupList, card.pickupEntry, listCode,
                    canStart && !phase.isBusy()) { pickupShortcut?.invoke() }
                if (onPrice != null) {
                    PriceEvidence(card.appPrice)
                    if (card.priceVerificationFailed && card.appPrice != null) {
                        Text("上次有效价格 · 本次未核验", color = DockColors.Amber)
                    }
                    OutlinedButton(onClick = onPrice, enabled = canStart && !phase.isBusy()) { Text("核验 App 展示价") }
                }

            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("移除这张卡片？") },
            text = { Text(if (card.rule.sourceId == com.life0423.carddock.platform.APPLE_STORE &&
                card.rule.targetKey != com.life0423.carddock.platform.PRICE_RANK)
                "移除本配置最后一张跟踪卡后，每日核价会停止，本机价格记录会清除。"
                else "“${card.title}”将从空间移除，之后可以从模板重新添加。") },
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
internal fun CardActions(
    id: String,
    phase: RunPhase,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    canStart: Boolean,
    resumeLabel: String,
) {
    // NEEDS_USER 不算忙碌：设备读取已经返回，要同时给出接管和取消。忙碌时只能取消。
    when {
        phase.isBusy() -> OutlinedButton(onCancel, Modifier.fillMaxWidth().testTag("cancel_$id")) {
            Text("取消运行")
        }
        phase == RunPhase.NEEDS_USER -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onResume, Modifier.weight(1f).testTag("resume_$id"), enabled = canStart) { Text(resumeLabel) }
            TextButton(onCancel, Modifier.testTag("cancel_$id")) { Text("取消") }
        }
        else -> Button(onRefresh, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("refresh_$id"), enabled = canStart, shape = DockButtonShape) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
            Text(if (phase == RunPhase.FAILED || phase == RunPhase.INTERRUPTED) "重新运行" else "刷新卡片", Modifier.padding(start = 6.dp))
        }
    }
}
