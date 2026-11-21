package com.paragrudani.replyly.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Spacing on a 4 dp grid. Use these instead of raw dp in layouts:
 * 4 inside a component, 8 between related items, 12 card padding (dense), 16 card padding
 * and between cards, 24 screen edges and between sections, 32+ for breathing room.
 */
object Spacing {
    val XXS = 2.dp
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
    val XXL = 32.dp
    val XXXL = 48.dp
}

/**
 * Corner radii. Bigger things get rounder corners: chips 10, cards 16, sheets 28, buttons are
 * full pills (Material's default). `MaterialTheme.shapes` maps to these.
 */
object Radii {
    val XS = 6.dp
    val S = 10.dp
    val M = 16.dp
    val L = 24.dp
    val XL = 28.dp
}

/**
 * Elevation. Calm surfaces separate by tone, not by shadow: cards sit flat on a container colour.
 * Shadows only for things that float over another app (the button, the panel sheet).
 */
object Elevation {
    val Flat = 0.dp

    /** Cards and grouped sections (tonal only). */
    val Raised = 1.dp

    /** The save bar and other bars pinned over content. */
    val Bar = 3.dp

    /** The panel sheet over a chat. */
    val Sheet = 6.dp

    /** The floating button over another app. */
    val Floating = 8.dp
}

internal val ReplylyShapes =
    Shapes(
        extraSmall = RoundedCornerShape(Radii.XS),
        small = RoundedCornerShape(Radii.S),
        medium = RoundedCornerShape(Radii.M),
        large = RoundedCornerShape(Radii.L),
        extraLarge = RoundedCornerShape(Radii.XL),
    )

/** Top-rounded sheet shape for bottom sheets (the panel). */
val SheetShape = RoundedCornerShape(topStart = Radii.XL, topEnd = Radii.XL)
