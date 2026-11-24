package com.paragrudani.replyly.snooze

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Seeded property test over random accelerometer streams (#56).
 *
 * Invariants:
 * 1. sub-threshold noise (any direction, any rate, magnitude below 2.7 g) never triggers;
 * 2. two peaks 250 ms to 1.2 s apart in such noise trigger exactly once, on the second peak;
 * 3. two peaks more than 1.2 s apart (1.3 s and up) never trigger;
 * 4. a peak burst shorter than the debounce counts as one shake.
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
            val gap = random.nextLong(ShakeDetector.DEBOUNCE_MS, ShakeDetector.WINDOW_MS + 1)
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
                // A short burst (shorter than the debounce) of over-threshold samples is one peak.
                val burst = random.nextLong(0, ShakeDetector.DEBOUNCE_MS)
                var s = t
                while (s <= t + burst) {
                    if (detector.feed(s, random.nextFloat() * 3 + 2.75f)) triggers++
                    s += random.nextLong(5, 40)
                }
                val next = t + random.nextLong(1_300, 4_000)
                triggers += detector.noise(s, next)
                t = next
            }
            assertWithMessage("run $it").that(triggers).isEqualTo(0)
        }
    }

    private companion object {
        const val SEED = 5656L
        const val RUNS = 2_000
    }
}
