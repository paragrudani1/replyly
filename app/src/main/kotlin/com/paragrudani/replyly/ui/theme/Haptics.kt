package com.paragrudani.replyly.ui.theme

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
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
    /**
     * The [VibrationEffect] predefined effect for the view-less path ([Context.performReplylyHaptic]);
     * `null`: the event only plays on a view.
     */
    val predefinedEffect: Int? = null,
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

    /** A shake ended the bubble's snooze (#56). No Replyly view is on screen: plays on the vibrator. */
    SnoozeEnd(HapticFeedbackConstants.CONFIRM, VibrationEffect.EFFECT_CLICK),
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

/**
 * The view-less path (#56): for feedback with no Replyly view on screen (a shake ending the
 * snooze). Plays [HapticEvent.predefinedEffect] on the system vibrator (the `VIBRATE`
 * permission, a normal one). Nothing plays when the system "Touch feedback" setting is off, the
 * event has no predefined effect, or the phone has no vibrator. Returns true if it played.
 */
fun Context.performReplylyHaptic(event: HapticEvent): Boolean {
    val effect = event.predefinedEffect ?: return false
    if (!systemTouchFeedbackEnabled(this)) return false
    val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return false
    if (!vibrator.hasVibrator()) return false
    // Touch usage: the phone's touch vibration intensity applies, as for the view path (review r1 N3).
    vibrator.vibrate(
        VibrationEffect.createPredefined(effect),
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
    )
    return true
}

private fun systemTouchFeedbackEnabled(view: View): Boolean = systemTouchFeedbackEnabled(view.context)

private fun systemTouchFeedbackEnabled(context: Context): Boolean =
    Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
