package com.paragrudani.replyly.snooze

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Seeded property test over random accelerometer streams (#56).
 *
 * Invariants:
 * 1. sub-threshold noise (any direction, any rate, magnitude below 2.7 g) never triggers;
 * 2. two peaks 250 ms to 1.2 s apart in such noise trigger exactly once, on the second peak;
 * 3. two peaks more than 1.2 s apart (1.3 s and up from the end of the first) never trigger;
 * 4. one natural shake of the hand, an oscillation of 200–900 ms (3–6 Hz, peaks of 3–4.5 g,
 *    sampled every 18–22 ms, across gravity), never triggers (review r1 S2);
 * 5. two such shakes with a still pause of 300–700 ms trigger exactly once.
 */
class ShakeDetectorPropertyTest {
    private val random = Random(SEED)

    /** A random vector of magnitude [g] gravities. */
    private fun vector(g: Float): Triple<Float, Float, Float> {
        val x = random.nextFloat() * 2 - 1
        val y = random.nextFloat() * 2 - 1
        val z = random.nextFloat() * 2 - 1
        val norm = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-3f)
        val scale = g * ShakeDetector.GRAVITY / norm
        return Triple(x * scale, y * scale, z * scale)
    }

    private fun ShakeDetector.feed(
        timeMs: Long,
        g: Float,
    ): Boolean {
        val (x, y, z) = vector(g)
        return onSample(timeMs, x, y, z)
    }

    /** Noise samples from [from] to [to] (exclusive), every 5..60 ms, below the threshold; returns triggers. */
    private fun ShakeDetector.noise(
        from: Long,
        to: Long,
    ): Int {
        var t = from
        var triggers = 0
        while (t < to) {
            if (feed(t, random.nextFloat() * 2.65f)) triggers++
            t += random.nextLong(5, 61)
        }
        return triggers
    }

    @Test
    fun `sub-threshold noise never triggers`() {
        repeat(RUNS) {
            val detector = ShakeDetector()
            val triggers = detector.noise(0, random.nextLong(1_000, 20_000))
            assertWithMessage("run $it").that(triggers).isEqualTo(0)
        }
    }

    @Test
    fun `two peaks within 1_2 s trigger exactly once`() {
        repeat(RUNS) {
            val detector = ShakeDetector()
            val first = random.nextLong(0, 5_000)
            val gap = random.nextLong(ShakeDetector.QUIET_MS, ShakeDetector.WINDOW_MS + 1)
            var triggers = detector.noise(0, first)
            if (detector.feed(first, random.nextFloat() * 3 + 2.75f)) triggers++
            triggers += detector.noise(first + 1, first + gap)
            val second = detector.feed(first + gap, random.nextFloat() * 3 + 2.75f)
            if (second) triggers++
            triggers += detector.noise(first + gap + 1, first + gap + 3_000)
            assertWithMessage("run $it gap=$gap").that(second).isTrue()
            assertWithMessage("run $it gap=$gap").that(triggers).isEqualTo(1)
        }
    }

    @Test
    fun `peaks 1_3 s or more apart never trigger`() {
        repeat(RUNS) {
            val detector = ShakeDetector()
            var t = random.nextLong(0, 2_000)
            var triggers = 0
            repeat(random.nextInt(2, 6)) {
                // A short burst of over-threshold samples is one peak.
                val burst = random.nextLong(0, ShakeDetector.QUIET_MS)
                var s = t
                while (s <= t + burst) {
                    if (detector.feed(s, random.nextFloat() * 3 + 2.75f)) triggers++
                    s += random.nextLong(5, 40)
                }
                // 1.3 s or more after the burst's last over-threshold sample.
                val next = s + random.nextLong(1_300, 4_000)
                triggers += detector.noise(s, next)
                t = next
            }
            assertWithMessage("run $it").that(triggers).isEqualTo(0)
        }
    }

    /**
     * One shake of the hand from [from]: a sinusoid at 3–6 Hz with a peak of 3–4.5 g along a random
     * axis across gravity (gravity on z), lasting [durationMs], sampled every 18–22 ms. Returns
     * the triggers and the time after the last sample.
     */
    private fun ShakeDetector.oscillate(
        from: Long,
        durationMs: Long,
    ): Pair<Int, Long> {
        val hz = 3.0 + random.nextDouble() * 3.0
        val peakG = 3.0 + random.nextDouble() * 1.5
        val angle = random.nextDouble() * 2 * PI
        var t = 0L
        var triggers = 0
        while (t <= durationMs) {
            val a = peakG * sin(2 * PI * hz * t / 1_000.0) * ShakeDetector.GRAVITY
            val x = (a * cos(angle)).toFloat()
            val y = (a * sin(angle)).toFloat()
            if (onSample(from + t, x, y, ShakeDetector.GRAVITY)) triggers++
            t += random.nextLong(18, 23)
        }
        return triggers to from + t
    }

    /** The phone held still (1 g on z) from [from] to [to], every 18–22 ms. */
    private fun ShakeDetector.still(
        from: Long,
        to: Long,
    ): Int {
        var t = from
        var triggers = 0
        while (t < to) {
            if (onSample(t, 0f, 0f, ShakeDetector.GRAVITY)) triggers++
            t += random.nextLong(18, 23)
        }
        return triggers
    }

    @Test
    fun `one oscillating shake never triggers`() {
        repeat(RUNS) {
            val detector = ShakeDetector()
            val duration = random.nextLong(200, 901)
            val (triggers, end) = detector.oscillate(random.nextLong(0, 2_000), duration)
            val after = detector.still(end, end + 3_000)
            assertWithMessage("run $it duration=$duration").that(triggers + after).isEqualTo(0)
        }
    }

    @Test
    fun `two oscillating shakes with a pause trigger exactly once`() {
        repeat(RUNS) {
            val detector = ShakeDetector()
            val first = random.nextLong(200, 901)
            val pause = random.nextLong(300, 701)
            val second = random.nextLong(200, 901)
            var (triggers, t) = detector.oscillate(random.nextLong(0, 2_000), first)
            triggers += detector.still(t, t + pause)
            val (secondTriggers, end) = detector.oscillate(t + pause, second)
            triggers += secondTriggers + detector.still(end, end + 3_000)
            assertWithMessage("run $it first=$first pause=$pause second=$second").that(triggers).isEqualTo(1)
        }
    }

    private companion object {
        const val SEED = 5656L
        const val RUNS = 2_000
    }
}
