package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
  primary = DarkAccent,
  onPrimary = Color.White,
  primaryContainer = DarkCard2,
  onPrimaryContainer = DarkFg,
  secondary = DarkAccentVariant,
  onSecondary = Color.White,
  tertiary = SkyAccent,
  background = DarkBg,
  onBackground = DarkFg,
  surface = DarkBg2,
  onSurface = DarkFg,
  surfaceVariant = DarkCard,
  onSurfaceVariant = DarkMuted,
  outline = DarkLine,
  outlineVariant = DarkCard2,
)

private val LightColorScheme = lightColorScheme(
  primary = LightAccent,
  onPrimary = Color.White,
  primaryContainer = LightCard2,
  onPrimaryContainer = LightFg,
  secondary = DarkAccentVariant,
  onSecondary = Color.White,
  tertiary = SkyAccent,
  background = LightBg,
  onBackground = LightFg,
  surface = LightBg2,
  onSurface = LightFg,
  surfaceVariant = LightCard,
  onSurfaceVariant = LightMuted,
  outline = LightLine,
  outlineVariant = LightCard2,
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Default to DAI Dubber dark cinematic theme
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}
