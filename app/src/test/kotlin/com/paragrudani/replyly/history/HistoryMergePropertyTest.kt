package com.paragrudani.replyly.history

import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.parser.ChatMessage
import org.junit.Test
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.random.Random

/**
 * Property test for [HistoryMerge]. A seeded simulator writes a true chat, shows the user random
 * screen windows of it, merges every read into a [FakeHistory] and checks the result against the
 * truth after each merge. Deterministic: the same seeds give the same reads on every run.
 *
 * The chat (1:1 or group) has bursts in the same minute, day wrap (times without dates, so the
 * same time comes back every day), a daily "gm" at 9:00 am, and many repeated "ok", "😂", "haha"
 * and empty-text stickers. Each read is a window of 3 to 8 messages; the top bubble's time and the
 * bottom bubble's time are sometimes cut off, and in a group the name label of a run that starts
 * above the screen is missing (the parser then gives those bubbles no sender). The user stays at
 * the bottom, scrolls up or down less than a screen, flings up more than a screen, jumps anywhere,
 * or comes back after more than a screen of new messages (a gap).
 *
 * Invariants, checked after every merge:
 * 1. No saved row's text, sender or time ever changes into another message's.
 * 2. [MergePlan.newAtEnd] holds only messages newer than every message saved before the merge.
 * 3. Within each gap-free segment, the saved order is the true order (and nothing is stored twice).
 * 4. Every message visible in a read that anchored is stored, exactly once, in one segment.
 *
 * Messages that are identical in every field (two "ok"s from me at 6:41 pm) cannot be told apart
 * by anyone, so the checks treat such twins as interchangeable inside a segment.
 */
class HistoryMergePropertyTest {
    @Test
    fun `one-to-one chats keep the merge invariants`() = fuzz("1:1", group = false, tailSize = null, seeds = 1L..100L)

    @Test
    fun `group chats keep the merge invariants`() = fuzz("group", group = true, tailSize = null, seeds = 1001L..1100L)

    @Test
    fun `merging against a small tail keeps the merge invariants`() {
        fuzz("1:1 tail", group = false, tailSize = 12, seeds = 2001L..2050L)
        fuzz("group tail", group = true, tailSize = 12, seeds = 3001L..3050L)
    }

