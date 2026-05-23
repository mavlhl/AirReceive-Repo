package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val MacDarkColorScheme =
  darkColorScheme(
    primary = MacSystemBlue,
    onPrimary = MacOnBlue,
    primaryContainer = MacBlueContainer,
    onPrimaryContainer = MacLabelPrimary,
    background = MacContentBg,
    onBackground = MacLabelPrimary,
    surface = MacSecondaryBg,
    onSurface = MacLabelPrimary,
    surfaceVariant = MacTertiaryBg,
    onSurfaceVariant = MacLabelSecondary,
    outline = MacSeparator,
    error = MacSystemRed,
    onError = MacOnBlue,
    secondary = MacLabelSecondary,
    tertiary = MacSystemOrange,
  )

private val MacLightColorScheme =
  lightColorScheme(
    primary = MacSystemBlue,
    onPrimary = MacOnBlue,
    primaryContainer = MacSystemBlue.copy(alpha = 0.12f),
    onPrimaryContainer = MacLightLabelPrimary,
    background = MacLightContentBg,
    onBackground = MacLightLabelPrimary,
    surface = MacLightSecondaryBg,
    onSurface = MacLightLabelPrimary,
    surfaceVariant = MacLightTertiaryBg,
    onSurfaceVariant = MacLightLabelSecondary,
    outline = MacLightSeparator,
    error = MacSystemRed,
    onError = MacOnBlue,
    secondary = MacLightLabelSecondary,
    tertiary = MacSystemOrange,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> MacDarkColorScheme
      else -> MacLightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
