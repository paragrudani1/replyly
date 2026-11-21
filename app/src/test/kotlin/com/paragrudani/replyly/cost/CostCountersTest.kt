package com.paragrudani.replyly.cost

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CostCountersTest {
    private val nov21 = LocalDate.of(2026, 11, 21)
    private val counters =
        CostCounters(
            day = nov21,
            requests = 7,
            month = YearMonth.of(2026, 11),
            promptTokens = 1000,
            completionTokens = 200,
        )

    @Test
    fun `same day keeps everything`() {
        assertThat(counters.rolledTo(nov21)).isEqualTo(counters)
    }

    @Test
    fun `next day resets requests but keeps the month tokens`() {
        val rolled = counters.rolledTo(LocalDate.of(2026, 11, 22))
        assertThat(rolled.requests).isEqualTo(0)
        assertThat(rolled.day).isEqualTo(LocalDate.of(2026, 11, 22))
        assertThat(rolled.promptTokens).isEqualTo(1000)
        assertThat(rolled.completionTokens).isEqualTo(200)
    }

    @Test
    fun `first of the next month resets requests and tokens`() {
        val rolled = counters.rolledTo(LocalDate.of(2026, 12, 1))
        assertThat(rolled).isEqualTo(CostCounters(LocalDate.of(2026, 12, 1), 0, YearMonth.of(2026, 12), 0, 0))
    }

    @Test
    fun `same day of a later year resets everything`() {
        val rolled = counters.rolledTo(LocalDate.of(2027, 11, 21))
        assertThat(rolled.requests).isEqualTo(0)
        assertThat(rolled.promptTokens).isEqualTo(0)
    }

    @Test
    fun `a clock moved backwards does not reset the counters`() {
        // Travelling back across a time zone must not hand out a fresh daily allowance.
        assertThat(counters.rolledTo(LocalDate.of(2026, 11, 20))).isEqualTo(counters)
    }

    @Test
    fun `a stored day far in the future moves back to today and keeps the counts`() {
        // The clock was once set to next year; without this the count would never reset until then.
        val future = counters.copy(day = LocalDate.of(2027, 3, 5), month = YearMonth.of(2027, 3))
        val rolled = future.rolledTo(nov21)
        assertThat(rolled).isEqualTo(counters)
        // From there it rolls normally again.
        assertThat(rolled.rolledTo(LocalDate.of(2026, 11, 22)).requests).isEqualTo(0)
    }
}