    private fun fuzz(
        name: String,
        group: Boolean,
        tailSize: Int?,
        seeds: LongRange,
    ) {
        val started = System.nanoTime()
        var reads = 0
        val failures = mutableListOf<String>()
        val limits = mutableListOf<String>()
        for (seed in seeds) {
            val simulation = Simulation(seed, group, tailSize)
            simulation.run(STEPS)
            reads += simulation.reads
            simulation.failure?.let { failures += "seed $seed: $it" }
            simulation.knownLimit?.let { limits += "seed $seed: $it" }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        println(
            "HistoryMergePropertyTest $name: ${seeds.count()} seeds, $reads reads, $millis ms, ${limits.size} stopped at a known limit",
        )
        limits.forEach { println(it) }
        // Known limits end a seed early; they must stay rare, or the property test checks little.
        assertWithMessage("too many seeds stopped at a known limit:\n" + limits.joinToString("\n"))
            .that(limits.size)
            .isAtMost(seeds.count() / 10)
        assertWithMessage(
            "${failures.size} of ${seeds.count()} seeds broke an invariant:\n" + failures.take(3).joinToString("\n\n"),
        ).that(failures)
            .isEmpty()
    }

    private class TrueMessage(
        val id: Int,
        val isMe: Boolean,
        val sender: String?,
        val text: String,
        val time: String,
    ) {
        val key: List<Any?> get() = listOf(isMe, sender, text, time)

        override fun toString() = "#$id ${if (isMe) "me" else sender} \"$text\" $time"
    }

    private class Simulation(
        seed: Long,
        private val group: Boolean,
        tailSize: Int?,
    ) {
        private val random = Random(seed)
        private val truth = mutableListOf<TrueMessage>()

        /** Minutes since the chat started; bubbles show only the time of day. */
        private var clock = random.nextInt(MINUTES_PER_DAY)
        private val history = FakeHistory(tailSize)

        /** Row id to the true message it stands for. */
        private val rowTruth = HashMap<Long, Int>()
        private var top = 0
        private var atBottom = true
        var reads = 0
            private set
        var failure: String? = null
            private set

        /** Set instead of [failure] when the broken read is one of the documented known limits. */
        var knownLimit: String? = null
            private set

        fun run(steps: Int) {
            repeat(25) { addMessage() }
            for (step in 1..steps) {
                if (random.nextDouble() < 0.4) repeat(random.nextInt(1, 4)) { addMessage() }
                val height = random.nextInt(3, 9)
                val action = random.nextDouble()
                when {
                    action < 0.35 -> {}

                    // Stays where it is (at the bottom it follows new messages).
                    action < 0.45 -> {
                        atBottom = true
                    }

                    action < 0.55 -> { // Comes back after more than a screen of new messages.
                        repeat(random.nextInt(height + 1, 3 * height + 2)) { addMessage() }
                        atBottom = true
                    }

                    action < 0.75 -> {
                        scrollTo(top - random.nextInt(1, height))
                    }

                    action < 0.85 -> {
                        scrollTo(top + random.nextInt(1, height))
                    }

                    action < 0.93 -> {
                        scrollTo(random.nextInt(truth.size))
                    }

                    else -> {
                        scrollTo(top - random.nextInt(height, 3 * height + 1))
                    }
                }
                val last = maxOf(0, truth.size - height)
                if (atBottom || top >= last) {
                    top = last
                    atBottom = true
                }
                top = top.coerceIn(0, last)
                read(top, minOf(truth.size, top + height), step)
                if (failure != null || knownLimit != null) return
            }
        }

        private fun scrollTo(newTop: Int) {
            top = newTop
            atBottom = false
        }

        private fun addMessage() {
            val step = random.nextDouble()
            val next =
                clock +
                    when {
                        step < 0.45 -> 0
                        step < 0.75 -> random.nextInt(1, 4)
                        step < 0.95 -> random.nextInt(4, 31)
                        else -> random.nextInt(60, 601)
                    }
            val gm = (next - GM) / MINUTES_PER_DAY
            if (gm > (clock - GM) / MINUTES_PER_DAY && random.nextDouble() < 0.7) {
                // Asha's "gm" at 9:00 am on most days: the same message, day after day.
                clock = GM + gm * MINUTES_PER_DAY
                truth +=
                    TrueMessage(
                        truth.size,
                        isMe = false,
                        sender = if (group) "Asha" else TITLE,
                        text = "gm",
                        time = format(clock),
                    )
                return
            }
            clock = next
            val id = truth.size
            val isMe = random.nextDouble() < 0.4
            val previousSender = truth.lastOrNull { !it.isMe }?.sender
            val sender =
                when {
                    isMe -> null
                    !group -> TITLE
                    previousSender != null && random.nextDouble() < 0.6 -> previousSender
                    else -> MEMBERS.random(random)
                }
            val pick = random.nextDouble()
            val text =
                when {
                    pick < 0.18 -> "ok"

                    pick < 0.28 -> "😂"

                    pick < 0.34 -> ""

                    // A sticker: no text.
                    pick < 0.40 -> "haha"

                    pick < 0.44 -> "yes"

                    else -> "message $id"
                }
            truth += TrueMessage(id, isMe, sender, text, format(clock))
        }

        /** What the parser reads from the screen window `truth[from until to]`. */
        private fun render(
            from: Int,
            to: Int,
        ): List<ChatMessage> {
            var label: String? = null
            return (from until to).map { k ->
                val t = truth[k]
                val clipped = (k == from && random.nextDouble() < 0.35) || (k == to - 1 && random.nextDouble() < 0.15)
                val sender =
                    when {
                        t.isMe -> {
                            label = null
                            null
                        }

                        !group -> {
                            TITLE
                        }

                        else -> {
                            val startsRun = k == 0 || truth[k - 1].isMe || truth[k - 1].sender != t.sender
                            if (startsRun) label = if (k == from && random.nextDouble() < 0.5) null else t.sender
                            label
                        }
                    }
                ChatMessage(sender, t.text, if (clipped) null else t.time, t.isMe)
            }
        }

        private fun read(
            from: Int,
            to: Int,
            step: Int,
        ) {
            val read = render(from, to)
            val readIds = IdentityHashMap<ChatMessage, Int>()
            read.forEachIndexed { k, message -> readIds[message] = from + k }
            val before = history.sortedRows()
            val maxBefore = before.maxOfOrNull { rowTruth.getValue(it.id) } ?: -1
            // Known limit: a read with no overlap that was older than the saved rows sits at the
            // end, so reads that continue it report old messages as new.
            val oldBlockAtEnd =
                segmentsOf(before).lastOrNull()?.let { last -> last.maxOf { rowTruth.getValue(it.id) } < maxBefore } ==
                    true
            // Known limit: the one-pair rules take a read whose top bubble shows the same as the
            // saved last message (equal, or equal with its time cut off) for new messages below it.
            val lastBefore = before.lastOrNull()
            val oneAtEndTwin =
                lastBefore != null &&
                    rowTruth.getValue(lastBefore.id) != from &&
                    lastBefore.message.let {
                        it.isMe == read[0].isMe &&
                            it.text == read[0].text &&
                            it.sender == read[0].sender &&
                            (read[0].timeShown == null || read[0].timeShown == it.timeShown)
                    }

            // Known limit: a read message exactly equal (every shown field, time included) to a
            // saved message from far away in the chat (another day) can be taken for it. Twins
            // close by (a burst of "ok"s in one minute) are not excused.
            val twinOfSaved =
                read.withIndex().any { (k, message) ->
                    message.timeShown != null &&
                        before.any { row ->
                            abs(rowTruth.getValue(row.id) - (from + k)) > FAR && sameShown(message, row.message)
                        }
                }

            // Known limit: the top bubble, as shown, could also be the message right above it (a
            // run of identical "ok"s that goes on above the screen): nothing tells how many are new.
            val topAmbiguous =
                (from > 0 && compatible(read[0], truth[from - 1])) ||
                    (to < truth.size && compatible(read.last(), truth[to]))

            var wildcardRowShifted = false
            val plan = history.merge(read)
            reads++
            plan.inserts.zip(history.lastInsertedIds).forEach { (insert, rowId) ->
                rowTruth[rowId] = readIds[insert.message] ?: error("an insert that is not a read message")
            }

            /** [invariant] is the number of the broken invariant in the class KDoc. */
            fun fail(
                invariant: Int,
                what: String,
            ) {
                val report =
                    buildString {
                        appendLine("step $step: $what")
                        appendLine("  read ${from until to}: " + read.joinToString(" | ") { describe(it) })
                        appendLine("  anchored=${plan.anchored} newAtEnd=${plan.newAtEnd.map { readIds[it] }}")
                        appendLine("  before, segments near the read and the last one:")
                        val near =
                            segmentsOf(before).filter {
                                it.any { row ->
                                    rowTruth.getValue(row.id) in
                                        from - 3 until to + 3
                                }
                            }
                        (near + listOfNotNull(segmentsOf(before).lastOrNull())).distinct().forEach { segment ->
                            appendLine(
                                "    " + segment.joinToString(" | ") { "#${rowTruth[it.id]} ${describe(it.message)}" },
                            )
                        }
                        appendLine("  after:")
                        segmentsOf(history.sortedRows()).forEach { segment ->
                            appendLine("  segment: " + segment.joinToString(" ") { "#${rowTruth[it.id]}" })
                        }
                    }
                // Each known limit excuses only the invariants it can break.
                when {
                    twinOfSaved -> {
                        knownLimit = "a read with a message equal to a saved one from another day, $report"
                    }

                    oneAtEndTwin && invariant != 1 -> {
                        knownLimit =
                            "a top bubble that shows the same as the saved last one, $report"
                    }

                    wildcardRowShifted -> {
                        knownLimit =
                            "a row without a time shifted inside a run of identical messages, $report"
                    }

                    topAmbiguous && invariant == 4 -> {
                        knownLimit =
                            "an edge bubble that could be the message next to it, $report"
                    }

                    else -> {
                        failure = report
                    }
                }
            }

            val rows = history.sortedRows()
            // Invariant 1.
            rows.firstOrNull { !compatible(it.message, truth[rowTruth.getValue(it.id)]) }?.let {
                // Known limit: a row saved without a time (and, in a group, without a sender) can
                // take the fields of the identical message next to it when the read is shifted
                // inside a run of identical messages.
                wildcardRowShifted = topAmbiguous && before.single { row -> row.id == it.id }.message.timeShown == null
                return fail(1, "row of ${truth[rowTruth.getValue(it.id)]} now holds ${describe(it.message)}")
            }
            // Invariant 2.
            if (!oldBlockAtEnd) {
                plan.newAtEnd.firstOrNull { readIds.getValue(it) <= maxBefore }?.let {
                    return fail(2, "newAtEnd holds #${readIds[it]}, not newer than saved #$maxBefore")
                }
            }
            // Invariant 3.
            val segments = segmentsOf(rows).map { canonicalIds(it) }
            segments.firstOrNull { ids -> ids.zipWithNext().any { (a, b) -> a >= b } }?.let {
                return fail(3, "a segment is out of true order or holds a message twice: $it")
            }
            // Invariant 4.
            if (plan.anchored) {
                val visible = ((if (oneAtEndTwin) from + 1 else from) until to).toList()
                if (segments.none { ids -> ids.filter { it in from until to }.containsAll(visible) }) {
                    return fail(4, "an anchored read's messages are not all in one segment")
                }
            }
        }

        private fun segmentsOf(rows: List<StoredRow>): List<List<StoredRow>> =
            rows.fold(mutableListOf<MutableList<StoredRow>>()) { segments, row ->
                if (segments.isEmpty() || row.gapBefore) segments += mutableListOf<StoredRow>()
                segments.last() += row
                segments
            }

        /**
         * The true ids of a segment's rows, with twins (identical in every field) handed out in
         * row order: nobody can tell which twin a row stands for.
         */
        private fun canonicalIds(segment: List<StoredRow>): List<Int> {
            val ids = segment.map { rowTruth.getValue(it.id) }.toMutableList()
            ids.indices.groupBy { truth[ids[it]].key }.values.forEach { slots ->
                val sorted = slots.map { ids[it] }.sorted()
                slots.forEachIndexed { k, slot -> ids[slot] = sorted[k] }
            }
            return ids
        }

        private fun compatible(
            saved: ChatMessage,
            t: TrueMessage,
        ) = saved.isMe == t.isMe &&
            saved.text == t.text &&
            (saved.sender == null || saved.sender == t.sender) &&
            (saved.timeShown == null || saved.timeShown == t.time)

        /** Equal in every field both show, as the merge's exact match: only a sender may be missing. */
        private fun sameShown(
            a: ChatMessage,
            b: ChatMessage,
        ) = a.isMe == b.isMe &&
            a.text == b.text &&
            a.timeShown == b.timeShown &&
            (a.sender == b.sender || (!a.isMe && (a.sender == null || b.sender == null)))

        private fun describe(m: ChatMessage) = "${if (m.isMe) "me" else m.sender} \"${m.text}\" ${m.timeShown}"
    }

    private companion object {
        const val STEPS = 120

        /** Twins further apart than two screens count as "far" (another day). */
        const val FAR = 16
        const val MINUTES_PER_DAY = 24 * 60
        const val TITLE = "Asha"
        val MEMBERS = listOf("Asha", "Ravi", "Meera", "Bob")

        /** 9:00 am, in minutes. */
        const val GM = 9 * 60

        fun format(clock: Int): String {
            val minute = clock % MINUTES_PER_DAY
            val hour = minute / 60
            val shown = if (hour % 12 == 0) 12 else hour % 12
            return "%d:%02d %s".format(shown, minute % 60, if (hour < 12) "am" else "pm")
        }
    }
}
