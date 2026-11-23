package com.paragrudani.replyly.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * WCAG AA for the brand schemes (light and dark): every text role on the surfaces it is drawn on
 * reaches 4.5:1, outlines and the accent on the button reach 3:1. Dynamic colour comes from the
 * system and is not checked here (docs/DEVICE_CHECKLIST.md).
 */
class ThemeContrastTest {
    private val schemes = listOf("light" to LightColors, "dark" to DarkColors)
    private val extended = mapOf("light" to LightExtendedColors, "dark" to DarkExtendedColors)

    private fun ColorScheme.surfaces(): List<Pair<String, Color>> =
        listOf(
            "background" to background,
            "surface" to surface,
            "surfaceContainerLowest" to surfaceContainerLowest,
            "surfaceContainerLow" to surfaceContainerLow,
            "surfaceContainer" to surfaceContainer,
            "surfaceContainerHigh" to surfaceContainerHigh,
            "surfaceContainerHighest" to surfaceContainerHighest,
            "surfaceVariant" to surfaceVariant,
        )

    @Test
    fun `text on every surface passes AA`() {
        for ((mode, s) in schemes) {
            for ((surfaceName, surface) in s.surfaces()) {
                // Primary and error are used as text too (text buttons, section headers, error lines).
                listOf(
                    "onSurface" to s.onSurface,
                    "onSurfaceVariant" to s.onSurfaceVariant,
                    "primary" to s.primary,
                    "error" to s.error,
                ).forEach { (textName, text) -> assertAa(mode, textName, surfaceName, text, surface, AA_TEXT) }
            }
        }
    }

    @Test
    fun `on-colours pass AA on their containers`() {
        for ((mode, s) in schemes) {
            listOf(
                Triple("onPrimary", s.onPrimary, s.primary),
                Triple("onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer),
                Triple("onSecondary", s.onSecondary, s.secondary),
                Triple("onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
                Triple("onTertiary", s.onTertiary, s.tertiary),
                Triple("onTertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
                Triple("onError", s.onError, s.error),
                Triple("onErrorContainer", s.onErrorContainer, s.errorContainer),
                Triple("onBackground", s.onBackground, s.background),
                Triple("inverseOnSurface", s.inverseOnSurface, s.inverseSurface),
                Triple("inversePrimary", s.inversePrimary, s.inverseSurface),
            ).forEach { (name, fg, bg) -> assertAa(mode, name, "its container", fg, bg, AA_TEXT) }
        }
    }

    @Test
    fun `intent chips pass AA`() {
        for ((mode, colors) in extended) {
            listOf(
                "agree" to colors.agree,
                "decline" to colors.decline,
                "ask" to colors.ask,
                "other" to colors.otherIntent,
                "insight (X)" to colors.insight,
                "humour (X)" to colors.humour,
            ).forEach { (name, pair) -> assertAa(mode, name, "chip", pair.content, pair.container, AA_TEXT) }
        }
    }

    @Test
    fun `outlines and the accent reach 3 to 1`() {
        for ((mode, s) in schemes) {
            assertAa(mode, "outline", "surface", s.outline, s.surface, AA_LARGE_TEXT_AND_UI)
            assertAa(mode, "primary (filled button)", "surface", s.primary, s.surface, AA_LARGE_TEXT_AND_UI)
        }
        // The marigold sparkle sits on the ink icon background.
        assertAa("brand", "marigold", "ink", ReplylyPalette.Marigold, ReplylyPalette.Ink, AA_LARGE_TEXT_AND_UI)
    }

    @Test
    fun `contrast math matches the WCAG reference values`() {
        assertWithMessage("black on white").that(contrastRatio(Color.Black, Color.White)).isWithin(0.01).of(21.0)
        assertWithMessage("same colour").that(contrastRatio(Color.Red, Color.Red)).isWithin(0.001).of(1.0)
        // #767676 on white is the classic 4.54:1 grey.
        assertWithMessage("#767676").that(contrastRatio(Color(0xFF767676), Color.White)).isWithin(0.01).of(4.54)
    }

    private fun assertAa(
        mode: String,
        fgName: String,
        bgName: String,
        fg: Color,
        bg: Color,
        min: Double,
    ) {
        val ratio = contrastRatio(fg, bg)
        val shown = String.format(java.util.Locale.ROOT, "%.2f", ratio)
        assertWithMessage("$mode: $fgName on $bgName = $shown").that(ratio).isAtLeast(min)
    }
}
