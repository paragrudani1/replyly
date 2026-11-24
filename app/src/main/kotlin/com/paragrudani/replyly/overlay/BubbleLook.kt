package com.paragrudani.replyly.overlay

import com.paragrudani.replyly.settings.ReplylySettings
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the floating button looks like (#55, Settings → Bubble), pure: the settings mapped to
 * what the view draws and how big its window is.
 *
 * - Circle: [OverlayComponent.BUTTON_DP] (44 dp) × [size].
 * - Window (the touch area): circle + [SHADOW_ROOM_DP] (room for the shadow), never below
 *   [MIN_TOUCH_DP] (48 dp, DESIGN.md). About 50 / 56 / 63 / 70 dp for the four sizes.
 * - Alpha: [opacityPercent]; busy dims relative to it ([BUSY_FACTOR]).
 * - Shrunk (idle): the circle at [DOT_SCALE] with the mark hidden; the window keeps its size.
 *
 * Values are normalised on the way in ([from]), so a look always holds a valid step.
 */
data class BubbleLook(
    /** "Show bubble": false hides the button everywhere (`HideReason.TURNED_OFF`). */
    val show: Boolean = true,
    /** One of [ReplylySettings.BUBBLE_SIZES]. */
    val size: Float = ReplylySettings.BUBBLE_SIZE_DEFAULT,
    /** One of [ReplylySettings.BUBBLE_OPACITIES]. */
    val opacityPercent: Int = ReplylySettings.BUBBLE_OPACITY_DEFAULT,
    val shrinkWhenIdle: Boolean = false,
) {
    val circleDp: Float get() = OverlayComponent.BUTTON_DP * size

    val windowDp: Float get() = max(circleDp + SHADOW_ROOM_DP, MIN_TOUCH_DP.toFloat())

    fun circlePx(density: Float): Int = (circleDp * density).roundToInt()

    /** Never below 48 dp in px (rounded up there), and never smaller than the circle plus its shadow room. */
    fun windowPx(density: Float): Int =
        max((windowDp * density).roundToInt(), kotlin.math.ceil(MIN_TOUCH_DP * density).toInt())

    /** The circle's alpha: the opacity, times [BUSY_FACTOR] while a tap is being read. */
    fun alpha(busy: Boolean): Float = opacityPercent / 100f * (if (busy) BUSY_FACTOR else 1f)

    companion object {
        const val SHADOW_ROOM_DP = 12f
        const val MIN_TOUCH_DP = 48

        /** The dot: the circle at half size. */
        const val DOT_SCALE = 0.5f

        const val BUSY_FACTOR = OverlayButtonView.BUSY_ALPHA

        /** Untouched this long after showing (or after the last release), the button shrinks to a dot. */
        const val IDLE_SHRINK_MILLIS = 5_000L

        fun from(settings: ReplylySettings): BubbleLook =
            BubbleLook(
                show = settings.showBubble,
                size = ReplylySettings.bubbleSizeStep(settings.bubbleSize),
                opacityPercent = ReplylySettings.bubbleOpacityStep(settings.bubbleOpacity),
                shrinkWhenIdle = settings.shrinkWhenIdle,
            )
    }
}

/**
 * "Shrink when idle" (#55), pure: when the button becomes a dot and when it grows back.
 *
 * The 5 s timer ([BubbleLook.IDLE_SHRINK_MILLIS]) runs only while every condition holds: the
 * setting is on, the button is shown, it is not busy, not touched (pressed or dragged), and
 * touch exploration (TalkBack) is off. Any condition breaking grows it back at once and stops the
 * timer; all of them holding again starts a fresh 5 s (so a release starts the 5 s again).
 *
 * [schedule] posts an action after a delay and returns a function that cancels it (the view uses
 * its handler; tests use virtual time). [onShrunkChange] is called on every change.
 */
class IdleShrink(
    private val schedule: (delayMillis: Long, action: () -> Unit) -> () -> Unit,
    private val onShrunkChange: (shrunk: Boolean) -> Unit,
) {
    var shrunk = false
        private set

    private var enabled = false
    private var shown = false
    private var busy = false
    private var touching = false
    private var touchExploration = false
    private var cancelTimer: (() -> Unit)? = null

    fun setEnabled(enabled: Boolean) = change { this.enabled = enabled }

    /** The button showed (each show starts at full size) or is going away. */
    fun setShown(shown: Boolean) =
        change(restart = shown) {
            this.shown = shown
            if (shown) setShrunk(false)
        }

    fun setBusy(busy: Boolean) = change { this.busy = busy }

    /** Pressed or dragged: full size while touched; the release starts the 5 s again. */
    fun setTouching(touching: Boolean) = change(restart = !touching && this.touching) { this.touching = touching }

    fun setTouchExploration(on: Boolean) = change { touchExploration = on }

    private val eligible get() = enabled && shown && !busy && !touching && !touchExploration

    private fun change(
        restart: Boolean = false,
        update: () -> Unit,
    ) {
        val before = eligible
        update()
        val now = eligible
        if (!now) {
            stopTimer()
            setShrunk(false)
        } else if (!before || restart) {
            startTimer()
        }
    }

    private fun startTimer() {
        stopTimer()
        cancelTimer =
            schedule(BubbleLook.IDLE_SHRINK_MILLIS) {
                cancelTimer = null
                if (eligible) setShrunk(true)
            }
    }

    private fun stopTimer() {
        cancelTimer?.invoke()
        cancelTimer = null
    }

    private fun setShrunk(value: Boolean) {
        if (shrunk == value) return
        shrunk = value
        onShrunkChange(value)
    }
}
