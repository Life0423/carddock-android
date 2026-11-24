package com.life0423.carddock.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val DockButtonShape = RoundedCornerShape(12.dp)

/** 建卡与卡片详情内，Material 按钮继承同一个随系统字号缩放的操作文字样式。 */
@Composable internal fun DockActionTheme(content: @Composable () -> Unit) {
    val type = MaterialTheme.typography
    MaterialTheme(typography = type.copy(labelLarge = type.labelLarge.copy(
        fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)), content = content)
}
