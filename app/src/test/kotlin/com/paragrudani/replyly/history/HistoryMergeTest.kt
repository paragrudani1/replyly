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
        val history = historyOf(them("a"), me("see you soon"), me("see you soon"))

        history.merge(listOf(me("see you soon"), them("b")))

        assertThat(history.texts()).containsExactly("a", "see you soon", "see you soon", "b").inOrder()
    }

    @Test
    fun `a short repeated message as the only overlap is appended after a gap`() {
        // Known limit of the one-pair rule: "ok" repeats too often to anchor a read on its own.
        val history = historyOf(them("a"), me("ok"), me("ok"))

        history.merge(listOf(me("ok"), them("b")))

        assertThat(history.segments()).containsExactly(listOf("a", "ok", "ok"), listOf("ok", "b")).inOrder()
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
        val history = historyOf(them("are we meeting?"), me("see you at 6", "6:41 pm"), them("c"))

        val plan = history.merge(listOf(them("are we meeting?"), me("see you at 7", "6:41 pm"), them("c")))

        assertThat(history.texts()).containsExactly("are we meeting?", "see you at 7", "c").inOrder()
        assertThat(plan.inserts).isEmpty()
        assertThat(plan.edits).hasSize(1)
    }

    @Test
    fun `a changed last message is not an edit, and the read is appended after a gap`() {
        // Edits are looked for only between two aligned messages. At the edge, a different message
        // where the saved row should be means the overlap is a coincidence (known limit).
        val history = historyOf(them("a"), me("b"), me("typo", "6:45 pm"))

        val plan = history.merge(listOf(them("a"), me("b"), me("fixed", "6:45 pm")))

        assertThat(history.segments()).containsExactly(listOf("a", "b", "typo"), listOf("a", "b", "fixed")).inOrder()
        assertThat(plan.edits).isEmpty()
        assertThat(plan.anchored).isFalse()
    }

    @Test
    fun `an edit right after an identical message is stored as a new message`() {
        // The read's "ok" may be the second saved one, the first skipped (deleted for me): then
        // "ok!" is new. Undecidable, and an insert never overwrites a message (known limit).
        val history =
            historyOf(them("are you coming?"), me("ok", "6:45 pm"), me("ok", "6:45 pm"), them("d", "6:46 pm"))

        val plan =
            history.merge(
                listOf(them("are you coming?"), me("ok", "6:45 pm"), me("ok!", "6:45 pm"), them("d", "6:46 pm")),
            )

        assertThat(history.texts()).containsExactly("are you coming?", "ok", "ok", "ok!", "d").inOrder()
        assertThat(plan.edits).isEmpty()
    }

    @Test
    fun `a typo fix right after a run of equal messages is stored as a new message, not the whole screen`() {
        // PR #27 review round 3, S1: the "haan" pair could move one row along the run and leave the
        // fix an edit of the second "haan", so it is not an edit; the rest of the screen still lines up.
        val history =
            historyOf(
                them("are you coming tonight", "9:00 pm"),
                me("haan", "9:01 pm"),
                me("haan", "9:01 pm"),
                me("reaching by 10 pn", "9:01 pm"),
                them("great see you then", "9:03 pm"),
                me("bring the charger pls", "9:04 pm"),
            )

        val plan =
            history.merge(
                listOf(
                    them("are you coming tonight", "9:00 pm"),
                    me("haan", "9:01 pm"),
                    me("haan", "9:01 pm"),
                    me("reaching by 10 pm", "9:01 pm"),
                    them("great see you then", "9:03 pm"),
                    me("bring the charger pls", "9:04 pm"),
                ),
            )

        assertThat(plan.anchored).isTrue()
        assertThat(history.texts())
            .containsExactly(
                "are you coming tonight",
                "haan",
                "haan",
                "reaching by 10 pn",
                "reaching by 10 pm",
                "great see you then",
                "bring the charger pls",
            ).inOrder()
    }

    @Test
    fun `a distinctive message wins the pairing over an identical short one next to it`() {
        // An earlier read missed the first "ok". The read shows "ok", "message 12"; the saved side
        // has "message 12", "ok": pair "message 12", and the "ok" goes before it.
        val history =
            historyOf(
                them("message 9", "8:47 am", sender = "Bob"),
                me("message 10", "8:50 am"),
                me("message 12", "8:52 am"),
                them("ok", "8:52 am", sender = "Bob"),
            )

        val plan =
            history.merge(
                listOf(
                    them("message 9", "8:47 am", sender = "Bob"),
                    me("message 10", "8:50 am"),
                    them("ok", "8:52 am", sender = "Bob"),
                    me("message 12", "8:52 am"),
                ),
            )

        assertThat(history.texts()).containsExactly("message 9", "message 10", "ok", "message 12", "ok").inOrder()
        assertThat(plan.newAtEnd).isEmpty()
    }

    @Test
    fun `a changed first visible message is not an edit, and the read is appended after a gap`() {
        val history = historyOf(them("old text", "6:30 pm"), me("b"), them("c"))

        val plan = history.merge(listOf(them("new text", "6:30 pm"), me("b"), them("c")))

        assertThat(history.segments())
            .containsExactly(listOf("old text", "b", "c"), listOf("new text", "b", "c"))
            .inOrder()
        assertThat(plan.edits).isEmpty()
    }

    @Test
    fun `an alignment made only of short messages never edits`() {
        // "gm" and "ok" pairs can be another day: a same-time message between them is not an edit.
        val history = historyOf(them("gm", "9:00 am"), me("ok", "9:00 am"), them("ok", "9:00 am"))

        val plan = history.merge(listOf(them("gm", "9:00 am"), me("message 321", "9:00 am"), them("ok", "9:00 am")))

        assertThat(plan.edits).isEmpty()
        assertThat(history.messages().map { it.text }).contains("ok")
    }

    @Test
    fun `a read message pairs with the saved row that shows the same time, not a clipped one next to it`() {
        // A bubble above the read's last one is missing from the read (a parser miss): the last
        // one, "😂 5:57 pm", is the saved row with that time, not the clipped "😂" above it.
        val history =
            historyOf(
                them("message 202", "5:19 pm"),
                them("message 203", "5:21 pm"),
                me("😂", time = null),
                me("😂", "5:57 pm"),
            )

        val plan =
            history.merge(
                listOf(them("message 202", "5:19 pm"), them("message 203", "5:21 pm"), me("😂", "5:57 pm")),
            )

        assertThat(plan.isNoOp).isTrue()
        assertThat(history.lastMessages(1).single().message).isEqualTo(me("😂", "5:57 pm"))
    }

    @Test
    fun `a changed message whose time is not shown is not taken for an edit`() {
        val history = historyOf(them("a", sender = "Ravi"), them("old", "6:30 pm", sender = "Ravi"), them("c"))

        history.merge(listOf(them("a", sender = "Ravi"), them("new", time = null, sender = "Ravi"), them("c")))

        // Not an edit, and a slot where both sides would miss a message: appended after a gap.
        assertThat(history.segments()).containsExactly(listOf("a", "old", "c"), listOf("a", "new", "c")).inOrder()
    }

    @Test
    fun `a changed group message whose sender is not shown is not taken for an edit`() {
        val history = historyOf(them("a", sender = "Ravi"), them("old", "6:30 pm", sender = "Ravi"), them("c"))

        history.merge(listOf(them("a", sender = "Ravi"), them("new", "6:30 pm", sender = null), them("c")))

        assertThat(history.segments()).containsExactly(listOf("a", "old", "c"), listOf("a", "new", "c")).inOrder()
    }

    @Test
    fun `a different message with another time is not taken for an edit`() {
        val history = historyOf(them("x"), them("a"), me("b", "6:41 pm"), them("z", "6:55 pm"))

        history.merge(listOf(them("x"), them("a"), me("c", "6:50 pm"), them("z", "6:55 pm")))

        assertThat(history.segments())
            .containsExactly(listOf("x", "a", "b", "z"), listOf("x", "a", "c", "z"))
            .inOrder()
    }

    @Test
    fun `a message from someone else in the slot of a saved one makes the overlap a coincidence`() {
        // Both sides would miss a message in the same slot: more likely the pairs are not the same.
        val history = historyOf(them("a"), me("b", "6:41 pm"), them("z"))

        val plan = history.merge(listOf(them("a"), them("c", "6:41 pm"), them("z")))

        assertThat(history.segments()).containsExactly(listOf("a", "b", "z"), listOf("a", "c", "z")).inOrder()
        assertThat(plan.edits).isEmpty()
    }

    @Test
    fun `F3 - a substitution with another author is not credible`() {
        val history =
            historyOf(
                them("m131", "12:58 am"),
                me("😂", "1:19 am"),
                me("ok", "1:19 am"),
                me("😂", "1:19 am"),
            )

        val plan =
            history.merge(
                listOf(me("😂", "1:19 am"), them("m138", "1:19 am"), me("😂", "1:19 am"), them("m140", "1:48 am")),
            )

        assertThat(plan.anchored).isFalse()
        assertThat(history.segments().last()).containsExactly("😂", "m138", "😂", "m140").inOrder()
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
        val history = historyOf(them("see you there", "6:40 pm"), me("on my way"))

        val plan = history.merge(listOf(them("see you there", time = null), me("on my way"), them("c")))

        assertThat(history.texts()).containsExactly("see you there", "on my way", "c").inOrder()
        assertThat(plan.edits).isEmpty()
    }

    @Test
    fun `F4 - two short pairs with only one exact do not anchor`() {
        // One exact "ok" plus one time-less "ok" is too weak: "ok" repeats all the time.
        val history = historyOf(them("x1", "10:00 pm"), me("ok", "10:59 pm"), me("ok", "11:00 pm"), them("x2"))

        val plan = history.merge(listOf(me("ok", time = null), me("ok", "11:00 pm"), them("y", "11:02 pm")))

        assertThat(plan.anchored).isFalse()
    }

    @Test
    fun `F4 - two exact short pairs anchor`() {
        val history = historyOf(them("x1", "10:00 pm"), me("ok", "10:59 pm"), me("ok", "11:00 pm"))

        val plan = history.merge(listOf(me("ok", "10:59 pm"), me("ok", "11:00 pm"), them("y", "11:02 pm")))

        assertThat(plan.anchored).isTrue()
        assertThat(plan.newAtEnd.map { it.text }).containsExactly("y")
    }

    @Test
    fun `F4 - a long pair lets one exact pair anchor with a time-less one`() {
        val history = historyOf(them("x1", "10:00 pm"), me("see you tomorrow", "10:59 pm"), me("ok", "11:00 pm"))

        val plan =
            history.merge(
                listOf(me("see you tomorrow", time = null), me("ok", "11:00 pm"), them("y", "11:02 pm")),
            )

        assertThat(plan.anchored).isTrue()
        assertThat(history.texts()).containsExactly("x1", "see you tomorrow", "ok", "y").inOrder()
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
        val history = historyOf(me("x"), them("a", sender = null), them("b", sender = null), me("c"))

        val plan = history.merge(listOf(me("x"), them("a", sender = "Ravi"), them("b", sender = "Ravi"), me("c")))

        assertThat(history.messages().map { it.sender }).containsExactly(null, "Ravi", "Ravi", null).inOrder()
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
        val history = historyOf(them("count me in", sender = "Ravi"))

        history.merge(listOf(them("count me in", sender = "Ravi"), them("count me in", sender = "Meera")))

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

        assertThat(history.segments()).containsExactly(listOf("a", "b", "morning"), listOf("a", "b", "hey")).inOrder()
        assertThat(plan.edits).isEmpty()
    }

    @Test
    fun `F2 - a same-author burst next to the edge does not let a read in at the wrong slot`() {
        val history =
            historyOf(
                them("ok", null),
                them("ok", "11:19 am"),
                them("", "11:19 am"),
                them("yes", "11:19 am"),
                me("message 22", "11:19 am"),
                them("ok", "11:19 am"),
                them("ok", "11:19 am"),
                them("message 25", "11:19 am"),
            )

        val plan = history.merge(listOf(me("yes", "11:19 am"), them("ok", "11:19 am"), them("ok", "11:19 am")))

        // "me yes" above the pairs is not "me message 22": the read is not placed between them.
        assertThat(plan.anchored).isFalse()
        assertThat(history.segments().first())
            .containsExactly("ok", "ok", "", "yes", "message 22", "ok", "ok", "message 25")
            .inOrder()
        assertThat(plan.newAtEnd).isEmpty()
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
        val history = historyOf(them("a"), me("b"), them("see you at 6", "6:45 pm"))

        val plan = history.merge(listOf(them("see you at 6", "6:45 pm"), me("d", "6:46 pm")))

        assertThat(history.texts()).containsExactly("a", "b", "see you at 6", "d").inOrder()
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

    // Gap segments. Scenarios B1, B2, B3 and S1 are from the #3 re-review.

    @Test
    fun `a read with no overlap starts a new segment`() {
        val history = historyOf(them("a"), me("b"))

        val plan = history.merge(listOf(them("x"), me("y")))

        assertThat(plan.inserts.map { it.gapBefore }).containsExactly(true, false).inOrder()
        assertThat(history.segments()).containsExactly(listOf("a", "b"), listOf("x", "y")).inOrder()
    }

    @Test
    fun `the first read of a chat has no gap marker`() {
        val plan = FakeHistory().merge(listOf(them("a"), me("b")))

        assertThat(plan.inserts.map { it.gapBefore }).containsExactly(false, false)
    }

    @Test
    fun `B1 - a scroll-up after a gap stores older messages in their own segment, not as new`() {
        val history =
            historyOf(
                them("dinner at 8?", "6:00 pm"),
                me("sure", "6:01 pm"),
                them("great", "6:02 pm"),
                me("see you", "6:03 pm"),
            )
        // Next day, at the bottom: no overlap, appended after a gap.
        history.merge(
            listOf(them("morning", "9:00 am"), me("hey", "9:01 am"), them("coffee?", "9:02 am"), me("yes", "9:03 am")),
        )

        // Fling up into yesterday.
        val plan =
            history.merge(
                listOf(
                    them("great", "6:02 pm"),
                    me("see you", "6:03 pm"),
                    them("running late", "7:55 pm"),
                    me("no worries", "7:56 pm"),
                ),
            )

        assertThat(plan.anchored).isTrue()
        assertThat(plan.newAtEnd).isEmpty()
        assertThat(history.segments())
            .containsExactly(
                listOf("dinner at 8?", "sure", "great", "see you", "running late", "no worries"),
                listOf("morning", "hey", "coffee?", "yes"),
            ).inOrder()
    }

    @Test
    fun `B1 group - a scroll-up after a gap does not duplicate the appended block`() {
        val history =
            historyOf(
                them("dinner at 8?", "6:00 pm", sender = "Ravi"),
                me("sure", "6:01 pm"),
                them("great", "6:02 pm", sender = "Meera"),
                me("see you", "6:03 pm"),
            )
        history.merge(
            listOf(
                them("running late", "7:55 pm", sender = null),
                me("no worries", "7:56 pm"),
                them("morning", "9:00 am", sender = "Ravi"),
            ),
        )

        val plan =
            history.merge(
                listOf(
                    them("great", "6:02 pm", sender = "Meera"),
                    me("see you", "6:03 pm"),
                    them("running late", "7:55 pm", sender = "Meera"),
                    me("no worries", "7:56 pm"),
                ),
            )

        assertThat(plan.newAtEnd).isEmpty()
        // The read shows both sides of the gap, so it closes it: nothing is stored twice.
        assertThat(history.segments())
            .containsExactly(
                listOf("dinner at 8?", "sure", "great", "see you", "running late", "no worries", "morning"),
            )
        assertThat(history.messages()[4].sender).isEqualTo("Meera")
    }

    @Test
    fun `B2 - a same-time message across a gap is not taken for an edit`() {
        val history =
            historyOf(
                them("leaving?", "9:10 pm"),
                me("yes", "9:11 pm"),
                them("drive safe", "9:12 pm"),
                me("will do", "9:13 pm"),
            )
        // Tuesday, at the bottom: no overlap.
        history.merge(listOf(me("home now", "9:15 pm"), them("ok", "9:15 pm"), them("tue msg", "9:30 pm")))

        // Scroll up into Monday.
        val plan =
            history.merge(
                listOf(
                    them("drive safe", "9:12 pm"),
                    me("will do", "9:13 pm"),
                    me("on my way", "9:15 pm"),
                    them("ok", "9:15 pm"),
                ),
            )

        assertThat(plan.edits).isEmpty()
        assertThat(plan.newAtEnd).isEmpty()
        assertThat(history.segments())
            .containsExactly(
                listOf("leaving?", "yes", "drive safe", "will do", "on my way", "ok"),
                listOf("home now", "ok", "tue msg"),
            ).inOrder()
    }

    @Test
    fun `B2 group - a same-time message of the same member across a gap is not taken for an edit`() {
        val history =
            historyOf(
                them("leaving?", "10:20 pm", sender = "Asha"),
                me("yes", "10:21 pm"),
                them("drive safe", "10:22 pm", sender = "Bob"),
                me("will do", "10:23 pm"),
            )
        history.merge(listOf(them("😂", "10:30 pm", sender = "Bob"), them("ok", "10:30 pm", sender = "Asha")))

        val plan =
            history.merge(
                listOf(
                    them("drive safe", "10:22 pm", sender = "Bob"),
                    me("will do", "10:23 pm"),
                    them("lol", "10:30 pm", sender = "Bob"),
                    them("ok", "10:30 pm", sender = "Asha"),
                ),
            )

        assertThat(plan.edits).isEmpty()
        assertThat(history.segments())
            .containsExactly(
                listOf("leaving?", "yes", "drive safe", "will do", "lol", "ok"),
                listOf("😂", "ok"),
            ).inOrder()
    }

    @Test
    fun `B2 group - a sender is not filled in across a gap`() {
        val history =
            historyOf(
                them("leaving?", "10:20 pm", sender = "Asha"),
                me("yes", "10:21 pm"),
                them("drive safe", "10:22 pm", sender = "Bob"),
                me("will do", "10:23 pm"),
            )
        // Bob's "ok" at the top of the screen, his name label above it.
        history.merge(listOf(them("ok", "10:30 pm", sender = null), them("tue msg", "10:45 pm", sender = "Asha")))

        history.merge(
            listOf(
                them("drive safe", "10:22 pm", sender = "Bob"),
                me("will do", "10:23 pm"),
                them("ok", "10:30 pm", sender = "Asha"),
            ),
        )

        val bobsOk = history.messages().single { it.text == "ok" && it.sender != "Asha" }
        assertThat(bobsOk.sender).isNull()
        assertThat(history.segments())
            .containsExactly(
                listOf("leaving?", "yes", "drive safe", "will do", "ok"),
                listOf("ok", "tue msg"),
            ).inOrder()
    }

    @Test
    fun `B3 - a saved row without a time does not pull a lone match far off the diagonal`() {
        val history = historyOf(me("ok", time = null), them("a", "10:00"), me("b", "10:01"), me("😂", "10:06"))

        val plan =
            history.merge(
                listOf(them("c", "10:06"), me("ok", "10:06"), me("😂", "10:06"), me("d", "10:06"), me("e", "10:07")),
            )

        assertThat(plan.anchored).isFalse()
        assertThat(plan.newAtEnd).isEmpty()
        assertThat(history.segments())
            .containsExactly(listOf("ok", "a", "b", "😂"), listOf("c", "ok", "😂", "d", "e"))
            .inOrder()
    }

    @Test
    fun `a saved row without a time gets the time from an aligned read`() {
        val history = historyOf(them("z", "9:50"), me("ok", time = null), them("a", "10:00"), me("b", "10:01"))

        val plan = history.merge(listOf(them("z", "9:50"), me("ok", "9:58"), them("a", "10:00"), me("b", "10:01")))

        assertThat(plan.inserts).isEmpty()
        assertThat(history.messages()[1]).isEqualTo(me("ok", "9:58"))
    }

    @Test
    fun `a row without a time at the edge of the overlap is not filled in`() {
        // The read's top "ok 5:02" may be a message the saved side missed, right below the clipped
        // "ok" (4:48 in truth): filling in 5:02 could overwrite that row's real time.
        val history = historyOf(them("ok", time = null), them("ok", "5:05 pm"), them("message 188", "5:05 pm"))

        val plan =
            history.merge(listOf(them("ok", "5:02 pm"), them("ok", "5:05 pm"), them("message 188", "5:05 pm")))

        assertThat(plan.edits).isEmpty()
        assertThat(history.messages().first().timeShown).isNull()
    }

    @Test
    fun `S1 - a clipped top bubble that is the only overlap anchors the read`() {
        val history = historyOf(them("a"), them("b"), them("c"), me("see you at 6", "1:03 pm"))

        val plan =
            history.merge(
                listOf(
                    me("see you at 6", time = null),
                    them("e", "1:04 pm"),
                    them("f", "1:04 pm"),
                    them("g", "1:05 pm"),
                    me("h", "1:06 pm"),
                ),
            )

        assertThat(plan.anchored).isTrue()
        assertThat(history.segments()).containsExactly(listOf("a", "b", "c", "see you at 6", "e", "f", "g", "h"))
        assertThat(plan.newAtEnd.map { it.text }).containsExactly("e", "f", "g", "h").inOrder()
    }

    @Test
    fun `one pair does not anchor a short message like ok, which repeats too often`() {
        val history = historyOf(them("a"), them("b"), me("ok", "1:03 pm"))

        assertThat(history.merge(listOf(me("ok", time = null), them("e", "1:04 pm"))).anchored).isFalse()
        assertThat(history.merge(listOf(me("ok", "1:04 pm"), them("f", "1:05 pm"))).anchored).isFalse()
    }

    @Test
    fun `one pair does not anchor when the read fits another saved message as well`() {
        val history =
            historyOf(them("see you at 6", "1:03 pm"), them("x", "1:04 pm"), me("b"), them("see you at 6", "1:03 pm"))

        val plan = history.merge(listOf(them("see you at 6", "1:03 pm"), them("x", "1:04 pm"), them("y", "1:05 pm")))

        assertThat(plan.newAtEnd).isEmpty()
    }

    @Test
    fun `S1 - a clipped top bubble needs the same sender as the saved last message`() {
        val history = historyOf(them("a"), them("see you at 6", "1:03 pm", sender = "Ravi"))

        val plan = history.merge(listOf(them("see you at 6", time = null, sender = "Meera"), them("e", "1:04 pm")))

        assertThat(plan.anchored).isFalse()
    }

    @Test
    fun `scrolling up over a gap moves the marker to the oldest new message`() {
        val history = historyOf(them("a", "1:00 pm"), them("b", "1:01 pm"))
        history.merge(listOf(them("m3", "3:03 pm"), them("m4", "3:04 pm"), them("m5", "3:05 pm")))

        val plan =
            history.merge(
                listOf(them("m1", "3:01 pm"), them("m2", "3:02 pm"), them("m3", "3:03 pm"), them("m4", "3:04 pm")),
            )

        assertThat(plan.gapsCleared).hasSize(1)
        assertThat(history.segments())
            .containsExactly(listOf("a", "b"), listOf("m1", "m2", "m3", "m4", "m5"))
            .inOrder()
    }

    @Test
    fun `the current position follows the last read, also into an old block stuck at the end`() {
        val history = historyOf(them("a1", "6:00 pm"), me("a2", "6:01 pm"), them("a3", "6:02 pm"), me("a4", "6:03 pm"))
        assertThat(history.lastMessages(2).map { it.message.text }).containsExactly("a3", "a4").inOrder()

        // A jump far up: no overlap, so the old block lands after the newest rows.
        history.merge(listOf(them("o1", "1:00 pm"), me("o2", "1:01 pm"), them("o3", "1:02 pm")))
        assertThat(history.texts().last()).isEqualTo("o3")
        assertThat(history.lastMessages(2).map { it.message.text }).containsExactly("o2", "o3").inOrder()

        // Back at the bottom: the read anchors in the newest segment, before the old block.
        val back = history.merge(listOf(them("a3", "6:02 pm"), me("a4", "6:03 pm"), them("n1", "6:10 pm")))
        assertThat(history.segments().first()).containsExactly("a1", "a2", "a3", "a4", "n1").inOrder()
        assertThat(history.lastMessages(3).map { it.message.text }).containsExactly("a3", "a4", "n1").inOrder()
        // The current position was in the old block, so it is not known that n1 is new.
        assertThat(back.newAtEnd).isEmpty()

        // From now on, new messages at the bottom are reported again although the old block is last.
        val sent = history.merge(listOf(me("a4", "6:03 pm"), them("n1", "6:10 pm"), me("n2", "6:11 pm")))
        assertThat(sent.newAtEnd.map { it.text }).containsExactly("n2")
        assertThat(history.lastMessages(2).map { it.message.text }).containsExactly("n1", "n2").inOrder()
    }

    @Test
    fun `a re-read inside the history moves the current position without changing rows`() {
        val history = historyOf(them("a1", "6:00 pm"), me("a2", "6:01 pm"), them("a3", "6:02 pm"), me("a4", "6:03 pm"))

        val plan = history.merge(listOf(them("a1", "6:00 pm"), me("a2", "6:01 pm")))

        assertThat(plan.isNoOp).isTrue()
        assertThat(history.lastMessages(10).map { it.message.text }).containsExactly("a1", "a2").inOrder()
    }

    @Test
    fun `a read that shows both sides of a gap closes it`() {
        val history = historyOf(them("a", "1:00 pm"), them("b", "1:01 pm"), them("c", "1:02 pm"))
        history.merge(listOf(them("m3", "3:03 pm"), them("m4", "3:04 pm"), them("m5", "3:05 pm")))

        val plan =
            history.merge(
                listOf(
                    them("b", "1:01 pm"),
                    them("c", "1:02 pm"),
                    them("m1", "3:01 pm"),
                    them("m3", "3:03 pm"),
                    them("m4", "3:04 pm"),
                ),
            )

        assertThat(plan.newAtEnd).isEmpty()
        assertThat(history.segments()).containsExactly(listOf("a", "b", "c", "m1", "m3", "m4", "m5"))
    }
}
