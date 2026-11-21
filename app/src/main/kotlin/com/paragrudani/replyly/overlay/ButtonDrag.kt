package com.paragrudani.replyly.overlay

import kotlin.math.abs
import kotlin.math.roundToInt

/** What one touch event means for the button. */
sealed interface DragStep {
    /** Finger down: show the pressed look. */
    data object Press : DragStep

    /**
     * The finger moved past the touch slop: from now on this touch is a drag, never a tap. [dy]
     * is the vertical move so far, in px.
     */
    data class DragStart(
        val dy: Int,
    ) : DragStep

    /** Still dragging; [dy] is the total vertical move since the finger went down, in px. */
    data class Drag(
        val dy: Int,
    ) : DragStep

    /** Drag finished (finger up or the gesture was cancelled); [dy] is the total move. */
    data class DragEnd(
        val dy: Int,
    ) : DragStep

    /** Finger up without ever passing the slop: a tap. The only step that may lead to an LLM call. */
    data object Tap : DragStep

    /** Gesture cancelled before it became a drag: nothing happens. */
    data object Cancel : DragStep

    /** Event outside a gesture (move or up without a down). */
    data object Ignore : DragStep
}

/**
 * Tells a tap from a drag. Once the finger moves more than [slopPx] in any direction the touch
 * is a drag for good: moving back to the start point and lifting does not make it a tap.
 * Coordinates are raw screen pixels (the window moves under the finger while dragging).
 */
class DragTracker(
    private val slopPx: Float,
) {
    private var downX = 0f
    private var downY = 0f
    private var lastDy = 0
    private var state = State.IDLE

    private enum class State { IDLE, PRESSED, DRAGGING }

    fun down(
        x: Float,
        y: Float,
    ): DragStep {
        downX = x
        downY = y
        lastDy = 0
        state = State.PRESSED
        return DragStep.Press
    }

    fun move(
        x: Float,
        y: Float,
    ): DragStep =
        when (state) {
            State.IDLE -> {
                DragStep.Ignore
            }

            State.PRESSED -> {
                if (abs(x - downX) > slopPx || abs(y - downY) > slopPx) {
                    state = State.DRAGGING
                    lastDy = (y - downY).roundToInt()
                    DragStep.DragStart(lastDy)
                } else {
                    DragStep.Ignore
                }
            }

            State.DRAGGING -> {
                lastDy = (y - downY).roundToInt()
                DragStep.Drag(lastDy)
            }
        }

    fun up(
        x: Float,
        y: Float,
    ): DragStep {
        val step =
            when (state) {
                State.IDLE -> {
                    DragStep.Ignore
                }

                State.PRESSED -> {
                    if (abs(x - downX) > slopPx ||
                        abs(y - downY) > slopPx
                    ) {
                        DragStep.DragEnd((y - downY).roundToInt())
                    } else {
                        DragStep.Tap
                    }
                }

                State.DRAGGING -> {
                    DragStep.DragEnd((y - downY).roundToInt())
                }
            }
        state = State.IDLE
        return step
    }

    /** The system took the gesture away. A drag keeps the last position it reached. */
    fun cancel(): DragStep {
        val step =
            when (state) {
                State.IDLE -> DragStep.Ignore
                State.PRESSED -> DragStep.Cancel
                State.DRAGGING -> DragStep.DragEnd(lastDy)
            }
        state = State.IDLE
        return step
    }
}
