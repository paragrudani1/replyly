package com.paragrudani.replyly.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Smallest touch target (Android and WCAG guidance). */
val MinTouchTarget: Dp = 48.dp

/**
 * Makes a custom tappable element at least 48 x 48 dp, with its content centred. Put it AFTER
 * `clickable` / `combinedClickable`, so the click area (and the TalkBack focus box) is the full
 * 48 dp while the visual stays its own size:
 *
 * ```
 * Icon(painter, "Copy", Modifier.clickable(onClickLabel = "Copy reply") { ... }.minTouchTarget())
 * ```
 *
 * Material buttons, chips, switches and `IconButton` already do this; use it for anything you
 * make clickable yourself.
 */
fun Modifier.minTouchTarget(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val min = MinTouchTarget.roundToPx()
        val width = maxOf(placeable.width, min).coerceAtMost(constraints.maxWidth)
        val height = maxOf(placeable.height, min).coerceAtMost(constraints.maxHeight)
        layout(width, height) {
            placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
        }
    }
