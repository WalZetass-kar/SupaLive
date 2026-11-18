package com.example.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val LocalIsDarkTheme = staticCompositionLocalOf { false }
val LocalReducedMotion = staticCompositionLocalOf { false }

fun Context.isReducedMotionEnabled(): Boolean =
  try {
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
  } catch (_: Exception) {
    false
  }

enum class ThemeMode(val label: String) {
  SYSTEM("System"),
  LIGHT("Light"),
  DARK("Dark"),
}

object ThemePreferences {
  private const val PREFS_NAME = "supalive_ui_prefs"
  private const val KEY_THEME_MODE = "theme_mode"

  fun load(context: Context): ThemeMode {
    val raw =
      context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
    return ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
  }

  fun save(context: Context, mode: ThemeMode) {
    context.applicationContext
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_THEME_MODE, mode.name)
      .apply()
  }
}

private val SupaliveDarkColorScheme =
  darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = Color(0xFF032318),
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = EmeraldBright,
    secondary = CyanAccent,
    onSecondary = Color(0xFF042029),
    secondaryContainer = CyanContainerDark,
    onSecondaryContainer = Color(0xFFA5F3FC),
    tertiary = StatusWarning,
    onTertiary = Color(0xFF2E1B00),
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    outline = ObsidianOutline,
    error = StatusError,
    onError = Color.White,
  )

private val SupaliveLightColorScheme =
  lightColorScheme(
    primary = EmeraldDeep,
    onPrimary = Color.White,
    primaryContainer = EmeraldContainerLight,
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF0E7490),
    onSecondary = Color.White,
    secondaryContainer = CyanContainerLight,
    onSecondaryContainer = Color(0xFF164E63),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    background = DaylightBg,
    onBackground = TextPrimaryLight,
    surface = DaylightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = DaylightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = DaylightOutline,
    error = Color(0xFFDC2626),
    onError = Color.White,
  )

@Composable
fun SupaliveTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  reducedMotion: Boolean? = null,
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val effectiveReducedMotion = reducedMotion ?: remember(context) { context.isReducedMotionEnabled() }
  val colorScheme = if (darkTheme) SupaliveDarkColorScheme else SupaliveLightColorScheme
  CompositionLocalProvider(
    LocalIsDarkTheme provides darkTheme,
    LocalReducedMotion provides effectiveReducedMotion,
  ) {
    MaterialTheme(colorScheme = colorScheme, typography = SupaliveTypography, content = content)
  }
}
