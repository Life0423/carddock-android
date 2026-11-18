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
    val Background = Color(0xFFFFFFFF)
    val Surface = Color(0xFFF5F5F8)
    val Raised = Color(0xFFF0EFFF)
    val Outline = Color(0xFFE8E9EE)
    val Mint = Color(0xFF5754D6)
    val Amber = Color(0xFF8A5B16)
    val Blue = Color(0xFF5754D6)
    val Text = Color(0xFF202124)
    val Muted = Color(0xFF737780)
    val Error = Color(0xFFB3333F)
}

private val CardDockColorScheme = lightColorScheme(
    primary = DockColors.Mint,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF0EFFF),
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
 * 已选浅色主题；保留固定品牌色，不跟随壁纸动态取色。
 * 不跟系统动态取色，演示配色不会被壁纸色替换。
 */
@Composable
fun CardDockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CardDockColorScheme,
        typography = Typography(),
        content = content,
    )
}
