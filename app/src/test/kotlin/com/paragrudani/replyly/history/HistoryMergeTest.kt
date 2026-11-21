package com.paragrudani.replyly.history

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatMessage
import org.junit.Test

/**
 * The merge is tested through its result: apply the plan to an in-memory history and read the
 * history back in position order, like the DB does.
 */
class HistoryMergeTest {
    private fun them(
        text: String,
        time: String? = "6:40 pm",
        sender: String? = "Asha",
    ) = ChatMessage(sender = sender, text = text, timeShown = time, isMe = false)

    private fun me(
        text: String,
        time: String? = "6:41 pm",
    ) = ChatMessage(sender = null, text = text, timeShown = time, isMe = true)

    /** In-memory stand-in for the messages table of one chat. */
    private class FakeHistory {
        private var nextId = 1L
        val rows = mutableListOf<StoredRow>()

        fun merge(read: List<ChatMessage>): MergePlan {
            val plan = HistoryMerge.plan(rows.sortedBy { it.position }, read)
            plan.moves.forEach { move ->
                val i = rows.indexOfFirst { it.id == move.id }
                rows[i] = rows[i].copy(position = move.position)
            }
            plan.edits.forEach { edit ->
                val i = rows.indexOfFirst { it.id == edit.id }
                rows[i] = rows[i].copy(message = edit.message)
            }
            plan.inserts.forEach { rows += StoredRow(nextId++, it.position, it.message) }
            val positions = rows.map { it.position }
            check(positions.toSet().size == positions.size) { "two rows share a position" }
            return plan
        }

        fun messages(): List<ChatMessage> = rows.sortedBy { it.position }.map { it.message }

        fun texts(): List<String> = messages().map { it.text }
    }

    private fun historyOf(vararg messages: ChatMessage) = FakeHistory().apply { merge(messages.toList()) }

    @Test
    fun `first read of a chat stores every message in screen order`() {
        val history = FakeHistory()

        val plan = history.merge(listOf(them("hi"), me("hello"), them("how are you")))

        assertThat(history.texts()).containsExactly("hi", "hello", "how are you").inOrder()
        assertThat(plan.inserts).hasSize(3)
    }

    @Test
    fun `first read keeps repeated identical messages`() {
        val history = FakeHistory()

        history.merge(listOf(me("ok"), me("ok")))

        assertThat(history.texts()).containsExactly("ok", "ok")
    }

    @Test
    fun `identical re-read changes nothing`() {
        val read = listOf(them("hi"), me("ok"), me("ok"), them("cool"))
        val history = historyOf(*read.toTypedArray())

        val plan = history.merge(read)

        assertThat(plan.isNoOp).isTrue()
        assertThat(history.texts()).containsExactly("hi", "ok", "ok", "cool").inOrder()
    }

    @Test
    fun `empty read changes nothing`() {
        val history = historyOf(them("hi"))

        assertThat(history.merge(emptyList()).isNoOp).isTrue()
    }

    @Test
    fun `new messages below the saved tail are appended`() {
        val history = historyOf(them("a"), them("b"), me("c"))

        val plan = history.merge(listOf(them("b"), me("c"), them("d"), me("e")))

        assertThat(history.texts()).containsExactly("a", "b", "c", "d", "e").inOrder()
        assertThat(plan.anchored).isTrue()
        assertThat(plan.newAtEnd.map { it.text }).containsExactly("d", "e").inOrder()
    }

    @Test
    fun `a second identical ok at the same time is a new message`() {
        val history = historyOf(them("coming?"), me("ok", "6:41 pm"))

        val plan = history.merge(listOf(them("coming?"), me("ok", "6:41 pm"), me("ok", "6:41 pm")))

        assertThat(history.texts()).containsExactly("coming?", "ok", "ok").inOrder()
        assertThat(plan.newAtEnd).containsExactly(me("ok", "6:41 pm"))
    }

