package com.life0423.carddock.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Named palette values keep presentation decisions out of business code. */
object DockColors {
    val Background = Color(0xFF08131F)
    val Surface = Color(0xFF122333)
    val Raised = Color(0xFF1B3042)
    val Outline = Color(0xFF2C4354)
    val Mint = Color(0xFF8BE5CF)
    val Amber = Color(0xFFFFCD85)
    val Blue = Color(0xFF9FC8FF)
    val Text = Color(0xFFF0F7FA)
    val Muted = Color(0xFFA1B4C3)
    val Error = Color(0xFFFFB4AD)
}

private val CardDockColorScheme = darkColorScheme(
    primary = DockColors.Mint,
    onPrimary = Color(0xFF073A30),
    primaryContainer = Color(0xFF214C43),
    onPrimaryContainer = Color(0xFFC7F8EA),
    secondary = DockColors.Blue,
    onSecondary = Color(0xFF15354F),
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

/** A deliberate dark prototype theme; no device-dependent dynamic colors. */
@Composable
fun CardDockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CardDockColorScheme,
        typography = Typography(),
        content = content,
    )
}
