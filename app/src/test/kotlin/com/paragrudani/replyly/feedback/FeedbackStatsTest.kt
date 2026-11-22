package com.paragrudani.replyly.feedback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeedbackStatsTest {
    private fun row(
        id: Long,
        version: Int = 2,
        panel: String? = "p$id",
        inserted: String? = null,
        sent: String? = null,
    ) = FeedbackStatsRow(id, version, panel, inserted, sent)

    @Test
    fun `edit distance is 0 for equal texts and 1 for nothing shared`() {
        assertThat(FeedbackStats.normalizedEditDistance("haan pakka", "haan pakka")).isEqualTo(0.0)
        assertThat(FeedbackStats.normalizedEditDistance("", "")).isEqualTo(0.0)
        assertThat(FeedbackStats.normalizedEditDistance("abc", "xyz")).isEqualTo(1.0)
        assertThat(FeedbackStats.normalizedEditDistance("", "ok")).isEqualTo(1.0)
    }

    @Test
    fun `edit distance is divided by the longer text and ignores outer spaces`() {
        // kitten -> sitting: 3 edits over 7 characters.
        assertThat(FeedbackStats.normalizedEditDistance("kitten", "sitting")).isWithin(1e-9).of(3.0 / 7)
        assertThat(FeedbackStats.normalizedEditDistance(" ok ", "ok")).isEqualTo(0.0)
        // One appended word: "Haan pakka" -> "Haan pakka bhai" is 5 of 15.
        assertThat(FeedbackStats.normalizedEditDistance("Haan pakka", "Haan pakka bhai")).isWithin(1e-9).of(5.0 / 15)
    }

    @Test
    fun `an emoji counts as one character`() {
        assertThat(FeedbackStats.normalizedEditDistance("ok 👍", "ok 👌")).isWithin(1e-9).of(1.0 / 4)
    }

    @Test
    fun `insert rate counts panels, not generations`() {
        val rows =
            listOf(
                // Panel A: 3 generations (Regenerate), the last one inserted.
                row(1, panel = "A"),
                row(2, panel = "A"),
                row(3, panel = "A", inserted = "x"),
                // Panel B: one generation, nothing inserted.
                row(4, panel = "B"),
            )
        val v2 = FeedbackStats.compute(rows).single()
        assertThat(v2.panels).isEqualTo(2)
        assertThat(v2.panelsWithInsert).isEqualTo(1)
        assertThat(v2.insertRate).isEqualTo(0.5)
    }

    @Test
    fun `old entries without a panel id or chat key are one panel each`() {
        val rows = listOf(row(1, version = 1, panel = null, inserted = "a"), row(2, version = 1, panel = null))
        val v1 = FeedbackStats.compute(rows).single()
        assertThat(v1.panels).isEqualTo(2)
        assertThat(v1.panelsWithInsert).isEqualTo(1)
        assertThat(v1.panelsEstimated).isTrue()
    }

    @Test
    fun `old entries of one chat within 2 minutes of the previous one are one estimated panel`() {
        val minute = 60_000L

        fun v1(
            id: Long,
            chat: String,
            at: Long,
            inserted: String? = null,
        ) = FeedbackStatsRow(id, 1, null, inserted, null, chatKey = chat, createdAtMillis = at)
        val rows =
            listOf(
                // Riya: open, Regenerate 1 min later, chip 3 min after the first (2 min after the
                // previous one: still the same panel), then inserted.
                v1(1, "w:Riya", 0),
                v1(2, "w:Riya", 1 * minute),
                v1(4, "w:Riya", 3 * minute, inserted = "x"),
                // Another chat in between does not split or join Riya's panel.
                v1(3, "w:Amit", 2 * minute),
                // Riya again 10 minutes later: a new panel.
                v1(5, "w:Riya", 13 * minute),
                // Just over 2 minutes after that: a new panel too.
                v1(6, "w:Riya", 15 * minute + 1),
            )
        val stats = FeedbackStats.compute(rows).single()
        assertThat(stats.panels).isEqualTo(4)
        assertThat(stats.panelsWithInsert).isEqualTo(1)
        assertThat(stats.panelsEstimated).isTrue()
        assertThat(FeedbackStats.estimatedPanels(rows).map { panel -> panel.map { it.id } })
            .containsExactly(listOf(1L, 2L, 4L), listOf(5L), listOf(6L), listOf(3L))
    }

    @Test
    fun `entries with a panel id are not estimated`() {
        assertThat(FeedbackStats.compute(listOf(row(1))).single().panelsEstimated).isFalse()
    }

    @Test
    fun `average edit uses only entries with both the inserted and the sent text`() {
        val rows =
            listOf(
                row(1, inserted = "abcd", sent = "abcd"), // 0
                row(2, inserted = "abcd", sent = "abce"), // 0.25
                row(3, inserted = "abcd"), // sent not seen: not measured
                row(4, sent = "typed myself"), // nothing inserted: not measured
            )
        val v2 = FeedbackStats.compute(rows).single()
        assertThat(v2.editsMeasured).isEqualTo(2)
        assertThat(v2.averageEdit).isWithin(1e-9).of(0.125)
    }

    @Test
    fun `stats are per prompt version, oldest version first`() {
        val rows = listOf(row(1, version = 2, inserted = "a"), row(2, version = 1), row(3, version = 2))
        val stats = FeedbackStats.compute(rows)
        assertThat(stats.map { it.promptVersion }).containsExactly(1, 2).inOrder()
        assertThat(stats[0].insertRate).isEqualTo(0.0)
        assertThat(stats[0].averageEdit).isNull()
        assertThat(stats[1].insertRate).isEqualTo(0.5)
    }

    @Test
    fun `no entries means no stats`() {
        assertThat(FeedbackStats.compute(emptyList())).isEmpty()
        assertThat(PromptVersionStats(2, 0, 0, 0, null).insertRate).isNull()
    }
}
