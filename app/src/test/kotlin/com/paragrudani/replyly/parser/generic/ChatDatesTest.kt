package com.paragrudani.replyly.parser.generic

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.LocalDate

class ChatDatesTest {
    // A Sunday.
    private val today = LocalDate.of(2026, 11, 22)

    private fun date(
        y: Int,
        m: Int,
        d: Int,
    ) = LocalDate.of(y, m, d)

    private fun assertResolves(
        expected: LocalDate,
        vararg texts: String,
    ) = texts.forEach { assertWithMessage(it).that(ChatDates.resolve(it, today)).isEqualTo(expected) }

    @Test
    fun `today and yesterday in English and Hindi`() {
        assertResolves(today, "Today", "TODAY", "today", "आज")
        assertResolves(today.minusDays(1), "Yesterday", "YESTERDAY", "कल")
    }

    @Test
    fun `a weekday is the most recent past day with that name, within the last 7 days`() {
        assertResolves(date(2026, 11, 21), "Saturday", "SATURDAY", "Sat", "शनिवार")
        assertResolves(date(2026, 11, 16), "Monday", "सोमवार")
        // Today's own name means a week ago: a separator never names today by weekday.
        assertResolves(date(2026, 11, 15), "Sunday", "रविवार")
    }

    @Test
    fun `day month year in every order and length`() {
        assertResolves(
            date(2026, 11, 21),
            "21 November 2026",
            "21 NOVEMBER 2026",
            "21 Nov 2026",
            "November 21, 2026",
            "Nov 21, 2026",
            "Saturday, 21 November 2026",
            "Sat, 21 Nov 2026",
            "21 नवंबर 2026",
            "२१ नवंबर २०२६",
        )
        assertResolves(date(2025, 9, 3), "3 Sept 2025", "3 Sep 2025", "September 3, 2025")
    }

    @Test
    fun `numeric dates read day first, as in Indian English`() {
        assertResolves(date(2026, 11, 21), "21/11/2026", "21.11.26", "21-11-2026", "2026-11-21", "21/11/26")
        assertResolves(date(2026, 3, 4), "04/03/2026", "4.3.26")
        // Only a month-first reading is a real date.
        assertResolves(date(2026, 11, 21), "11/21/2026")
    }

    @Test
    fun `a date without a year is its most recent past occurrence`() {
        assertResolves(date(2026, 11, 21), "21 November", "November 21", "Nov 21", "Saturday, 21 November")
        assertResolves(date(2025, 12, 25), "25 December", "Dec 25")
        assertResolves(today, "22 Nov")
        // 29 February: the last leap year.
        assertResolves(date(2024, 2, 29), "29 February")
    }

    @Test
    fun `text that is not a date`() {
        listOf(
            "Today at 6?",
            "may be",
            "Monday works",
            "21 people",
            "ok",
            "10:30",
            "6.42",
            "31 February 2026",
            "32/11/2026",
            "13/13/2026",
            "kal milte hain",
            "",
        ).forEach { assertWithMessage(it).that(ChatDates.resolve(it, today)).isNull() }
    }

    @Test
    fun `isDateSeparator recognises the same forms`() {
        listOf("21.11.26", "21 नवंबर 2026", "आज", "Sat, 21 Nov 2026").forEach {
            assertWithMessage(it).that(ChatText.isDateSeparator(it)).isTrue()
        }
        assertThat(ChatText.isDateSeparator("Monday works")).isFalse()
    }

    @Test
    fun `telegram status lines carry the message's own date`() {
        assertThat(ChatDates.fromStatusLine("Received today at 6:42 PM", today)).isEqualTo(today)
        assertThat(ChatDates.fromStatusLine("Sent yesterday at 9:15 PM, Read", today)).isEqualTo(date(2026, 11, 21))
        assertThat(ChatDates.fromStatusLine("Received Nov 19 at 6:42 PM", today)).isEqualTo(date(2026, 11, 19))
        assertThat(ChatDates.fromStatusLine("Received on Nov 19 at 6:42 PM", today)).isEqualTo(date(2026, 11, 19))
        assertThat(ChatDates.fromStatusLine("Sent 21.11.25 at 18:02, Read", today)).isEqualTo(date(2025, 11, 21))
        assertThat(ChatDates.fromStatusLine("Received today at 6:42 PM", today)).isEqualTo(today)
        // No date in the line.
        assertThat(ChatDates.fromStatusLine("Received at 6:42 PM", today)).isNull()
        assertThat(ChatDates.fromStatusLine("Sent soon at 6:42 PM", today)).isNull()
    }
}
