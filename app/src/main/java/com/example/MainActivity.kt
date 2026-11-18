package com.example

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.ui.SupaliveApp
import com.example.ui.theme.SupaliveTheme
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.ThemePreferences

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val initialMode = ThemePreferences.load(this)
    val systemDark =
      (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val initialDark =
      when (initialMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
      }
    enableEdgeToEdge(
      statusBarStyle = barStyleFor(initialDark),
      navigationBarStyle = barStyleFor(initialDark),
    )

    setContent {
      var themeMode by remember { mutableStateOf(initialMode) }
      val darkTheme =
        when (themeMode) {
          ThemeMode.SYSTEM -> isSystemInDarkTheme()
          ThemeMode.LIGHT -> false
          ThemeMode.DARK -> true
        }
      var appliedBarDark by remember { mutableStateOf(initialDark) }

      LaunchedEffect(darkTheme) {
        if (darkTheme != appliedBarDark) {
          val style = barStyleFor(darkTheme)
          enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
          appliedBarDark = darkTheme
        }
      }

      SupaliveTheme(darkTheme = darkTheme) {
        SupaliveApp(
          themeMode = themeMode,
          isDarkTheme = darkTheme,
          onThemeModeChange = { nextMode ->
            themeMode = nextMode
            ThemePreferences.save(this@MainActivity, nextMode)
          },
        )
      }
    }
  }
}

private fun barStyleFor(darkTheme: Boolean): SystemBarStyle =
  if (darkTheme) {
    SystemBarStyle.dark(Color.TRANSPARENT)
  } else {
    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
  }
