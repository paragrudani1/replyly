package com.paragrudani.replyly.overlay

import kotlin.math.hypot

/**
 * The drag-to-snooze target's rules (#56), pure. All positions are screen pixels; [density]
 * turns the dp constants into pixels.
 *
 * - **Reveal**: once the drag has lasted [REVEAL_MS], or earlier once the finger is at least
 *   [EARLY_DOWN_DP] below where the drag started after at least [EARLY_MIN_MS]. A quick flick
 *   ends before either, so the target never flashes.
 * - **Hit**: the finger (not the clamped button window) within [hitRadiusPx] = the drawn radius
 *   plus slack of the target's centre, and only while the target shows.
 */
class DismissTargetRule(
    val density: Float,
) {
    /** The drawn circle's radius. */
    val radiusPx: Float = RADIUS_DP * density

    /** How far from the centre a finger still counts as on the target. */
    val hitRadiusPx: Float = (RADIUS_DP + SLACK_DP) * density

    fun reveals(
        elapsedMs: Long,
        downPx: Float,
    ): Boolean = elapsedMs >= REVEAL_MS || (elapsedMs >= EARLY_MIN_MS && downPx >= EARLY_DOWN_DP * density)

    fun hits(
        x: Float,
        y: Float,
        target: Center,
    ): Boolean = hypot(x - target.x, y - target.y) <= hitRadiusPx

    /** The target circle's centre on screen. */
    data class Center(
        val x: Float,
        val y: Float,
    )

    companion object {
        const val REVEAL_MS = 300L
        const val EARLY_MIN_MS = 120L
        const val EARLY_DOWN_DP = 72f

        /** The circle is 64 dp across. */
        const val RADIUS_DP = 32f

        /** A finger a little outside the drawn circle still counts (the button covers the view). */
        const val SLACK_DP = 16f
    }
}

/**
 * One drag's state against the target: reveal stays on once reached (monotonic), and
 * [Update.entered] is true on the move that brings the finger onto the target, so the haptic
 * plays once per entry.
 */
class DismissTargetDrag(
    private val rule: DismissTargetRule,
    /** The finger's y where the drag started (only "how far down" matters for the reveal). */
    private val startY: Float,
    private val target: DismissTargetRule.Center,
) {
    private var revealed = false
    private var inside = false

    data class Update(
        val revealed: Boolean,
        val inside: Boolean,
        val entered: Boolean,
    )

    /** The finger is at ([x], [y]) [elapsedMs] after the drag started. */
    fun onMove(
        elapsedMs: Long,
        x: Float,
        y: Float,
    ): Update {
        revealed = revealed || rule.reveals(elapsedMs, y - startY)
        val nowInside = revealed && rule.hits(x, y, target)
        val entered = nowInside && !inside
        inside = nowInside
        return Update(revealed, inside, entered)
    }

    /** True when the finger was on the shown target at its last move: the release snoozes. */
    fun releasedInside(): Boolean = inside
}