    @Test
    fun `repeated messages scrolled partly off the top are not duplicated`() {
        val history = historyOf(them("a"), me("ok"), me("ok"))

        history.merge(listOf(me("ok"), them("b")))

        assertThat(history.texts()).containsExactly("a", "ok", "ok", "b").inOrder()
    }

    @Test
    fun `scrolling up stores older messages before the saved ones`() {
        val history = historyOf(them("d"), me("e"), them("f"))

        val plan = history.merge(listOf(them("a"), me("b"), them("c"), them("d"), me("e")))

        assertThat(history.texts()).containsExactly("a", "b", "c", "d", "e", "f").inOrder()
        assertThat(plan.newAtEnd).isEmpty()
    }

    @Test
    fun `scrolling up many times keeps the order`() {
        val history = historyOf(them("m5"), them("m6"))

        history.merge(listOf(them("m3"), them("m4"), them("m5"), them("m6")))
        history.merge(listOf(them("m1"), them("m2"), them("m3"), them("m4")))
        history.merge(listOf(them("m4"), them("m5"), them("m6"), them("m7")))

        assertThat(history.texts()).containsExactly("m1", "m2", "m3", "m4", "m5", "m6", "m7").inOrder()
    }

    @Test
    fun `a read inside the saved history changes nothing`() {
        val history = historyOf(them("a"), me("b"), them("c"), me("d"), them("e"))

        val plan = history.merge(listOf(me("b"), them("c"), me("d")))

        assertThat(plan.isNoOp).isTrue()
    }

    @Test
    fun `a read with no overlap is appended`() {
        val history = historyOf(them("a"), me("b"))

        val plan = history.merge(listOf(them("x"), me("y")))

        assertThat(history.texts()).containsExactly("a", "b", "x", "y").inOrder()
        assertThat(plan.anchored).isFalse()
    }

    @Test
    fun `a read with no overlap is not duplicated when read again`() {
        val history = historyOf(them("a"), me("b"))
        history.merge(listOf(them("x"), me("y")))

        val plan = history.merge(listOf(them("x"), me("y")))

        assertThat(plan.isNoOp).isTrue()
        assertThat(history.texts()).containsExactly("a", "b", "x", "y").inOrder()
    }

    @Test
    fun `no-overlap messages are never reported as new at the end`() {
        val history = historyOf(them("a"))

        val plan = history.merge(listOf(me("sent while Replyly was off")))

        assertThat(plan.newAtEnd).isEmpty()
    }

    @Test
    fun `a single coincidental match deep in history is not an overlap`() {
        // Bubbles show the time but not the date: yesterday's "gm 9:00 am" equals today's.
        val history =
            historyOf(
                them("gm", "9:00 am"),
                me("gm!", "9:01 am"),
                them("lunch?", "1:00 pm"),
                me("sure", "1:02 pm"),
                them("bye", "8:00 pm"),
            )

        history.merge(
            listOf(
                them("gm", "9:00 am"),
                me("morning", "9:05 am"),
                them("office today?", "9:06 am"),
                me("yes", "9:07 am"),
            ),
        )

        assertThat(history.texts())
            .containsExactly("gm", "gm!", "lunch?", "sure", "bye", "gm", "morning", "office today?", "yes")
            .inOrder()
    }

    @Test
    fun `an edited message in the middle is updated in place`() {
        val history = historyOf(them("a"), me("see you at 6", "6:41 pm"), them("c"))

        val plan = history.merge(listOf(them("a"), me("see you at 7", "6:41 pm"), them("c")))

        assertThat(history.texts()).containsExactly("a", "see you at 7", "c").inOrder()
        assertThat(plan.inserts).isEmpty()
        assertThat(plan.edits).hasSize(1)
    }

