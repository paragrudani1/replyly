package com.paragrudani.replyly.parser.generic

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** [ChatAssembler.checkDates]: the per-screen plausibility pass every parser runs (issue #26, S1). */
class ChatAssemblerDatesTest {
    private val nov21 = LocalDate.of(2026, 11, 21)
    private val nov22 = LocalDate.of(2026, 11, 22)

    /** Five past midnight on 22 November. */
    private val justAfterMidnight = LocalDateTime.of(2026, 11, 22, 0, 5)
    private val evening = LocalDateTime.of(2026, 11, 22, 20, 0)

    private fun bubble(
        time: String?,
        date: LocalDate?,
    ) = RawBubble(Side.LEFT, null, "message at $time", time, date)

    private fun ChatAssembler.DateCheck.dates() = bubbles.map { it.date }

    @Test
    fun `plausible dates stay`() {
        val bubbles = listOf(bubble("11:58 pm", nov21), bubble("12:01 am", nov22), bubble("12:03 am", nov22))

        val check = ChatAssembler.checkDates(bubbles, listOf(nov21, nov22), justAfterMidnight)

        assertThat(check.dropped).isNull()
        assertThat(check.bubbles).isEqualTo(bubbles)
    }

    @Test
    fun `a stale today drops every date on the screen`() {
        // Drawn before midnight, read after it: "TODAY" above 11:50 pm still means the 21st.
        val check =
            ChatAssembler.checkDates(
                listOf(bubble("11:48 pm", nov22), bubble("11:50 pm", nov22)),
                listOf(nov22),
                justAfterMidnight,
            )

        assertThat(check.dropped).isEqualTo("stale today")
        assertThat(check.dates()).containsExactly(null, null)
    }

    @Test
    fun `a time up to five minutes ahead of the capture is not stale`() {
        val at = LocalDateTime.of(2026, 11, 22, 18, 40)
        listOf("6:45 pm" to null, "18:45" to null, "6:46 pm" to "stale today", "18:46" to "stale today")
            .forEach { (time, dropped) ->
                val check = ChatAssembler.checkDates(listOf(bubble(time, nov22)), listOf(nov22), at)
                assertThat(check.dropped).isEqualTo(dropped)
            }
        // Yesterday's late messages are never stale, and neither is anything in the day's last minutes.
        assertThat(ChatAssembler.checkDates(listOf(bubble("11:50 pm", nov21)), emptyList(), at).dropped).isNull()
        val lateCapture = LocalDateTime.of(2026, 11, 22, 23, 57)
        assertThat(ChatAssembler.checkDates(listOf(bubble("12:01 am", nov22)), emptyList(), lateCapture).dropped)
            .isNull()
    }

    @Test
    fun `two todays, or separators that go back in time, drop every date on the screen`() {
        val twoTodays =
            ChatAssembler.checkDates(
                listOf(bubble("11:50 pm", nov22), bubble("12:02 am", nov22)),
                listOf(nov22, nov22),
                justAfterMidnight,
            )
        assertThat(twoTodays.dropped).isEqualTo("separators out of order")
        assertThat(twoTodays.dates()).containsExactly(null, null)

        val backwards =
            ChatAssembler.checkDates(
                listOf(bubble("9:00 am", nov22), bubble("9:00 am", nov21)),
                listOf(nov22, nov21),
                evening,
            )
        assertThat(backwards.dropped).isEqualTo("separators out of order")
        assertThat(backwards.dates()).containsExactly(null, null)
    }

    @Test
    fun `an unreadable separator does not count for the order`() {
        val check =
            ChatAssembler.checkDates(
                listOf(bubble("9:00 am", nov21), bubble("9:00 am", null), bubble("10:00 am", nov22)),
                listOf(nov21, null, nov22),
                evening,
            )

        assertThat(check.dropped).isNull()
    }

    @Test
    fun `a date after the capture day drops every date on the screen`() {
        val check =
            ChatAssembler.checkDates(
                listOf(bubble("9:00 am", nov21), bubble("9:00 am", nov22.plusDays(3))),
                listOf(nov21, nov22.plusDays(3)),
                evening,
            )

        assertThat(check.dropped).isEqualTo("future date")
        assertThat(check.dates()).containsExactly(null, null)
    }

    @Test
    fun `clock times in the forms bubbles show`() {
        assertThat(ChatAssembler.clockTime("6:42 pm")).isEqualTo(LocalTime.of(18, 42))
        assertThat(ChatAssembler.clockTime("6:42 PM")).isEqualTo(LocalTime.of(18, 42))
        assertThat(ChatAssembler.clockTime("6:42 p.m.")).isEqualTo(LocalTime.of(18, 42))
        assertThat(ChatAssembler.clockTime("12:05 am")).isEqualTo(LocalTime.of(0, 5))
        assertThat(ChatAssembler.clockTime("12:05 pm")).isEqualTo(LocalTime.of(12, 5))
        assertThat(ChatAssembler.clockTime("00:05")).isEqualTo(LocalTime.of(0, 5))
        assertThat(ChatAssembler.clockTime("23:59")).isEqualTo(LocalTime.of(23, 59))
        listOf("24:00", "13:00 pm", "0:30 am", "6:60 pm", "edited 6:42 pm", "").forEach {
            assertThat(ChatAssembler.clockTime(it)).isNull()
        }
    }
}
