package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = ImmersiveAccent,
    onPrimary = ImmersiveOnAccent,
    primaryContainer = ImmersiveHighlightContainer,
    onPrimaryContainer = ImmersiveOnHighlightContainer,
    background = ImmersiveBg,
    onBackground = ImmersiveText,
    surface = ImmersiveSurface,
    onSurface = ImmersiveText,
    surfaceVariant = ImmersiveSurfaceVariant,
    onSurfaceVariant = ImmersiveMuted,
    outline = ImmersiveOutline,
    secondary = PurpleGrey80,
    tertiary = Pink80
  )

private val LightColorScheme = DarkColorScheme // Keep it immersive dark for both states

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  // Dynamic color set to false by default to ensure the hand-crafted Immersive UI is rendered perfectly
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      else -> DarkColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
