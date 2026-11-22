package com.paragrudani.replyly.history

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatMessage
import org.junit.Test
import java.time.LocalDate

/**
 * Dates in the merge (issue #26): a message's date is metadata, not part of its identity, except
 * that two messages whose dates are both known and differ are never the same message. A read
 * that shows a date (or a reply to me) the saved row lacks fills it in.
 */
class HistoryMergeDatesTest {
    private val nov20 = LocalDate.of(2026, 11, 20)
    private val nov21 = LocalDate.of(2026, 11, 21)

    private fun them(
        text: String,
        time: String? = "9:00 am",
        date: LocalDate? = null,
    ) = ChatMessage(sender = "Asha", text = text, timeShown = time, isMe = false, date = date)

    private fun me(
        text: String,
        time: String? = "9:05 am",
        date: LocalDate? = null,
    ) = ChatMessage(sender = null, text = text, timeShown = time, isMe = true, date = date)

    private fun historyOf(vararg messages: ChatMessage) = FakeHistory().apply { merge(messages.toList()) }

    @Test
    fun `a re-read that now shows the separator dates the saved rows and stores nothing`() {
        val history = historyOf(them("good morning everyone"), me("morning! coffee?"), them("yes at 10"))

        val plan =
            history.merge(
                listOf(them("good morning everyone", date = nov21), me("morning! coffee?", date = nov21)),
            )

        assertThat(plan.inserts).isEmpty()
        assertThat(plan.edits).isEmpty()
        assertThat(plan.isNoOp).isFalse()
        assertThat(history.messages().map { it.date }).containsExactly(nov21, nov21, null).inOrder()
    }

    @Test
    fun `a re-read with the same dates is a no-op`() {
        val read = listOf(them("good morning everyone", date = nov21), me("morning! coffee?", date = nov21))
        val history = historyOf(*read.toTypedArray())

        assertThat(history.merge(read).isNoOp).isTrue()
    }

    @Test
    fun `a read without dates still lines up with dated rows`() {
        val history = historyOf(them("good morning everyone", date = nov21), me("morning! coffee?", date = nov21))

        val plan = history.merge(listOf(them("good morning everyone"), me("morning! coffee?"), them("yes at 10")))

        assertThat(plan.anchored).isTrue()
        assertThat(history.texts()).containsExactly("good morning everyone", "morning! coffee?", "yes at 10").inOrder()
        // The known dates stay; the new message has none of its own.
        assertThat(history.messages().map { it.date }).containsExactly(nov21, nov21, null).inOrder()
    }

    @Test
    fun `the same messages on another known day are other messages`() {
        // The same greeting and reply at the same times, yesterday and today.
        val history = historyOf(them("good morning everyone", date = nov20), me("morning! coffee?", date = nov20))

        val plan =
            history.merge(
                listOf(them("good morning everyone", date = nov21), me("morning! coffee?", date = nov21)),
            )

        assertThat(plan.anchored).isFalse()
        assertThat(plan.inserts).hasSize(2)
        assertThat(history.messages().map { it.date }).containsExactly(nov20, nov20, nov21, nov21).inOrder()
    }

    @Test
    fun `without dates the same messages on another day are taken for the saved ones`() {
        // The known limit the dates remove: nothing on screen told the days apart.
        val history = historyOf(them("good morning everyone"), me("morning! coffee?"))

        assertThat(history.merge(listOf(them("good morning everyone"), me("morning! coffee?"))).isNoOp).isTrue()
    }

    @Test
    fun `a message on a known day is not an edit of one on another known day`() {
        val history =
            historyOf(
                them("good morning everyone", "9:00 am", nov21),
                me("old text here", "9:05 am", nov20),
                them("see you at the station", "9:10 am", nov21),
            )

        history.merge(
            listOf(
                them("good morning everyone", "9:00 am", nov21),
                me("new text here", "9:05 am", nov21),
                them("see you at the station", "9:10 am", nov21),
            ),
        )

        assertThat(history.texts()).contains("old text here")
        assertThat(history.texts()).contains("new text here")
    }

    @Test
    fun `an edit keeps the saved date when the read shows none`() {
        val history =
            historyOf(
                them("good morning everyone", "9:00 am", nov21),
                me("old text here", "9:05 am", nov21),
                them("see you at the station", "9:10 am", nov21),
            )

        history.merge(
            listOf(
                them("good morning everyone"),
                me("new text here", "9:05 am"),
                them("see you at the station", "9:10 am"),
            ),
        )

        assertThat(history.texts()).containsExactly("good morning everyone", "new text here", "see you at the station")
        assertThat(history.messages().map { it.date }).containsExactly(nov21, nov21, nov21).inOrder()
    }

    @Test
    fun `a read that shows a reply to me marks the saved row`() {
        val history = historyOf(me("dinner on friday?"), them("yes, 8 pm works"), them("book a table"))

        history.merge(listOf(me("dinner on friday?"), them("yes, 8 pm works").copy(repliesToMe = true)))

        assertThat(history.messages().map { it.repliesToMe }).containsExactly(false, true, false).inOrder()
        // A later read without the quote in view does not clear it.
        history.merge(listOf(me("dinner on friday?"), them("yes, 8 pm works"), them("book a table")))
        assertThat(history.messages().map { it.repliesToMe }).containsExactly(false, true, false).inOrder()
    }
}
