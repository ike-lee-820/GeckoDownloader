package com.ikelee.geckodl.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val SeedLight = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFF455A64),
    background = Color(0xFFFCFDF8),
    surface = Color(0xFFFCFDF8)
)

private val SeedDark = darkColorScheme(
    primary = Color(0xFFA5D6A7),
    onPrimary = Color(0xFF003910),
    primaryContainer = Color(0xFF1B5E20),
    secondary = Color(0xFFB0BEC5),
    background = Color(0xFF191C19),
    surface = Color(0xFF191C19)
)

/**
 * Material You: dynamic colour on Android 12+ when enabled, otherwise the fixed seed scheme.
 * darkMode is one of system, light, dark.
 */
@Composable
fun GdlTheme(
    dynamicColor: Boolean,
    darkMode: String,
    content: @Composable () -> Unit
) {
    val dark = when (darkMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> SeedDark
        else -> SeedLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
