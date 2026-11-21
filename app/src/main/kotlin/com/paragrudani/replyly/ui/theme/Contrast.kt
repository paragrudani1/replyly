package com.paragrudani.replyly.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x contrast ratio between two opaque colours, 1.0 to 21.0. AA needs 4.5 for body text,
 * 3.0 for large text (18 sp, or 14 sp bold) and for UI parts such as outlines and icons.
 */
fun contrastRatio(
    foreground: Color,
    background: Color,
): Double {
    val a = relativeLuminance(foreground)
    val b = relativeLuminance(background)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

/** WCAG relative luminance of the sRGB colour (alpha ignored). Pure math, safe on the JVM. */
fun relativeLuminance(color: Color): Double {
    fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

const val AA_TEXT = 4.5
const val AA_LARGE_TEXT_AND_UI = 3.0
