package com.example.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

object ThemePreferences {
  private const val PREFS = "airreceive_prefs"
  private const val KEY_DARK = "use_dark_theme"

  fun isDarkTheme(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DARK, true)

  fun setDarkTheme(context: Context, dark: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DARK, dark).apply()
  }
}

@Composable
fun rememberDarkThemePreference(): Pair<Boolean, (Boolean) -> Unit> {
  val context = LocalContext.current
  var dark by rememberSaveable { mutableStateOf(ThemePreferences.isDarkTheme(context)) }
  val setter: (Boolean) -> Unit = { value ->
    dark = value
    ThemePreferences.setDarkTheme(context, value)
  }
  return dark to setter
}
