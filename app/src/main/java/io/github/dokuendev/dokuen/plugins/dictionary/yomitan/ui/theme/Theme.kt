package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = NeonTeal,
    onPrimary = MidnightDark,
    secondary = CyanAccent,
    onSecondary = TextWhite,
    background = MidnightDark,
    onBackground = TextWhite,
    surface = GlassSurface,
    onSurface = TextWhite,
    error = NeonRed,
    onError = TextWhite
)

@Composable
fun YomitanTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
