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
    val Background = Color(0xFFF7F3EA)
    val Surface = Color(0xFFFFFDF7)
    val Raised = Color(0xFFEFE6D8)
    val Outline = Color(0xFFDCD1C0)
    val Mint = Color(0xFF963A29)
    val Ticket = Color(0xFFF1DABC)
    val CountPaper = Color(0xFFE6EBDD)
    val TimePaper = Color(0xFFDFEAEB)
    val MoneyPaper = Color(0xFFF4E3D6)
    val Sage = Color(0xFF52674F)
    val Amber = Color(0xFF85522E)
    val Blue = Color(0xFF415E68)
    val Text = Color(0xFF352F29)
    val Muted = Color(0xFF716659)
    val Error = Color(0xFFA62D26)
}

private val CardDockColorScheme = lightColorScheme(
    primary = DockColors.Mint,
    onPrimary = Color(0xFFFFF8EA),
    primaryContainer = Color(0xFFF4DDC9),
    onPrimaryContainer = Color(0xFF522D20),
    secondary = DockColors.Blue,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = DockColors.Raised,
    onSecondaryContainer = DockColors.Text,
    tertiary = DockColors.Amber,
    background = DockColors.Background,
    onBackground = DockColors.Text,
    surface = DockColors.Surface,
    onSurface = DockColors.Text,
    // 弹窗、菜单、分段控件和输入框使用同一纸色表面层级。
    surfaceDim = DockColors.Background,
    surfaceBright = DockColors.Raised,
    surfaceContainerLowest = DockColors.Background,
    surfaceContainerLow = DockColors.Surface,
    surfaceContainer = DockColors.Surface,
    surfaceContainerHigh = DockColors.Raised,
    surfaceContainerHighest = DockColors.Raised,
    outlineVariant = DockColors.Outline,
    surfaceVariant = DockColors.Raised,
    onSurfaceVariant = DockColors.Muted,
    outline = DockColors.Outline,
    error = DockColors.Error,
)

/**
 * Warm paper theme; no device-dependent dynamic colors.
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