    @Test
    fun `a changed last message is stored as a new message, not an edit`() {
        // Edits are looked for only between two aligned messages: the edges are where gaps sit.
        val history = historyOf(them("a"), me("b"), me("typo", "6:45 pm"))

        val plan = history.merge(listOf(them("a"), me("b"), me("fixed", "6:45 pm")))

        assertThat(history.texts()).containsExactly("a", "b", "typo", "fixed").inOrder()
        assertThat(plan.edits).isEmpty()
    }

    @Test
    fun `editing the second of two identical messages edits that one`() {
        val history = historyOf(them("c"), me("ok", "6:45 pm"), me("ok", "6:45 pm"), them("d", "6:46 pm"))

        val plan =
            history.merge(listOf(them("c"), me("ok", "6:45 pm"), me("ok!", "6:45 pm"), them("d", "6:46 pm")))

        assertThat(history.texts()).containsExactly("c", "ok", "ok!", "d").inOrder()
        assertThat(plan.inserts).isEmpty()
    }

    @Test
    fun `a changed first visible message is stored as a new message, not an edit`() {
        val history = historyOf(them("old text", "6:30 pm"), me("b"), them("c"))

        val plan = history.merge(listOf(them("new text", "6:30 pm"), me("b"), them("c")))

        assertThat(history.texts()).containsExactly("old text", "new text", "b", "c").inOrder()
        assertThat(plan.edits).isEmpty()
    }

    @Test
    fun `a changed message whose time is not shown is not taken for an edit`() {
        val history = historyOf(them("a", sender = "Ravi"), them("old", "6:30 pm", sender = "Ravi"), them("c"))

        history.merge(listOf(them("a", sender = "Ravi"), them("new", time = null, sender = "Ravi"), them("c")))

        assertThat(history.texts()).containsExactly("a", "old", "new", "c").inOrder()
    }

    @Test
    fun `a changed group message whose sender is not shown is not taken for an edit`() {
        val history = historyOf(them("a", sender = "Ravi"), them("old", "6:30 pm", sender = "Ravi"), them("c"))

        history.merge(listOf(them("a", sender = "Ravi"), them("new", "6:30 pm", sender = null), them("c")))

        assertThat(history.texts()).containsExactly("a", "old", "new", "c").inOrder()
    }

    @Test
    fun `a different message with another time is not taken for an edit`() {
        val history = historyOf(them("x"), them("a"), me("b", "6:41 pm"), them("z", "6:55 pm"))

        history.merge(listOf(them("x"), them("a"), me("c", "6:50 pm"), them("z", "6:55 pm")))

        assertThat(history.texts()).containsExactly("x", "a", "b", "c", "z").inOrder()
    }

    @Test
    fun `a message from someone else is not taken for an edit`() {
        val history = historyOf(them("a"), me("b", "6:41 pm"), them("z"))

        history.merge(listOf(them("a"), them("c", "6:41 pm"), them("z")))

        assertThat(history.texts()).containsExactly("a", "b", "c", "z").inOrder()
    }

    @Test
    fun `a message missing from the screen is kept`() {
        val history = historyOf(them("a"), me("b"), them("c"), me("d"), them("e"), me("f"))

        history.merge(listOf(them("a"), them("c"), me("d"), them("e"), me("f"), them("g")))

        assertThat(history.texts()).containsExactly("a", "b", "c", "d", "e", "f", "g").inOrder()
    }

