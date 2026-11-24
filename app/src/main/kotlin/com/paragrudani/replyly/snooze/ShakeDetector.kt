package com.paragrudani.replyly.snooze

import kotlin.math.sqrt

/**
 * Tells a deliberate double shake from ordinary movement (#56), from accelerometer samples
 * (m/s², gravity included). Pure: no sensor, no clock of its own.
 *
 * - A *shake* is a sample whose magnitude is over [thresholdG] times gravity (at rest the phone
 *   reads 1 g). Further samples within [debounceMs] of the last counted shake belong to it.
 * - Two shakes within [windowMs] (start to start) are one event: [onSample] returns true once
 *   and the count starts again.
 */
class ShakeDetector(
    private val thresholdG: Float = THRESHOLD_G,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val windowMs: Long = WINDOW_MS,
) {
    /** When the last shake was counted (also after a trigger, for the debounce). */
    private var lastShakeMs: Long? = null

    /** When the first shake of a pair was counted; `null` when none is waiting for its second. */
    private var firstShakeMs: Long? = null

    /** Feeds one sample; true when it completes a double shake. */
    fun onSample(
        timeMs: Long,
        x: Float,
        y: Float,
        z: Float,
    ): Boolean {
        val g = sqrt(x * x + y * y + z * z) / GRAVITY
        if (g <= thresholdG) return false
        val last = lastShakeMs
        if (last != null && timeMs - last < debounceMs) return false
        lastShakeMs = timeMs
        val first = firstShakeMs
        if (first != null && timeMs - first <= windowMs) {
            firstShakeMs = null
            return true
        }
        firstShakeMs = timeMs
        return false
    }

    /** Forgets any shake so far (the listener stopped). */
    fun reset() {
        lastShakeMs = null
        firstShakeMs = null
    }

    companion object {
        /** Standard gravity, m/s² (`SensorManager.GRAVITY_EARTH`). */
        const val GRAVITY = 9.80665f
        const val THRESHOLD_G = 2.7f
        const val DEBOUNCE_MS = 250L
        const val WINDOW_MS = 1_200L
    }
}
