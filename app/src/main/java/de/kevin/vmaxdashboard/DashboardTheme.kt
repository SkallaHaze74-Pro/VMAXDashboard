package de.kevin.vmaxdashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkBackground = Color(0xFF070A10)
private val DarkSurface = Color(0xFF0D1511)
private val SoftWhite = Color(0xFFE8F0EA)
private val VmaxGreen = Color(0xFF69E77C)

internal val VmaxDarkColorScheme = darkColorScheme(
    primary = VmaxGreen,
    onPrimary = Color(0xFF002108),
    primaryContainer = Color(0xFF154621),
    onPrimaryContainer = Color(0xFFB7F8BF),
    inversePrimary = Color(0xFF1F6E33),
    secondary = Color(0xFFA0D5A7),
    onSecondary = Color(0xFF073914),
    secondaryContainer = Color(0xFF1D3924),
    onSecondaryContainer = Color(0xFFB9F2C0),
    tertiary = Color(0xFF78DCC3),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF005143),
    onTertiaryContainer = Color(0xFF95F4DF),
    background = DarkBackground,
    onBackground = SoftWhite,
    surface = DarkSurface,
    onSurface = SoftWhite,
    surfaceVariant = Color(0xFF17211B),
    onSurfaceVariant = Color(0xFFB4C3B6),
    surfaceTint = VmaxGreen,
    inverseSurface = Color(0xFFDDE5DE),
    inverseOnSurface = Color(0xFF273129),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF7F9083),
    outlineVariant = Color(0xFF344139),
    scrim = Color.Black,
    surfaceBright = Color(0xFF273129),
    surfaceDim = Color(0xFF070A0D),
    surfaceContainer = Color(0xFF0F1712),
    surfaceContainerHigh = Color(0xFF141D17),
    surfaceContainerHighest = Color(0xFF1A251D),
    surfaceContainerLow = Color(0xFF0A100D),
    surfaceContainerLowest = Color(0xFF050806)
)

@Composable
fun VmaxDashboardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VmaxDarkColorScheme,
        content = content
    )
}