    @Test
    fun `a message that appears between saved ones is inserted there`() {
        val history = historyOf(them("a"), them("c", "6:50 pm"))

        history.merge(listOf(them("a"), me("b", "6:45 pm"), them("c", "6:50 pm")))

        assertThat(history.texts()).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `a bubble whose time is cut off matches the saved message`() {
        val history = historyOf(them("a", "6:40 pm"), me("b"))

        val plan = history.merge(listOf(them("a", time = null), me("b"), them("c")))

        assertThat(history.texts()).containsExactly("a", "b", "c").inOrder()
        assertThat(plan.edits).isEmpty()
    }

    @Test
    fun `a group bubble without its sender label matches the saved message`() {
        val history = historyOf(them("a", sender = "Ravi"), them("b", sender = "Ravi"), me("c"))

        val plan = history.merge(listOf(them("a", sender = null), them("b", sender = null), me("c"), me("d")))

        assertThat(history.texts()).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(plan.edits).isEmpty()
        assertThat(history.messages()[1].sender).isEqualTo("Ravi")
    }

    @Test
    fun `a later read fills in the sender of a saved message that had none`() {
        val history = historyOf(them("a", sender = null), them("b", sender = null), me("c"))

        val plan = history.merge(listOf(them("a", sender = "Ravi"), them("b", sender = "Ravi"), me("c")))

        assertThat(history.messages().map { it.sender }).containsExactly("Ravi", "Ravi", null).inOrder()
        assertThat(plan.inserts).isEmpty()
    }

    @Test
    fun `a sender is not filled in from a read that did not overlap`() {
        val history = historyOf(them("ok", "6:40 pm", sender = null), me("x"))

        history.merge(listOf(them("ok", "6:40 pm", sender = "Ravi"), me("y", "7:00 pm")))

        assertThat(history.messages().first().sender).isNull()
    }

    @Test
    fun `the same text from two group members are two messages`() {
        val history = historyOf(them("yes", sender = "Ravi"))

        history.merge(listOf(them("yes", sender = "Ravi"), them("yes", sender = "Meera")))

        assertThat(history.messages().map { it.sender }).containsExactly("Ravi", "Meera").inOrder()
    }

    @Test
    fun `a group read whose top run has no sender labels anchors on the saved run`() {
        val history =
            historyOf(
                them("x", "6:30 pm", sender = "Ravi"),
                them("a", "6:40 pm", sender = "Ravi"),
                them("b", "6:41 pm", sender = "Meera"),
            )

        val plan =
            history.merge(
                listOf(
                    them("a", "6:40 pm", sender = null),
                    them("b", "6:41 pm", sender = null),
                    them("c", "6:42 pm", sender = "Meera"),
                ),
            )

        assertThat(plan.anchored).isTrue()
        assertThat(history.texts()).containsExactly("x", "a", "b", "c").inOrder()
        assertThat(plan.newAtEnd.map { it.text }).containsExactly("c")
        assertThat(history.messages().map { it.sender }).containsExactly("Ravi", "Ravi", "Meera", "Meera").inOrder()
    }

    @Test
    fun `the same text and time from a different known sender does not match`() {
        val history = historyOf(them("a", "6:40 pm", sender = "Ravi"), them("b", "6:41 pm", sender = "Ravi"))

        val plan = history.merge(listOf(them("a", "6:40 pm", sender = "Meera"), them("b", "6:41 pm", sender = "Meera")))

        assertThat(plan.anchored).isFalse()
        assertThat(history.messages().map { it.sender }).containsExactly("Ravi", "Ravi", "Meera", "Meera").inOrder()
    }

    // Adversarial scenarios from the #3 review (A, A2, B, C, D).

    @Test
    fun `scenario A - a 2-message read with one coincidental match is appended`() {
        // Yesterday's "gm 9:00 am" equals today's: one match is not an overlap.
        val history =
            historyOf(
                them("gm", "9:00 am"),
                me("gm!", "9:01 am"),
                them("lunch?", "1:00 pm"),
                me("sure", "1:02 pm"),
                them("bye", "8:00 pm"),
            )

        val plan = history.merge(listOf(them("gm", "9:00 am"), me("morning", "9:05 am")))

        assertThat(history.texts()).containsExactly("gm", "gm!", "lunch?", "sure", "bye", "gm", "morning").inOrder()
        assertThat(plan.anchored).isFalse()
    }

    @Test
    fun `scenario A2 - a coincidental match does not overwrite the message after it`() {
        val history =
            historyOf(
                them("gm", "9:00 am"),
                them("morning", "9:02 am"),
                me("hi", "9:03 am"),
                them("lunch?", "1:00 pm"),
            )

        val plan = history.merge(listOf(them("gm", "9:00 am"), them("hey", "9:02 am")))

        assertThat(history.texts()).containsExactly("gm", "morning", "hi", "lunch?", "gm", "hey").inOrder()
        assertThat(plan.edits).isEmpty()
    }

    @Test
    fun `scenario A2 anchored - a same-time message after the last aligned one is not an edit`() {
        val history = historyOf(them("a"), them("b"), them("morning", "9:02 am"))

        val plan = history.merge(listOf(them("a"), them("b"), them("hey", "9:02 am")))

        assertThat(history.texts()).containsExactly("a", "b", "morning", "hey").inOrder()
        assertThat(plan.edits).isEmpty()
    }

    @Test
    fun `scenario B - a read after a gap that ends like the clipped first row is appended at the end`() {
        // The first read's top bubble had its time clipped; that row is the oldest saved one.
        val history =
            historyOf(
                me("ok", time = null),
                them("see you at 7", "6:50 pm"),
                me("done", "6:51 pm"),
                them("great", "6:52 pm"),
            )

        val plan =
            history.merge(
                listOf(
                    them("are you there", "8:00 pm"),
                    me("yes", "8:01 pm"),
                    them("call me", "8:02 pm"),
                    me("in 5", "8:03 pm"),
                    me("ok", "8:03 pm"),
                ),
            )

        assertThat(history.texts())
            .containsExactly("ok", "see you at 7", "done", "great", "are you there", "yes", "call me", "in 5", "ok")
            .inOrder()
        assertThat(plan.anchored).isFalse()
    }

    @Test
    fun `scenario B - one exact match at the top of the history is not an overlap either`() {
        val history = historyOf(me("ok", "8:03 pm"), them("see you at 7", "6:50 pm"), me("done", "6:51 pm"))

        history.merge(listOf(them("are you there", "8:00 pm"), me("in 5", "8:02 pm"), me("ok", "8:03 pm")))

        assertThat(history.texts())
            .containsExactly("ok", "see you at 7", "done", "are you there", "in 5", "ok")
            .inOrder()
    }

    @Test
    fun `scenario C - scrolling up over repeated oks adds the older ok at the top, not the end`() {
        val history = historyOf(me("ok"), me("ok"), them("b", "6:45 pm"))

        val plan = history.merge(listOf(me("ok"), me("ok"), me("ok")))

        assertThat(history.texts()).containsExactly("ok", "ok", "ok", "b").inOrder()
        assertThat(plan.newAtEnd).isEmpty()
    }

    @Test
    fun `scenario D - scrolling up after a gap does not overwrite the last message before the gap`() {
        val history = historyOf(them("plan?", "5:50 pm", sender = "Ravi"), them("see you", "6:00 pm", sender = "Meera"))
        // Opened again after a while: no overlap, so the read is appended after a gap.
        history.merge(
            listOf(
                them("m20", "7:20 pm", sender = "Ravi"),
                them("m21", "7:21 pm", sender = "Ravi"),
                them("m22", "7:22 pm", sender = "Ravi"),
            ),
        )

        // Scrolled up a little: the top bubble shows no time and no name label.
        val plan =
            history.merge(
                listOf(
                    them("m19", time = null, sender = null),
                    them("m20", "7:20 pm", sender = "Ravi"),
                    them("m21", "7:21 pm", sender = "Ravi"),
                ),
            )

        assertThat(history.texts()).containsExactly("plan?", "see you", "m19", "m20", "m21", "m22").inOrder()
        assertThat(history.messages()[1]).isEqualTo(them("see you", "6:00 pm", sender = "Meera"))
        assertThat(plan.edits).isEmpty()
    }

    @Test
    fun `a read whose top message is the saved last one anchors on that one match`() {
        val history = historyOf(them("a"), me("b"), them("c", "6:45 pm"))

        val plan = history.merge(listOf(them("c", "6:45 pm"), me("d", "6:46 pm")))

        assertThat(history.texts()).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(plan.newAtEnd.map { it.text }).containsExactly("d")
    }

    @Test
    fun `a single match scrolled up is not an overlap`() {
        // Known limit of the 2-match rule: a scroll-up showing one saved message is appended.
        val history = historyOf(them("m5"), them("m6"))

        val plan = history.merge(listOf(them("m3"), them("m4"), them("m5")))

        assertThat(plan.anchored).isFalse()
        assertThat(history.texts()).containsExactly("m5", "m6", "m3", "m4", "m5").inOrder()
    }

    @Test
    fun `a match made only by a missing time or sender does not anchor a read`() {
        val history = historyOf(them("x"), them("ok", "6:40 pm", sender = "Ravi"), them("y"))

        val plan = history.merge(listOf(them("ok", time = null, sender = null), me("z", "7:00 pm")))

        assertThat(plan.anchored).isFalse()
    }

    @Test
    fun `planning against the tail matches planning against the whole chat for reads at the tail`() {
        val stored = storedRows((1..20).map { them("m$it") })
        val read = listOf(them("m18"), them("m19"), them("m20"), me("new"))

        val tailPlan = HistoryMerge.planAgainstTail(stored.takeLast(5), read)

        assertThat(tailPlan).isEqualTo(HistoryMerge.plan(stored, read))
    }

    @Test
    fun `planning against the tail gives up when the read may concern older rows`() {
        val stored = storedRows((1..20).map { them("m$it") })
        val tail = stored.takeLast(5)

        // No overlap with the tail.
        assertThat(HistoryMerge.planAgainstTail(tail, listOf(them("m3"), them("m4"), them("m5")))).isNull()
        // Reaches above the tail: m14 and m15 may already be saved.
        assertThat(HistoryMerge.planAgainstTail(tail, listOf(them("m14"), them("m15"), them("m16"), them("m17"))))
            .isNull()
        // No room left between neighbours: a renumber needs every row of the chat.
        val tight = tail.mapIndexed { i, row -> row.copy(position = i.toLong()) }
        assertThat(HistoryMerge.planAgainstTail(tight, listOf(them("m17"), them("x"), them("m18")))).isNull()
    }

    private fun storedRows(messages: List<ChatMessage>) =
        messages.mapIndexed {
            i,
            m,
            ->
            StoredRow(id = i + 1L, position = (i + 1) * HistoryMerge.POSITION_STEP, message = m)
        }

    @Test
    fun `many inserts into a tight gap renumber positions without losing order`() {
        val history = historyOf(them("first", "1:00 pm"), them("last", "9:00 pm"))
        repeat(20) { round ->
            val inserted = (0..round).map { them("m$it", "2:${it.toString().padStart(2, '0')} pm") }
            history.merge(listOf(them("first", "1:00 pm")) + inserted + them("last", "9:00 pm"))
        }

        val expected = listOf("first") + (0 until 20).map { "m$it" } + "last"
        assertThat(history.texts()).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun `the message hash covers sender, text and time shown`() {
        val base = them("ok", "6:40 pm", sender = "Asha")

        assertThat(MessageHash.of(base)).isEqualTo(MessageHash.of(base.copy()))
        assertThat(MessageHash.of(base)).isNotEqualTo(MessageHash.of(base.copy(sender = "Ravi")))
        assertThat(MessageHash.of(base)).isNotEqualTo(MessageHash.of(base.copy(text = "ok!")))
        assertThat(MessageHash.of(base)).isNotEqualTo(MessageHash.of(base.copy(timeShown = "6:41 pm")))
        assertThat(MessageHash.of(me("ok"))).isNotEqualTo(MessageHash.of(them("ok", "6:41 pm", sender = null)))
    }
}
