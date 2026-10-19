package it.iotatec.morsegram.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

val Amber = Color(0xFFF5A623)
val AmberDark = Color(0xFFC97F00)
val Ink = Color(0xFF1D1D1F)
val Surface = Color(0xFF202124)
val OnSurface = Color(0xFFF2F2F2)
val Muted = Color(0xFF9AA0A6)

private val MorsegramColors = Colors(
    primary = Amber,
    primaryVariant = AmberDark,
    secondary = Amber,
    secondaryVariant = AmberDark,
    background = Color.Black,
    surface = Surface,
    error = Color(0xFFFF5449),
    onPrimary = Ink,
    onSecondary = Ink,
    onBackground = OnSurface,
    onSurface = OnSurface,
    onSurfaceVariant = Muted,
    onError = Color.White,
)

@Composable
fun MorsegramTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = MorsegramColors, content = content)
}
