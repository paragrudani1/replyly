package com.paragrudani.replyly.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The brand palette. Screens never use these directly: read `MaterialTheme.colorScheme` (roles)
 * or [ReplylyTheme.colors] (intents, accent), so dark mode and dynamic colour keep working.
 * Every text pair is checked for WCAG AA in `ThemeContrastTest`.
 */
object ReplylyPalette {
    /** Brand colour: a calm, deep ink blue. Fills the button, the icon and primary actions. */
    val Ink = Color(0xFF2D3E9A)

    /** The one warm accent: the sparkle in the icon and the wordmark. Use sparingly. */
    val Marigold = Color(0xFFF5B83D)

    /** Off-white with a hint of ink, the light page colour. */
    val Paper = Color(0xFFFAFAFD)

    /** Ink-tinted dark page colour (not pure black). */
    val Night = Color(0xFF121318)
}

internal val LightColors: ColorScheme =
    lightColorScheme(
        primary = ReplylyPalette.Ink,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDDE1FF),
        onPrimaryContainer = Color(0xFF0E1A5C),
        inversePrimary = Color(0xFFB9C3FF),
        secondary = Color(0xFF575C78),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDFE1F9),
        onSecondaryContainer = Color(0xFF151A33),
        tertiary = Color(0xFF7A5200),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDEA6),
        onTertiaryContainer = Color(0xFF281800),
        background = ReplylyPalette.Paper,
        onBackground = Color(0xFF1A1B23),
        surface = ReplylyPalette.Paper,
        onSurface = Color(0xFF1A1B23),
        surfaceVariant = Color(0xFFE2E2EC),
        onSurfaceVariant = Color(0xFF45464F),
        surfaceTint = ReplylyPalette.Ink,
        inverseSurface = Color(0xFF2F3038),
        inverseOnSurface = Color(0xFFF1F0F7),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF767680),
        outlineVariant = Color(0xFFC6C6D0),
        scrim = Color(0xFF000000),
        surfaceBright = ReplylyPalette.Paper,
        surfaceDim = Color(0xFFDAD9E0),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF4F4F9),
        surfaceContainer = Color(0xFFEEEEF4),
        surfaceContainerHigh = Color(0xFFE8E8EF),
        surfaceContainerHighest = Color(0xFFE2E2E9),
    )

internal val DarkColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFFB9C3FF),
        onPrimary = Color(0xFF14246E),
        primaryContainer = Color(0xFF2D3E9A),
        onPrimaryContainer = Color(0xFFDDE1FF),
        inversePrimary = ReplylyPalette.Ink,
        secondary = Color(0xFFC3C5DD),
        onSecondary = Color(0xFF2C2F42),
        secondaryContainer = Color(0xFF43465A),
        onSecondaryContainer = Color(0xFFDFE1F9),
        tertiary = Color(0xFFF5BD48),
        onTertiary = Color(0xFF412D00),
        tertiaryContainer = Color(0xFF5D4100),
        onTertiaryContainer = Color(0xFFFFDEA6),
        background = ReplylyPalette.Night,
        onBackground = Color(0xFFE3E1EA),
        surface = ReplylyPalette.Night,
        onSurface = Color(0xFFE3E1EA),
        surfaceVariant = Color(0xFF45464F),
        onSurfaceVariant = Color(0xFFC6C5D0),
        surfaceTint = Color(0xFFB9C3FF),
        inverseSurface = Color(0xFFE3E1EA),
        inverseOnSurface = Color(0xFF2F3038),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF90909A),
        outlineVariant = Color(0xFF45464F),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF38393F),
        surfaceDim = ReplylyPalette.Night,
        surfaceContainerLowest = Color(0xFF0D0E13),
        surfaceContainerLow = Color(0xFF1A1B21),
        surfaceContainer = Color(0xFF1E1F25),
        surfaceContainerHigh = Color(0xFF292A30),
        surfaceContainerHighest = Color(0xFF34343B),
    )

/** A container colour and the text/icon colour that goes on it (AA checked). */
@Immutable
data class ColorPair(
    val container: Color,
    val content: Color,
)

/**
 * Colours Material has no role for. Read them with [ReplylyTheme.colors].
 *
 * @property accent the marigold sparkle (or the wallpaper's tertiary with dynamic colour).
 *   Decoration only: never as a text colour on a light surface.
 * @property agree,decline,ask the suggestion intent chips; [forIntent] maps an intent name.
 */
@Immutable
data class ReplylyColors(
    val accent: Color,
    val agree: ColorPair,
    val decline: ColorPair,
    val ask: ColorPair,
    /** Any other intent: a quiet neutral chip. */
    val otherIntent: ColorPair,
    /** The panel's dimmed backdrop over the chat. */
    val backdrop: Color,
) {
    /** The chip colours for a suggestion's intent ("agree", "decline", "ask", anything else). */
    fun forIntent(intent: String): ColorPair =
        when (intent.trim().lowercase()) {
            "agree" -> agree
            "decline" -> decline
            "ask" -> ask
            else -> otherIntent
        }
}

internal val LightExtendedColors =
    ReplylyColors(
        accent = ReplylyPalette.Marigold,
        agree = ColorPair(container = Color(0xFFD5F0DC), content = Color(0xFF0E5127)),
        decline = ColorPair(container = Color(0xFFFFDAD4), content = Color(0xFF7A2318)),
        ask = ColorPair(container = Color(0xFFFFE2B0), content = Color(0xFF5C3D00)),
        otherIntent = ColorPair(container = LightColors.secondaryContainer, content = LightColors.onSecondaryContainer),
        backdrop = Color(0x52000000),
    )

internal val DarkExtendedColors =
    ReplylyColors(
        accent = ReplylyPalette.Marigold,
        agree = ColorPair(container = Color(0xFF1D4A2B), content = Color(0xFFB4EEC2)),
        decline = ColorPair(container = Color(0xFF6B2A20), content = Color(0xFFFFDAD4)),
        ask = ColorPair(container = Color(0xFF5D4100), content = Color(0xFFFFDEA6)),
        otherIntent = ColorPair(container = DarkColors.secondaryContainer, content = DarkColors.onSecondaryContainer),
        backdrop = Color(0x80000000),
    )

/**
 * The Material colour scheme for a surface. Not composable, so View-based overlay code (the
 * floating button) gets the same colours as Compose screens.
 *
 * @param dynamicColor true uses the wallpaper colours (Material You); the default is the brand.
 */
fun replylyColorScheme(
    context: Context,
    darkTheme: Boolean,
    dynamicColor: Boolean,
): ColorScheme =
    when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

/** Brand scheme without a Context (tests, previews). */
fun replylyBrandColorScheme(darkTheme: Boolean): ColorScheme = if (darkTheme) DarkColors else LightColors

internal fun replylyExtendedColors(
    scheme: ColorScheme,
    darkTheme: Boolean,
    dynamicColor: Boolean,
): ReplylyColors {
    val base = if (darkTheme) DarkExtendedColors else LightExtendedColors
    if (!dynamicColor) return base
    return base.copy(
        accent = scheme.tertiary,
        otherIntent = ColorPair(scheme.secondaryContainer, scheme.onSecondaryContainer),
    )
}
