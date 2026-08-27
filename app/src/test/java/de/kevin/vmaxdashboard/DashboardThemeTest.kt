package de.kevin.vmaxdashboard

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardThemeTest {

    @Test
    fun `palette uses dark surfaces soft white text and green accents`() {
        assertEquals(Color(0xFF070A10), VmaxDarkColorScheme.background)
        assertEquals(Color(0xFF0D1511), VmaxDarkColorScheme.surface)
        assertEquals(Color(0xFFE8F0EA), VmaxDarkColorScheme.onBackground)
        assertEquals(Color(0xFFE8F0EA), VmaxDarkColorScheme.onSurface)
        assertEquals(Color(0xFF69E77C), VmaxDarkColorScheme.primary)
        assertEquals(Color(0xFF002108), VmaxDarkColorScheme.onPrimary)
        assertTrue("Text should be soft white rather than full white", VmaxDarkColorScheme.onSurface != Color.White)
    }

    @Test
    fun `every Material surface role remains dark`() {
        val surfaces = VmaxDarkColorScheme.run {
            listOf(
                background,
                surface,
                surfaceVariant,
                surfaceDim,
                surfaceBright,
                surfaceContainerLowest,
                surfaceContainerLow,
                surfaceContainer,
                surfaceContainerHigh,
                surfaceContainerHighest
            )
        }

        surfaces.forEach { color ->
            assertTrue(
                "Expected a dark surface but luminance was ${relativeLuminance(color)}",
                relativeLuminance(color) < 0.08
            )
        }
    }

    @Test
    fun `text and accent pairs meet normal text contrast`() {
        assertReadablePairs(
            VmaxDarkColorScheme,
            minimumContrast = 4.5
        )
    }

    private fun assertReadablePairs(scheme: ColorScheme, minimumContrast: Double) {
        val pairs = scheme.run {
            listOf(
                onBackground to background,
                onSurface to surface,
                onSurfaceVariant to surfaceVariant,
                primary to background,
                onPrimary to primary,
                onPrimaryContainer to primaryContainer,
                onSecondary to secondary,
                onSecondaryContainer to secondaryContainer,
                onError to error,
                onErrorContainer to errorContainer
            )
        }

        pairs.forEach { (foreground, background) ->
            val contrast = contrastRatio(foreground, background)
            assertTrue(
                "Expected contrast >= $minimumContrast but was $contrast",
                contrast >= minimumContrast
            )
        }
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val lighter = max(relativeLuminance(first), relativeLuminance(second))
        val darker = min(relativeLuminance(first), relativeLuminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) {
                value / 12.92
            } else {
                Math.pow((value + 0.055) / 1.055, 2.4)
            }
        }

        return 0.2126 * linear(color.red) +
            0.7152 * linear(color.green) +
            0.0722 * linear(color.blue)
    }
}
