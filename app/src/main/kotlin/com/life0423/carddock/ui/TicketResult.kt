package com.life0423.carddock.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.life0423.carddock.core.model.FieldValue
import com.life0423.carddock.core.model.TicketFace
import com.life0423.carddock.core.model.TicketRole
import com.life0423.carddock.ui.theme.DockColors

/** 同一套票纸、油墨和读数排版用于预览及空间。 */
internal fun ticketPaper(role: TicketRole): Color = when (role) {
    TicketRole.COUNT -> DockColors.CountPaper
    TicketRole.TIME -> DockColors.TimePaper
    TicketRole.AMOUNT -> DockColors.MoneyPaper
    TicketRole.STATUS -> DockColors.Ticket
    TicketRole.TEXT -> DockColors.Surface
}

@Composable
internal fun TicketResult(face: TicketFace) {
    val first = face.fields.firstOrNull() ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TicketReading(first, face.role)
        face.fields.getOrNull(1)?.let { field ->
            Text(field.label + " · " + field.value, style = MaterialTheme.typography.bodySmall, color = DockColors.Muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 数量排成计数签，状态盖章，金额保留原币种，日期与钟点按原值分行。 */
@Composable
internal fun TicketReading(field: FieldValue, role: TicketRole, textMaxLines: Int = 3, showLabel: Boolean = true) {
    val ink = when (role) {
        TicketRole.COUNT -> DockColors.Sage
        TicketRole.TIME -> DockColors.Blue
        TicketRole.AMOUNT, TicketRole.STATUS -> DockColors.Mint
        TicketRole.TEXT -> DockColors.Text
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showLabel) Text(field.label, style = MaterialTheme.typography.labelMedium, color = ink)
        when (role) {
            TicketRole.STATUS -> Surface(color = Color.Transparent, contentColor = ink,
                border = BorderStroke(1.dp, ink), shape = RoundedCornerShape(5.dp)) {
                Text(field.value, Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            TicketRole.COUNT -> Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VerticalDivider(modifier = Modifier.height(27.dp), thickness = 3.dp, color = ink)
                Text(field.value, style = MaterialTheme.typography.headlineMedium, color = ink,
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
            }
            TicketRole.TIME -> {
                // 完整日期有固定长度；只拆日期与钟点，保留秒及原有精度。
                val parts = datedClock.matchEntire(field.value)
                Text(parts?.groupValues?.get(2) ?: field.value, style = MaterialTheme.typography.headlineSmall,
                    color = ink, fontWeight = FontWeight.SemiBold)
                parts?.let { Text(it.groupValues[1], style = MaterialTheme.typography.labelMedium, color = ink) }
            }
            TicketRole.AMOUNT -> {
                Text(field.value, style = MaterialTheme.typography.headlineSmall, color = ink,
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                HorizontalDivider(Modifier.width(64.dp), color = ink)
            }
            TicketRole.TEXT -> Text(field.value, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = textMaxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val datedClock = Regex("""(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}(?::\d{2})?)""")
