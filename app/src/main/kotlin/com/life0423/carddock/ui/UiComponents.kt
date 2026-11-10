package com.life0423.carddock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.model.FieldValue
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateKind
import com.life0423.carddock.ui.theme.DockColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun TemplateKind.icon(): ImageVector = when (this) {
    TemplateKind.EXPRESS -> Icons.Rounded.Inventory2
    TemplateKind.DELIVERY -> Icons.Rounded.LocalShipping
    TemplateKind.TRAVEL -> Icons.Rounded.Train
}

internal fun TemplateKind.accent(): Color = when (this) {
    TemplateKind.EXPRESS -> DockColors.Amber
    TemplateKind.DELIVERY -> DockColors.Mint
    TemplateKind.TRAVEL -> DockColors.Blue
}

@Composable
internal fun KindIcon(kind: TemplateKind, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(42.dp),
        color = kind.accent().copy(alpha = 0.12f),
        shape = RoundedCornerShape(13.dp),
    ) {
        Icon(
            imageVector = kind.icon(),
            contentDescription = null,
            tint = kind.accent(),
            modifier = Modifier.padding(10.dp),
        )
    }
}

internal fun RunPhase.label(): String = when (this) {
    RunPhase.IDLE -> "待运行"
    RunPhase.QUEUED -> "排队中"
    RunPhase.RUNNING -> "运行中"
    RunPhase.NEEDS_USER -> "需要接管"
    RunPhase.SUCCEEDED -> "已更新"
    RunPhase.FAILED -> "刷新失败"
    RunPhase.CANCELLED -> "已取消"
    RunPhase.INTERRUPTED -> "已中断"
}

internal fun RunPhase.isBusy(): Boolean = this == RunPhase.RUNNING || this == RunPhase.QUEUED

@Composable
internal fun StatusBadge(phase: RunPhase) {
    val color = when (phase) {
        RunPhase.SUCCEEDED -> DockColors.Mint
        RunPhase.RUNNING, RunPhase.QUEUED, RunPhase.NEEDS_USER -> DockColors.Amber
        RunPhase.FAILED -> DockColors.Error
        else -> DockColors.Muted
    }
    TextPill(phase.label(), color)
}

@Composable
internal fun TextPill(text: String, color: Color = DockColors.Mint) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
internal fun SnapshotFields(fields: List<FieldValue>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fields.forEach { field ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    text = field.label,
                    color = DockColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(76.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = field.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

internal fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("MM月dd日 HH:mm:ss", Locale.SIMPLIFIED_CHINESE).format(Date(epochMillis))
