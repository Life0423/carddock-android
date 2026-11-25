package com.life0423.carddock.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Named palette values keep presentation decisions out of business code.
 * 业务层不要直接写色值。界面只引用这里的命名颜色。
 */
object DockColors {
    val Background = Color(0xFFF8F7F4)
    val Surface = Color.White
    val Raised = Color(0xFFECEAF8)
    val Outline = Color(0xFFE1DFDA)
    val Mint = Color(0xFF5148BD)
    val Amber = Color(0xFF8A5B16)
    val Blue = Color(0xFF345788)
    val Text = Color(0xFF242330)
    val Muted = Color(0xFF64636D)
    val Error = Color(0xFFB3333F)
}

private val CardDockColorScheme = lightColorScheme(
    primary = DockColors.Mint,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFECEAF8),
    onPrimaryContainer = Color(0xFF343185),
    secondary = DockColors.Blue,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = DockColors.Raised,
    onSecondaryContainer = DockColors.Text,
    tertiary = DockColors.Amber,
    background = DockColors.Background,
    onBackground = DockColors.Text,
    surface = DockColors.Surface,
    onSurface = DockColors.Text,
    surfaceVariant = DockColors.Raised,
    onSurfaceVariant = DockColors.Muted,
    outline = DockColors.Outline,
    error = DockColors.Error,
)

/**
 * 采用固定浅色品牌配色。
 */
@Composable
fun CardDockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CardDockColorScheme,
        typography = Typography(),
        content = content,
    )
}
