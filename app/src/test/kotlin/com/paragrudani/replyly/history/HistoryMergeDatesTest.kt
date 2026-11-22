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
    fun `a re-read that now shows the separator dates the rows inside the screen and stores nothing`() {
        val history =
            historyOf(them("good morning everyone"), me("morning! coffee?"), them("yes at 10"), me("see you"))

        val plan =
            history.merge(
                listOf(
                    them("good morning everyone", date = nov21),
                    me("morning! coffee?", date = nov21),
                    them("yes at 10", date = nov21),
                ),
            )

        assertThat(plan.inserts).isEmpty()
        assertThat(plan.edits).isEmpty()
        assertThat(plan.isNoOp).isFalse()
        // The screen's top and bottom message may be another day's twin (the edge limits): they
        // stay approx until a later read shows them inside the screen.
        assertThat(history.messages().map { it.date }).containsExactly(null, nov21, null, null).inOrder()
    }

    @Test
    fun `a short message dates its row only when both neighbours line up around it`() {
        val history =
            historyOf(
                them("message one", "9:00 am"),
                me("ok", "9:01 am"),
                them("message two", "9:02 am"),
                me("ok", "9:03 am"),
                them("message three", "9:04 am"),
                me("message four", "9:05 am"),
            )

        history.merge(
            listOf(
                them("message one", "9:00 am", nov21),
                me("ok", "9:01 am", nov21),
                them("message two", "9:02 am", nov21),
                // A message the history missed sits between "message two" and this "ok".
                them("missed one", "9:02 am", nov21),
                me("ok", "9:03 am", nov21),
                them("message three", "9:04 am", nov21),
                me("message four", "9:05 am", nov21),
            ),
        )

        assertThat(history.messages().map { it.text to it.date })
            .containsExactly(
                // The read's first message: an edge, approx.
                "message one" to null,
                // Both neighbours paired with the rows around it.
                "ok" to nov21,
                "message two" to nov21,
                "missed one" to nov21,
                // Its upper neighbour is new: it could be another "ok", so it stays approx.
                "ok" to null,
                "message three" to nov21,
                "message four" to null,
            ).inOrder()
    }

    @Test
    fun `a message dated after the day a row was first seen is another message`() {
        // Saved on the 20th without a separator: approx 20 November, so sent on the 20th or before.
        val history = FakeHistory()
        history.merge(listOf(them("gm"), me("ok", "9:01 am"), them("😂", "9:02 am")), today = nov20)

        // The next day the same daily greeting and replies, under "TODAY".
        val plan =
            history.merge(
                listOf(them("gm", date = nov21), me("ok", "9:01 am", nov21), them("😂", "9:02 am", nov21)),
                today = nov21,
            )

        assertThat(plan.anchored).isFalse()
        assertThat(history.messages().map { it.date }).containsExactly(null, null, null, nov21, nov21, nov21).inOrder()
    }

    @Test
    fun `on a later day only a slot that looks like an edit on its own is taken for one`() {
        fun reRead(
            newText: String,
            today: LocalDate,
        ) = FakeHistory()
            .apply {
                merge(
                    listOf(
                        them("good morning everyone", "9:00 am"),
                        me("old text here", "9:05 am"),
                        them("see you at the station", "9:10 am"),
                    ),
                    today = nov20,
                )
                merge(
                    listOf(
                        them("good morning everyone", "9:00 am"),
                        me(newText, "9:05 am"),
                        them("see you at the station", "9:10 am"),
                    ),
                    today = today,
                )
            }.texts()

        assertThat(reRead("gm", nov20))
            .containsExactly("good morning everyone", "gm", "see you at the station")
            .inOrder()
        // A day later the undated read may still be from the 20th: a distinctive new text between
        // two distinctive pairs is an edit.
        assertThat(reRead("new text here", nov21))
            .containsExactly("good morning everyone", "new text here", "see you at the station")
            .inOrder()
        // A short one at the old message's time may be a daily habit ("gm" at 9:05 am): it is
        // stored as a new message in the slot, and the saved text stays.
        assertThat(reRead("gm", nov21))
            .containsExactly("good morning everyone", "old text here", "gm", "see you at the station")
            .inOrder()
    }

    /**
     * PR #27 review round 2, cases D1 to D5: a six-message screen read on [nov20], one message
     * edited within minutes, then the screen read again. Returns the history's texts.
     */
    private fun editReRead(
        times: List<String> = listOf("9:00 am", "9:02 am", "9:03 am", "9:05 am", "9:06 am", "9:08 am"),
        reRead: (List<ChatMessage>) -> Pair<List<ChatMessage>, LocalDate>,
    ): List<String> {
        val texts =
            listOf(
                "good morning everyone",
                "are we still on for lunch",
                "yes at the usual place",
                "great, booking a table",
                "see you there then",
                "leaving the office now",
            )
        val screen =
            texts.zip(times).mapIndexed { k, (text, time) -> if (k % 2 == 0) them(text, time) else me(text, time) }
        val history = FakeHistory()
        history.merge(screen, today = nov20)
        val edited = screen.mapIndexed { k, m -> if (k == 2) m.copy(text = "yes at the new place") else m }
        val (read, today) = reRead(edited)
        history.merge(read, today = today)
        return history.texts()
    }

    private val editedTexts =
        listOf(
            "good morning everyone",
            "are we still on for lunch",
            "yes at the new place",
            "great, booking a table",
            "see you there then",
            "leaving the office now",
        )

    @Test
    fun `D1 an edit re-read the same day is applied`() {
        assertThat(editReRead { it to nov20 }).containsExactlyElementsIn(editedTexts).inOrder()
    }

    @Test
    fun `D2 an edit re-read the next day without a separator is applied`() {
        // The undated read may be from the 20th: the capture day only bounds it from above.
        assertThat(editReRead { it to nov21 }).containsExactlyElementsIn(editedTexts).inOrder()
    }

    @Test
    fun `D3 an edit re-read the next day under YESTERDAY is applied`() {
        assertThat(editReRead { read -> read.map { it.copy(date = nov20) } to nov21 })
            .containsExactlyElementsIn(editedTexts)
            .inOrder()
    }

    @Test
    fun `D4 an edit re-read the next day above a TODAY separator is applied`() {
        val texts = editReRead { read -> read.drop(1) + them("morning! lunch again?", "8:50 am", nov21) to nov21 }

        assertThat(texts).containsExactlyElementsIn(editedTexts + "morning! lunch again?").inOrder()
    }

    @Test
    fun `D5 an edit at 23 58 re-read at 00 03 without a separator is applied`() {
        val times = listOf("11:50 pm", "11:51 pm", "11:52 pm", "11:54 pm", "11:55 pm", "11:56 pm")

        assertThat(editReRead(times) { it to nov21 }).containsExactlyElementsIn(editedTexts).inOrder()
    }

    @Test
    fun `no sender is filled into a saved run of the same text`() {
        fun asha(
            text: String,
            time: String?,
        ) = ChatMessage(sender = "Asha", text = text, timeShown = time, isMe = false)

        fun ravi(
            text: String,
            time: String?,
        ) = ChatMessage(sender = "Ravi", text = text, timeShown = time, isMe = false)
        val history = FakeHistory()
        // Asha's "ok" saved without its name label, then Ravi's "ok" with its time cut off.
        history.merge(
            listOf(
                asha("message one", "9:24 am"),
                asha("ok", "9:34 am").copy(sender = null),
                ravi("ok", null),
                ravi("message two", "9:34 am"),
            ),
        )

        // The read misses Asha's "ok" and shows Ravi's with its time.
        history.merge(listOf(asha("message one", "9:24 am"), ravi("ok", "9:34 am"), ravi("message two", "9:34 am")))

        assertThat(history.messages().map { it.sender }).containsExactly("Asha", null, "Ravi", "Ravi").inOrder()
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
