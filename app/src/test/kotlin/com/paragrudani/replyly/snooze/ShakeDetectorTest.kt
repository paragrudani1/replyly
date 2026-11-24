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
    fun `samples of one shake within the debounce count once`() {
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
}
