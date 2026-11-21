package com.paragrudani.replyly.ui.settings

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.feedback.FeedbackEntry
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.parser.ChatKey
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.random.Random

class FeedbackDayTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 11, 21)

    private fun entry(
        id: Long,
        at: LocalDateTime,
    ) = FeedbackEntry(
        id = id,
        chatKey = ChatKey("com.whatsapp", "Riya"),
        createdAt = at.atZone(zone).toInstant(),
        model = "m",
        prompt = "p",
        suggestions = listOf(Suggestion("agree", "a"), Suggestion("decline", "b"), Suggestion("ask", "c")),
    )

    @Test
    fun `entries group by local day, newest day first, with today and yesterday named`() {
        val entries =
            listOf(
                entry(4, LocalDateTime.of(2026, 11, 21, 9, 30)),
                entry(3, LocalDateTime.of(2026, 11, 21, 0, 5)),
                entry(2, LocalDateTime.of(2026, 11, 20, 23, 59)),
                entry(1, LocalDateTime.of(2026, 11, 3, 12, 0)),
            )

        val days = groupFeedbackByDay(entries, zone, today, Locale.US)

        assertThat(days.map { it.label }).containsExactly("Today", "Yesterday", "Tue, Nov 3").inOrder()
        assertThat(days.map { day -> day.entries.map { it.id } })
            .containsExactly(listOf(4L, 3L), listOf(2L), listOf(1L))
            .inOrder()
    }

    @Test
    fun `the day boundary follows the zone, not UTC`() {
        // 00:30 in Kolkata is still the previous day in UTC.
        val days = groupFeedbackByDay(listOf(entry(1, LocalDateTime.of(2026, 11, 21, 0, 30))), zone, today, Locale.US)
        assertThat(days.single().label).isEqualTo("Today")
    }

    @Test
    fun `a different year shows the year`() {
        val days = groupFeedbackByDay(listOf(entry(1, LocalDateTime.of(2025, 12, 31, 8, 0))), zone, today, Locale.US)
        assertThat(days.single().label).isEqualTo("Wed, Dec 31, 2025")
    }

    @Test
    fun `no entries, no days`() {
        assertThat(groupFeedbackByDay(emptyList(), zone, today, Locale.US)).isEmpty()
    }

    /**
     * Seeded property test. Invariants, for any entries in any order:
     * 1. every entry appears exactly once;
     * 2. all entries of a group share its date, and each date has one group;
     * 3. groups go newest date first and entries inside a group newest first.
     */
    @Test
    fun `grouping keeps every entry, one group per day, newest first`() {
        val random = Random(17)
        repeat(300) {
            val entries =
                List(random.nextInt(0, 40)) { i ->
                    entry(
                        i.toLong(),
                        LocalDateTime.of(2026, 11, 21, 12, 0).minusMinutes(random.nextLong(0, 60L * 24 * 20)),
                    )
                }.shuffled(random)

            val days = groupFeedbackByDay(entries, zone, today, Locale.US)

            assertThat(days.flatMap { it.entries }.map { it.id }).containsExactlyElementsIn(entries.map { it.id })
            assertThat(days.map { it.date }).containsNoDuplicates()
            days.forEach { day ->
                day.entries.forEach { assertThat(it.createdAt.atZone(zone).toLocalDate()).isEqualTo(day.date) }
            }
            assertThat(days.map { it.date }).isInStrictOrder(reverseOrder<LocalDate>())
            days.forEach { day ->
                assertThat(day.entries.map { it.createdAt }).isInOrder(reverseOrder<java.time.Instant>())
            }
        }
    }
}
