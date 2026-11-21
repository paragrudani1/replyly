package com.paragrudani.replyly.ui.theme

import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The moments where Replyly gives touch feedback. Each maps to exactly one system feedback type
 * ([feedbackConstant]), so the whole app feels the same. Anything not listed gets no haptic.
 */
enum class HapticEvent(
    /** The [HapticFeedbackConstants] value played for this event. */
    val feedbackConstant: Int,
) {
    /** A plain tap on the floating button or a control without its own feedback. */
    Tap(HapticFeedbackConstants.VIRTUAL_KEY),

    /** A suggestion card's text was inserted into the chat field. */
    CardInsert(HapticFeedbackConstants.CONFIRM),

    /** A long-press copied a suggestion to the clipboard. */
    LongPressCopy(HapticFeedbackConstants.LONG_PRESS),

    /** A dragged element (the floating button) snapped to an edge or slot. */
    DragSnap(HapticFeedbackConstants.SEGMENT_TICK),

    /** A switch or toggle turned on. */
    ToggleOn(HapticFeedbackConstants.TOGGLE_ON),

    /** A switch or toggle turned off. */
    ToggleOff(HapticFeedbackConstants.TOGGLE_OFF),

    /** Settings (or a note) were saved. */
    Save(HapticFeedbackConstants.CONFIRM),
    ;

    companion object {
        /** The toggle event for a switch's new state. */
        fun toggle(on: Boolean): HapticEvent = if (on) ToggleOn else ToggleOff
    }
}

/**
 * Plays Replyly's haptics on [view]. Respects the system "Touch feedback" setting
 * (`Settings.System.HAPTIC_FEEDBACK_ENABLED`) and the view's own `isHapticFeedbackEnabled`: when
 * either is off, nothing plays. Never call `View.performHapticFeedback` directly; `DesignRulesTest`
 * enforces it.
 *
 * Compose: `val haptics = rememberReplylyHaptics(); haptics.perform(HapticEvent.CardInsert)`.
 * Views (the floating button): `view.performReplylyHaptic(HapticEvent.Tap)`.
 */
@Immutable
class ReplylyHaptics(
    private val view: View,
) {
    /** Plays [event]'s feedback; returns true if the system played it. */
    fun perform(event: HapticEvent): Boolean = view.performReplylyHaptic(event)
}

/** Compose entry point: haptics bound to the current view. */
@Composable
fun rememberReplylyHaptics(): ReplylyHaptics {
    val view = LocalView.current
    return remember(view) { ReplylyHaptics(view) }
}

/** View entry point (the overlay button). Returns true if the system played the feedback. */
fun View.performReplylyHaptic(event: HapticEvent): Boolean {
    if (!isHapticFeedbackEnabled || !systemTouchFeedbackEnabled(this)) return false
    return performHapticFeedback(event.feedbackConstant)
}

private fun systemTouchFeedbackEnabled(view: View): Boolean =
    Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
