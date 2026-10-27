package io.github.vdurante.filesplitter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Green used for clean results; Material 3 has no "success" role. */
val SafeGreen = Color(0xFF2E9E4F)

// Navy + periwinkle, matching the launcher icon.
private val Periwinkle = Color(0xFF8AA4F5)
private val Indigo = Color(0xFF3E5BD6)
private val Navy = Color(0xFF14151F)

private val DarkColors = darkColorScheme(
    primary = Periwinkle,
    onPrimary = Color(0xFF10172E),
    primaryContainer = Color(0xFF2A3566),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondaryContainer = Color(0xFF2E3248),
    onSecondaryContainer = Color(0xFFDCE2FF),
    background = Navy,
    surface = Navy,
    surfaceVariant = Color(0xFF2A2D3D),
    surfaceContainerLowest = Color(0xFF0F1018),
    surfaceContainerLow = Color(0xFF1A1C28),
    surfaceContainer = Color(0xFF1E2030),
    surfaceContainerHigh = Color(0xFF242738),
    surfaceContainerHighest = Color(0xFF2A2D3F),
)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF0F1A4D),
    secondaryContainer = Color(0xFFE1E4F5),
    onSecondaryContainer = Color(0xFF1A1E33),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
