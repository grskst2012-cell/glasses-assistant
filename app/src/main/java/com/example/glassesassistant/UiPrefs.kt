package com.example.glassesassistant

import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Настройки внешнего вида. Хранятся в SharedPreferences и сразу применяются в интерфейсе. */
class UiPrefs(private val p: SharedPreferences) {
    var theme by mutableStateOf(p.getString("theme", "system") ?: "system"); private set          // system | light | dark
    var dynamic by mutableStateOf(p.getBoolean("dynamic", true)); private set
    var accent by mutableStateOf(p.getInt("accent", 0)); private set
    var amoled by mutableStateOf(p.getBoolean("amoled", false)); private set
    var fontScale by mutableStateOf(p.getFloat("fontScale", 1.0f)); private set
    var haptics by mutableStateOf(p.getBoolean("haptics", true)); private set
    var uiLang by mutableStateOf(p.getString("uiLang", "system") ?: "system"); private set        // system | ru | en

    fun changeTheme(v: String) { theme = v; p.edit().putString("theme", v).apply() }
    fun changeDynamic(v: Boolean) { dynamic = v; p.edit().putBoolean("dynamic", v).apply() }
    fun changeAccent(v: Int) { accent = v; p.edit().putInt("accent", v).apply() }
    fun changeAmoled(v: Boolean) { amoled = v; p.edit().putBoolean("amoled", v).apply() }
    fun changeFontScale(v: Float) { fontScale = v; p.edit().putFloat("fontScale", v).apply() }
    fun changeHaptics(v: Boolean) { haptics = v; p.edit().putBoolean("haptics", v).apply() }
    fun changeUiLang(v: String) { uiLang = v; p.edit().putString("uiLang", v).apply() }
}

/** Акцентные цвета для режима без динамических цветов: (ресурс названия, тон). */
val accentHues = listOf(
    R.string.accent_blue to 215f,
    R.string.accent_green to 140f,
    R.string.accent_purple to 275f,
    R.string.accent_orange to 28f,
    R.string.accent_red to 355f,
    R.string.accent_teal to 180f,
)

private fun hsl(h: Float, s: Float, l: Float) = Color.hsl(h % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))

/** Схема Material 3 из одного тона: ступени светлоты подобраны вручную (приближение к тональным палитрам). */
fun schemeFromHue(h: Float, dark: Boolean, amoled: Boolean): ColorScheme {
    val t = h + 60f
    if (!dark) return lightColorScheme(
        primary = hsl(h, .55f, .38f), onPrimary = Color.White,
        primaryContainer = hsl(h, .80f, .90f), onPrimaryContainer = hsl(h, .85f, .10f),
        secondary = hsl(h, .22f, .40f), onSecondary = Color.White,
        secondaryContainer = hsl(h, .35f, .90f), onSecondaryContainer = hsl(h, .30f, .12f),
        tertiary = hsl(t, .30f, .40f), onTertiary = Color.White,
        tertiaryContainer = hsl(t, .45f, .90f), onTertiaryContainer = hsl(t, .40f, .12f),
        background = hsl(h, .20f, .98f), onBackground = hsl(h, .15f, .10f),
        surface = hsl(h, .20f, .98f), onSurface = hsl(h, .15f, .10f),
        surfaceVariant = hsl(h, .25f, .90f), onSurfaceVariant = hsl(h, .15f, .30f),
        surfaceContainerLowest = Color.White, surfaceContainerLow = hsl(h, .25f, .96f),
        surfaceContainer = hsl(h, .25f, .94f), surfaceContainerHigh = hsl(h, .22f, .92f),
        surfaceContainerHighest = hsl(h, .20f, .90f),
        outline = hsl(h, .10f, .46f), outlineVariant = hsl(h, .18f, .80f),
    )
    val bg = if (amoled) Color.Black else hsl(h, .12f, .08f)
    return darkColorScheme(
        primary = hsl(h, .85f, .80f), onPrimary = hsl(h, .70f, .15f),
        primaryContainer = hsl(h, .50f, .26f), onPrimaryContainer = hsl(h, .80f, .90f),
        secondary = hsl(h, .30f, .78f), onSecondary = hsl(h, .30f, .18f),
        secondaryContainer = hsl(h, .22f, .27f), onSecondaryContainer = hsl(h, .40f, .90f),
        tertiary = hsl(t, .40f, .78f), onTertiary = hsl(t, .40f, .18f),
        tertiaryContainer = hsl(t, .28f, .27f), onTertiaryContainer = hsl(t, .50f, .90f),
        background = bg, onBackground = hsl(h, .12f, .90f),
        surface = bg, onSurface = hsl(h, .12f, .90f),
        surfaceVariant = hsl(h, .14f, .25f), onSurfaceVariant = hsl(h, .15f, .78f),
        surfaceContainerLowest = if (amoled) Color.Black else hsl(h, .12f, .06f),
        surfaceContainerLow = if (amoled) hsl(h, .10f, .07f) else hsl(h, .12f, .10f),
        surfaceContainer = if (amoled) hsl(h, .10f, .09f) else hsl(h, .12f, .12f),
        surfaceContainerHigh = if (amoled) hsl(h, .10f, .12f) else hsl(h, .12f, .15f),
        surfaceContainerHighest = if (amoled) hsl(h, .10f, .15f) else hsl(h, .12f, .18f),
        outline = hsl(h, .08f, .60f), outlineVariant = hsl(h, .12f, .30f),
    )
}

@Composable
fun AppTheme(ui: UiPrefs, content: @Composable () -> Unit) {
    val dark = when (ui.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    var scheme = when {
        ui.dynamic && dark -> dynamicDarkColorScheme(ctx)
        ui.dynamic -> dynamicLightColorScheme(ctx)
        else -> schemeFromHue(accentHues[ui.accent.coerceIn(0, accentHues.lastIndex)].second, dark, ui.amoled)
    }
    if (ui.dynamic && dark && ui.amoled) scheme = scheme.copy(
        background = Color.Black, surface = Color.Black,
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0A0A0A), surfaceContainer = Color(0xFF111111),
    )
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * ui.fontScale)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
