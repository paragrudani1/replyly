package com.paragrudani.replyly.snooze

import kotlin.math.sqrt

/**
 * Tells a deliberate double shake from ordinary movement (#56), from accelerometer samples
 * (m/s², gravity included). Pure: no sensor, no clock of its own.
 *
 * - A sample is *over* when its magnitude is over [thresholdG] times gravity (at rest the phone
 *   reads 1 g).
 * - A *shake* is a burst of over samples: each one less than [quietMs] after the previous over
 *   sample belongs to the same shake, however long it lasts. One real shake of the hand is an
 *   oscillation with a peak every 80–170 ms, so it stays one shake. A new shake starts only after
 *   at least [quietMs] below the threshold.
 * - A shake that starts at most [windowMs] after the previous shake ended completes a double
 *   shake: [onSample] returns true once and the count starts again (the rest of that burst is
 *   still the same shake, so it never counts as the first of the next pair).
 */
class ShakeDetector(
    private val thresholdG: Float = THRESHOLD_G,
    private val quietMs: Long = QUIET_MS,
    private val windowMs: Long = WINDOW_MS,
) {
    /** The last over sample: the end so far of the current (or last) shake. */
    private var lastOverMs: Long? = null

    /** The last shake is the first of a pair, waiting for its second. */
    private var waiting = false

    /** Feeds one sample; true when it completes a double shake. */
    fun onSample(
        timeMs: Long,
        x: Float,
        y: Float,
        z: Float,
    ): Boolean {
        val g = sqrt(x * x + y * y + z * z) / GRAVITY
        if (g <= thresholdG) return false
        val last = lastOverMs
        lastOverMs = timeMs
        if (last != null && timeMs - last < quietMs) return false
        // A new shake starts.
        if (waiting && last != null && timeMs - last <= windowMs) {
            waiting = false
            return true
        }
        waiting = true
        return false
    }

    /** Forgets any shake so far (the listener stopped). */
    fun reset() {
        lastOverMs = null
        waiting = false
    }

    companion object {
        /** Standard gravity, m/s² (`SensorManager.GRAVITY_EARTH`). */
        const val GRAVITY = 9.80665f
        const val THRESHOLD_G = 2.7f

        /** Below the threshold at least this long between two shakes. */
        const val QUIET_MS = 250L

        /** From the end of the first shake to the start of the second, at most. */
        const val WINDOW_MS = 1_200L
    }
}
