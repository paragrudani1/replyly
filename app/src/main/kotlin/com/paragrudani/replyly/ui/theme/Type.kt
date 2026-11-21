package com.paragrudani.replyly.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.paragrudani.replyly.R

/**
 * Figtree (SIL OFL, `third_party/figtree/OFL.txt`): a friendly, open geometric sans that stays
 * clear at 11 sp. Bundled, so Samsung's system font cannot change the look and screenshot tests
 * render the same glyphs everywhere. Devanagari and emoji fall back to the system fonts.
 */
val Figtree =
    FontFamily(
        Font(R.font.figtree_regular, FontWeight.Normal),
        Font(R.font.figtree_medium, FontWeight.Medium),
        Font(R.font.figtree_semibold, FontWeight.SemiBold),
        Font(R.font.figtree_bold, FontWeight.Bold),
    )

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = Figtree,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.em,
)

/**
 * The type scale (sp): 11, 12, 13, 14, 15, 16, 22, 24, 28, 32, 36 (display 45 and 57 are unused). Regular for reading,
 * Medium for labels, SemiBold for titles; headlines are tracked slightly tight. bodyMedium is
 * 15 sp (not Material's 14) because suggestion text is the product and must read easily.
 */
val ReplylyTypography =
    Typography(
        displayLarge = style(57, 64, FontWeight.SemiBold, -0.02),
        displayMedium = style(45, 52, FontWeight.SemiBold, -0.02),
        displaySmall = style(36, 44, FontWeight.SemiBold, -0.02),
        headlineLarge = style(32, 40, FontWeight.SemiBold, -0.015),
        headlineMedium = style(28, 36, FontWeight.SemiBold, -0.01),
        headlineSmall = style(24, 32, FontWeight.SemiBold, -0.01),
        titleLarge = style(22, 28, FontWeight.SemiBold),
        titleMedium = style(16, 24, FontWeight.SemiBold),
        titleSmall = style(14, 20, FontWeight.SemiBold),
        bodyLarge = style(16, 24, FontWeight.Normal),
        bodyMedium = style(15, 22, FontWeight.Normal),
        bodySmall = style(13, 18, FontWeight.Normal),
        labelLarge = style(14, 20, FontWeight.Medium, 0.005),
        labelMedium = style(12, 16, FontWeight.Medium, 0.01),
        labelSmall = style(11, 16, FontWeight.Medium, 0.02),
    )
