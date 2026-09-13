package dev.sam.wearsignal.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/**
 * Signal brand design palette.
 * Based on Signal's official design system (Ultramarine primary and dark AMOLED surfaces).
 */
val SignalUltramarine = Color(0xFF2C6BED)
val SignalUltramarineDark = Color(0xFF1B4DB3)
val SignalUltramarineLight = Color(0xFF3A76F0)
val SignalUltramarineTint = Color(0xFF1E3E7A)

val SignalBackground = Color(0xFF000000)
val SignalSurface = Color(0xFF1C1C1E)
val SignalSurfaceVariant = Color(0xFF2C2C2E)
val SignalOnSurfaceVariant = Color(0xFF8E8E93)

val SignalRed = Color(0xFFFF5B5B)

val SignalColors = Colors(
  primary = SignalUltramarine,
  primaryVariant = SignalUltramarineDark,
  secondary = SignalUltramarineLight,
  secondaryVariant = SignalUltramarineTint,
  background = SignalBackground,
  surface = SignalSurface,
  error = SignalRed,
  onPrimary = Color.White,
  onSecondary = Color.White,
  onBackground = Color.White,
  onSurface = Color.White,
  onSurfaceVariant = SignalOnSurfaceVariant,
  onError = Color.White
)

@Composable
fun WearSignalTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colors = SignalColors,
    content = content
  )
}
