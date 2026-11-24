package com.paragrudani.replyly.snooze

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShakeDetectorTest {
    private val detector = ShakeDetector()

    /** A sample of [g] times gravity, all on one axis. */
    private fun sample(
        timeMs: Long,
        g: Float,
    ) = detector.onSample(timeMs, 0f, 0f, g * ShakeDetector.GRAVITY)

    @Test
    fun `a phone at rest never triggers`() {
        val hits = (0L..5_000L step 20).count { sample(it, 1f) }
        assertThat(hits).isEqualTo(0)
    }

    @Test
    fun `one shake is not enough`() {
        assertThat(sample(0, 3.5f)).isFalse()
        assertThat((20L..3_000L step 20).count { sample(it, 1f) }).isEqualTo(0)
    }

    @Test
    fun `two shakes within 1_2 s trigger once`() {
        assertThat(sample(1_000, 3f)).isFalse()
        assertThat(sample(1_600, 3f)).isTrue()
    }

    @Test
    fun `two shakes 1_3 s apart do not trigger`() {
        assertThat(sample(1_000, 3f)).isFalse()
        assertThat(sample(2_300, 3f)).isFalse()
    }

    @Test
    fun `samples less than 250 ms apart are one shake, however long`() {
        assertThat(sample(1_000, 3f)).isFalse()
        assertThat(sample(1_020, 3.2f)).isFalse()
        assertThat(sample(1_100, 3.1f)).isFalse()
        assertThat(sample(1_240, 3.1f)).isFalse()
    }

    @Test
    fun `a trigger resets the count`() {
        sample(1_000, 3f)
        assertThat(sample(1_400, 3f)).isTrue()
        assertThat(sample(1_800, 3f)).isFalse()
        assertThat(sample(2_200, 3f)).isTrue()
    }

    @Test
    fun `the threshold is 2_7 g`() {
        sample(1_000, 2.69f)
        assertThat(sample(1_500, 2.69f)).isFalse()
        sample(3_000, 2.71f)
        assertThat(sample(3_500, 2.71f)).isTrue()
    }

    @Test
    fun `reset forgets a first shake`() {
        sample(1_000, 3f)
        detector.reset()
        assertThat(sample(1_500, 3f)).isFalse()
    }

    @Test
    fun `a long continuous shake is one shake`() {
        // An over sample every 150 ms for 3 s: never 250 ms quiet, so never a second shake.
        assertThat((0L..3_000L step 150).count { sample(it, 3.5f) }).isEqualTo(0)
    }

    @Test
    fun `the window runs from the end of the first shake`() {
        // First shake 0..900 ms, then still, then a second shake 1.1 s after its end: 2 s start to start.
        assertThat((0L..900L step 100).count { sample(it, 3.5f) }).isEqualTo(0)
        assertThat(sample(950, 1f)).isFalse()
        assertThat(sample(2_000, 3.5f)).isTrue()
    }

    @Test
    fun `a second shake needs 250 ms quiet before it`() {
        assertThat(sample(0, 3f)).isFalse()
        assertThat(sample(249, 3f)).isFalse()
        assertThat(sample(498, 3f)).isFalse()
        // 250 ms after the last over sample: a new shake, the second of the pair.
        assertThat(sample(748, 3f)).isTrue()
    }
}
