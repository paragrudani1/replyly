package com.paragrudani.replyly.history

import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.parser.ChatMessage
import org.junit.Test
import java.time.LocalDate
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
 * and empty-text stickers. Each read is a window of 3 to [MergeFuzz.Config.maxHeight] messages; the
 * top bubble's time and the bottom bubble's time are sometimes cut off, in a group the name label
 * of a run that starts above the screen is missing (the parser then gives those bubbles no
 * sender), and now and then a bubble between the edges is missing from the read (a parser miss or
 * a message deleted for me). The user stays at the bottom, scrolls up or down less than a screen,
 * flings up more than a screen, jumps anywhere, or comes back after more than a screen of new
 * messages (a gap).
 *
 * With [MergeFuzz.Config.dates], the screen also shows date separators (issue #26): one above the
 * first message of each day, scrolling in and out with the window (the top day's separator is
 * sometimes just above the screen). A read message below a visible separator carries that day's
 * date, the others none. [MergeFuzz.Config.midnight] makes days roll over often, close to
 * midnight, so the same times and texts ("ok" at 11:58 pm) come back on many days.
 *
 * With [MergeFuzz.Config.editRate], a message sent at most [MergeFuzz.Config.editWindow] minutes
 * earlier gets a new text now and then (15 minutes as in WhatsApp, 48 hours as in Telegram): a
 * distinctive text stays distinctive, a short one ("ok") gets another short one ("ok!"). With
 * [MergeFuzz.Config.lateReadRate], some reads come a day or two after the last message, so an
 * edit is often read again first on a later day, without a separator on screen (PR #27 review
 * round 2, S1). A row may hold any text its message ever had; a row holding an old text next to
 * a row of the same segment that the read stored with the new one is the merge's documented
 * "edit stored as a new message" and is left out of invariant 3. The summary counts the edits,
 * the rows holding an edited message's current text, and every row that stands for a message
 * another row also stands for (mostly reads appended after a gap).
 *
 * Invariants, checked after every merge:
 * 1. No saved row's text, sender, time or exact date ever changes into another message's.
 * 2. [MergePlan.newAtEnd] holds only messages newer than every message saved before the merge.
 * 3. Within each gap-free segment, the saved order is the true order (and nothing is stored twice).
 * 4. Every message visible in a read that anchored is stored, exactly once, in one segment.
 * 5. `lastMessages` (the rows ending at the current position) ends with the read's last message:
 *    when the read anchored, the row it lined up with or was stored in; otherwise the last row of
 *    the segment the read created.
 * 6. A row stored for a read message showing a date has that exact date; a saved row such a
 *    message lined up with has that exact date or none (the merge dates a row only from a sure
 *    pairing, its step 8; invariant 1 checks that no row ever holds a wrong one). The summary
 *    says how many lined-up dated messages dated their row.
 *
 * Every merge gets the simulated capture day, and rows stored without a date get it as their
 * approximate date, as on the phone.
 *
 * Messages that are identical in every field (two "ok"s from me at 6:41 pm) cannot be told apart
 * by anyone, so the checks treat such twins as interchangeable inside a segment.
 *
 * A read message lined up with a row that stood for another message close by, whose saved
 * fields do not contradict it (an identical twin, or a row saved without a time or sender), makes
 * that row stand for the read message: nobody can tell the two apart.
 *
 * Known limits of the merge (its KDoc) excuse only the invariants they can break, and only when
 * the failure involves the rows the limit is about:
 * - far twin: a read message equal in every shown field to a saved one more than [FAR] messages
 *   away (another day's "gm 9:00 am"); only when the read lined up with that row (any invariant
 *   but 5) or the overwritten row is that row (invariant 1).
 * - one-pair twin: the read's top bubble shows the same as the row at the end (invariants 2-4).
 * - wildcard row shifted: a row saved without a time takes the time of the next or previous
 *   message, identical in author, sender and text and at most one minute apart (invariant 1).
 * - ambiguous edge: the top or bottom bubble, as shown, is also the message just outside the
 *   screen (invariants 4 and 5).
 * - drops in one slot: the read misses a bubble where the history missed another one. Invariant 1
 *   when the missed one is overwritten by a read message within two places that its segment
 *   lacked; invariant 3 when every inverted pair is that dropped message and one this read
 *   stored, within two places; invariants 4 and 5 when a read bubble shows the same as the
 *   dropped one next to it (invariant 4 also when the read lined a bubble up with the dropped
 *   message's row, saved without a time or sender).
 * - row without a time: a row saved without a time stands for a message the history missed right
 *   next to it, with the same author and text (invariant 4).
 * - identical run: every misplaced (invariant 3) or missing (invariant 4) message is a short
 *   text with another message of the same author and text at most [RUN] messages away; the read
 *   lined up one message off inside the run ("ok", "ok", "😂", "ok" in a chat of mostly short
 *   messages). The merge KDoc lists this limit.
 * A limit ends the seed and counts toward a cap of 10 % of seeds. An old message reported in
 * `newAtEnd` by a read whose true range touches a block older than other saved rows (invariant 2
 * only) leaves the history right: the seed goes on, and those reads count toward a cap of 5 % of
 * reads.
 *
 * The seeds are fixed. Outside them the merge still fails some seeds, never by an overwrite.
 * Measured on seeds 100000-119999 (20,000 per config, PR #27 round 1): 1:1 1 seed (0.005%),
 * group 11 (0.055%), 1:1 dates 5 (0.025%), group dates 12 (0.06%), 1:1 midnight 7 (0.035%),
 * group midnight 14 (0.07%); none break invariant 1. Replaying the dated configs' reads with
 * every date stripped (merge as on `main`) fails 5, 12, 11 and 14 seeds, two of them overwrites.
 * Round 2, seeds 500000-519999 (20,000 per config): the configs above and their tail variants
 * fail 7 to 11 seeds each (at most 0.055%); the edit configs fail 1:1 13, group 17, group 48 h 19,
 * group midnight 10 and 1:1 tail 13 seeds (at most 0.1%). None breaks invariant 1.
 * The failures are the documented limits in shapes the excuses above do not cover: mostly a short
 * bubble ("ok", "😂") lined up with a twin a few rows away inside a run of identical messages,
 * beyond [RUN] (seen: 1249 group, invariant 4; 6079 1:1 dates, invariant 3; 7085 group dates,
 * invariant 2), and a run ending at a row saved without a time that reports one old message in
 * `newAtEnd` (invariant 2, feedback log only).
 */
class HistoryMergePropertyTest {
    @Test
    fun `one-to-one chats keep the merge invariants`() =
        check(
            MergeFuzz.run(
                MergeFuzz.Config("1:1", group = false),
                1L..100L,
            ),
        )

    @Test
    fun `group chats keep the merge invariants`() =
        check(
            MergeFuzz.run(
                MergeFuzz.Config("group", group = true),
                1001L..1100L,
            ),
        )

    @Test
    fun `merging against a small tail keeps the merge invariants`() {
        check(MergeFuzz.run(MergeFuzz.Config("1:1 tail", group = false, tailSize = 12), 2001L..2050L))
        check(MergeFuzz.run(MergeFuzz.Config("group tail", group = true, tailSize = 12), 3001L..3050L))
    }

    @Test
    fun `reads with date separators keep the merge invariants`() {
        check(MergeFuzz.run(MergeFuzz.Config("1:1 dates", group = false, dates = true), 101L..300L))
        check(MergeFuzz.run(MergeFuzz.Config("group dates", group = true, dates = true), 1101L..1300L))
        check(
            MergeFuzz.run(MergeFuzz.Config("1:1 dates tail", group = false, dates = true, tailSize = 12), 2101L..2150L),
        )
        check(
            MergeFuzz.run(
                MergeFuzz.Config("group dates tail", group = true, dates = true, tailSize = 12),
                3101L..3150L,
            ),
        )
    }

    @Test
    fun `midnight rollover with separators scrolling in and out keeps the merge invariants`() {
        check(
            MergeFuzz.run(MergeFuzz.Config("1:1 midnight", group = false, dates = true, midnight = true), 8001L..8100L),
        )
        check(
            MergeFuzz.run(
                MergeFuzz.Config("group midnight", group = true, dates = true, midnight = true),
                9001L..9060L,
            ),
        )
    }

    @Test
    fun `edits and reads on later days keep the merge invariants`() {
        edits.forEach { (config, seeds) -> check(MergeFuzz.run(config, seeds)) }
    }

    /**
     * Seeds outside the ranges above that the PR #27 review found overwriting a saved row. A date
     * stamped onto another day's twin: 104437 (1:1 dates), 118281 (group dates), 115704 (1:1
     * midnight), 113270 (group midnight). A daily "gm" at 9:00 am taken for an edit of another
     * day's message at 9:00 am (104828), and a sender filled into a saved run of "ok"s (103811),
     * both group midnight. A short edit ("ok" to "ok!") taken for an edit of the run's other
     * "ok", lined up one row off (500331 and 516434, 1:1 edits; 617554, group edits). Each may stop
     * at a documented limit, never at a broken invariant.
     */
    @Test
    fun `seeds that once overwrote a saved row stay fixed`() {
        val midnight = MergeFuzz.Config("group midnight", group = true, dates = true, midnight = true)
        listOf(
            MergeFuzz.Config("1:1 dates", group = false, dates = true) to 104437L,
            MergeFuzz.Config("group dates", group = true, dates = true) to 118281L,
            MergeFuzz.Config("1:1 midnight", group = false, dates = true, midnight = true) to 115704L,
            midnight to 113270L,
            midnight to 104828L,
            midnight to 103811L,
            edits[0].first to 500331L,
            edits[0].first to 516434L,
            edits[1].first to 617554L,
        ).forEach { (config, seed) ->
            val result = MergeFuzz.run(config, seed..seed)
            println(result.summary())
            assertWithMessage("${config.name} seed $seed:\n" + result.failures.joinToString("\n"))
                .that(result.failures)
                .isEmpty()
        }
    }

    @Test
    fun `long screens and few unique texts keep the merge invariants`() {
        check(MergeFuzz.run(MergeFuzz.Config("1:1 14 bubbles", group = false, maxHeight = 14), 4001L..4040L))
        check(MergeFuzz.run(MergeFuzz.Config("group 20% unique", group = true, uniqueShare = 0.2), 5001L..5040L))
    }

    private val edits =
        listOf(
            MergeFuzz.Config("1:1 edits", group = false, dates = true, editRate = 0.15, lateReadRate = 0.15) to
                6001L..6100L,
            MergeFuzz.Config("group edits", group = true, dates = true, editRate = 0.15, lateReadRate = 0.15) to
                7001L..7100L,
            MergeFuzz.Config(
                "group edits 48h",
                group = true,
                dates = true,
                editRate = 0.15,
                editWindow = 48 * 60,
                lateReadRate = 0.15,
            ) to 7101L..7200L,
        )

    private fun check(result: MergeFuzz.Result) {
        println(result.summary())
        result.limits.forEach { println(it) }
        // Known limits end a seed early; they must stay rare, or the property test checks little.
        assertWithMessage("too many seeds stopped at a known limit:\n" + result.limits.joinToString("\n"))
            .that(result.limits.size)
            .isAtMost(result.seeds / 10)
        assertWithMessage("too many reads reported old messages from a block stuck at the end")
            .that(result.excusedReads)
            .isAtMost(result.reads / 20)
        assertWithMessage(
            "${result.failures.size} of ${result.seeds} seeds broke an invariant:\n" +
                result.failures.take(3).joinToString("\n\n"),
        ).that(result.failures)
            .isEmpty()
    }
}

/** The simulator behind [HistoryMergePropertyTest]. */
internal object MergeFuzz {
    data class Config(
        val name: String,
        val group: Boolean,
        val tailSize: Int? = null,
        /** The tallest screen, in bubbles. */
        val maxHeight: Int = 8,
        /** Share of messages with a text of their own; the rest are "ok", "😂", stickers... */
        val uniqueShare: Double = 0.56,
        /** Chance that a bubble between the top and bottom one is missing from a read. */
        val dropRate: Double = 0.015,
        val steps: Int = 120,
        /** Reads show date separators: messages below one carry its date. */
        val dates: Boolean = false,
        /** Days roll over often, mostly close to midnight. */
        val midnight: Boolean = false,
        /** Chance per step that a message sent within [editWindow] minutes is edited. */
        val editRate: Double = 0.0,
        /** How long after sending a message can be edited: 15 minutes (WhatsApp), 48 h (Telegram). */
        val editWindow: Int = 15,
        /** Chance that a read happens a day or two after the last message (no message in between). */
        val lateReadRate: Double = 0.0,
    )

    class Result(
        val config: Config,
        val seeds: Int,
        val reads: Int,
        val millis: Long,
        /** "seed N: invariant K ..." reports. */
        val failures: List<String>,
        val limits: List<String>,
        /** Known-limit class for each seed that stopped at one. */
        val limitClasses: List<String>,
        /** Invariant number for each failed seed. */
        val failedInvariants: List<Int>,
        val excusedReads: Int,
        /** See [Simulation.dateChances]. */
        val dateChances: Int = 0,
        val dateUpgrades: Int = 0,
        /** Edits made in the true chats. */
        val edits: Int = 0,
        /**
         * Saved rows holding an edited message's current text when the seed ended, copies in a
         * read stored again after a gap included (so a higher count is not by itself better).
         */
        val editsApplied: Int = 0,
        /** Rows that stand for a true message another row also stands for, when the seed ended. */
        val duplicateRows: Int = 0,
    ) {
        fun summary() =
            "HistoryMergePropertyTest ${config.name}: $seeds seeds, $reads reads, $millis ms, " +
                "${limits.size} stopped at a known limit ${limitClasses.groupingBy { it }.eachCount()}, " +
                "$excusedReads reads excused (old block), ${failures.size} failed " +
                "${failedInvariants.groupingBy { it }.eachCount()}" +
                (if (dateChances > 0) ", $dateUpgrades of $dateChances lined-up dated messages dated a row" else "") +
                (if (edits > 0) ", $edits edits, $editsApplied applied" else "") +
                ", $duplicateRows rows stored twice"
    }

    fun run(
        config: Config,
        seeds: LongRange,
    ): Result {
        val started = System.nanoTime()
        var reads = 0
        var excused = 0
        var dateChances = 0
        var dateUpgrades = 0
        var edits = 0
        var editsApplied = 0
        var duplicateRows = 0
        val failures = mutableListOf<String>()
        val invariants = mutableListOf<Int>()
        val limits = mutableListOf<String>()
        val classes = mutableListOf<String>()
        for (seed in seeds) {
            val simulation = Simulation(seed, config)
            simulation.run(config.steps)
            reads += simulation.reads
            excused += simulation.excusedReads
            dateChances += simulation.dateChances
            dateUpgrades += simulation.dateUpgrades
            edits += simulation.edits
            editsApplied += simulation.editsApplied()
            duplicateRows += simulation.duplicateRows()
            simulation.failure?.let {
                failures += "seed $seed: $it"
                invariants += simulation.failedInvariant
            }
            simulation.knownLimit?.let {
                limits += "seed $seed: $it"
                classes += simulation.limitClass!!
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        return Result(
            config,
            seeds.count(),
            reads,
            millis,
            failures,
            limits,
            classes,
            invariants,
            excused,
            dateChances,
            dateUpgrades,
            edits,
            editsApplied,
            duplicateRows,
        )
    }

    private class TrueMessage(
        val id: Int,
        val isMe: Boolean,
        val sender: String?,
        text: String,
        val time: String,
        /** Minutes since the chat started. */
        val minute: Int,
    ) {
        /** Every text it had, oldest first: an edit adds one. A row may hold any of them. */
        val versions = mutableListOf(text)
        val text: String get() = versions.last()

        val day: Int get() = minute / MINUTES_PER_DAY
        val date: LocalDate get() = FIRST_DAY.plusDays(day.toLong())

        /**
         * What a bubble shows of it: identical twins share it. Not the date: a row without an exact
         * date cannot tell twins of two days apart (invariant 1 checks the exact dates).
         */
        val key: List<Any?> get() = listOf(isMe, sender, text, time)

        override fun toString() = "#$id ${if (isMe) "me" else sender} \"$text\" $time $date"
    }

    private class Simulation(
        seed: Long,
        private val config: Config,
    ) {
        private val random = Random(seed)
        private val group = config.group
        private val truth = mutableListOf<TrueMessage>()

        /** Minutes since the chat started; bubbles show only the time of day. */
        private var clock =
            if (config.midnight) {
                MINUTES_PER_DAY -
                    random.nextInt(
                        1,
                        40,
                    )
            } else {
                random.nextInt(MINUTES_PER_DAY)
            }
        private val history = FakeHistory(config.tailSize)

        /** Row id to the true message it stands for. */
        private val rowTruth = HashMap<Long, Int>()
        private var top = 0
        private var atBottom = true
        var reads = 0
            private set
        var failure: String? = null
            private set
        var failedInvariant = 0
            private set

        /** Set instead of [failure] when the broken read is one of the documented known limits. */
        var knownLimit: String? = null
            private set
        var limitClass: String? = null
            private set

        /** Reads whose only fault is a documented limit that leaves the history right. */
        var excusedReads = 0
            private set

        /** Read messages showing a date lined up with a row without an exact date, and how many dated it. */
        var dateChances = 0
            private set
        var dateUpgrades = 0
            private set

        var edits = 0
            private set

        /** Rows holding an edited message's current text. */
        fun editsApplied() =
            history.sortedRows().count { row ->
                truth[rowTruth.getValue(row.id)].let { it.versions.size > 1 && row.message.text == it.text }
            }

        /** Rows that stand for a true message another row also stands for. */
        fun duplicateRows() =
            history.sortedRows().let { rows -> rows.size - rows.map { rowTruth.getValue(it.id) }.toSet().size }

        fun run(steps: Int) {
            repeat(25) { addMessage() }
            for (step in 1..steps) {
                if (random.nextDouble() < 0.4) repeat(random.nextInt(1, 4)) { addMessage() }
                if (config.editRate > 0 && random.nextDouble() < config.editRate) edit()
                val height = random.nextInt(3, config.maxHeight + 1)
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
                if (config.lateReadRate > 0 && random.nextDouble() < config.lateReadRate) {
                    // The chat is opened again a day or two later, at any time of day.
                    val day = clock / MINUTES_PER_DAY + random.nextInt(1, 3)
                    clock = day * MINUTES_PER_DAY + random.nextInt(MINUTES_PER_DAY)
                }
                read(top, minOf(truth.size, top + height), step)
                if (failure != null || knownLimit != null) return
            }
        }

        private fun scrollTo(newTop: Int) {
            top = newTop
            atBottom = false
        }

        /**
         * A message sent at most [Config.editWindow] minutes ago gets a new text: a distinctive
         * one stays distinctive, a short one ("ok") gets another short one.
         */
        private fun edit() {
            // Stickers have no text to edit.
            val recent = truth.filter { clock - it.minute <= config.editWindow && it.text.isNotEmpty() }
            if (recent.isEmpty()) return
            val t = recent.random(random)
            t.versions +=
                if (t.text.length >= HistoryMerge.DISTINCTIVE_LENGTH) {
                    "message ${t.id} v${t.versions.size + 1}"
                } else {
                    t.text + "!"
                }
            edits++
        }

        private fun addMessage() {
            val step = random.nextDouble()
            val next =
                if (config.midnight && step < 0.12) {
                    // Jump to a few minutes before the next midnight.
                    (clock / MINUTES_PER_DAY + 1) * MINUTES_PER_DAY - random.nextInt(1, 6)
                } else {
                    clock +
                        when {
                            step < 0.45 -> 0
                            step < 0.75 -> random.nextInt(1, 4)
                            step < 0.95 -> random.nextInt(4, 31)
                            else -> random.nextInt(60, 601)
                        }
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
                        minute = clock,
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
            // The repeated texts keep their relative shares: ok 18, 😂 10, sticker 6, haha 6, yes 4.
            val pick = random.nextDouble() * 0.44 / (1 - config.uniqueShare)
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
            truth += TrueMessage(id, isMe, sender, text, format(clock), clock)
        }

        /**
         * What the parser reads from the screen window `truth[from until to]`, with the true index
         * of each message read (a bubble between the edges may be missing). With dates, a
         * separator sits above the first message of each day; the top message's one is on screen
         * only half the time.
         */
        private fun render(
            from: Int,
            to: Int,
        ): List<Pair<Int, ChatMessage>> {
            var label: String? = null
            var separatorDate: LocalDate? = null
            return (from until to).mapNotNull { k ->
                if (config.dates) {
                    val firstOfDay = k == 0 || truth[k - 1].day != truth[k].day
                    if (firstOfDay && (k > from || random.nextBoolean())) separatorDate = truth[k].date
                }
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
                val dropped = k > from && k < to - 1 && random.nextDouble() < config.dropRate
                if (dropped) {
                    null
                } else {
                    k to
                        ChatMessage(sender, t.text, if (clipped) null else t.time, t.isMe, separatorDate)
                }
            }
        }

        private fun read(
            from: Int,
            to: Int,
            step: Int,
        ) {
            val rendered = render(from, to)
            val read = rendered.map { it.second }
            val readIds = IdentityHashMap<ChatMessage, Int>()
            rendered.forEach { (k, message) -> readIds[message] = k }
            val dropped = (from until to).toSet() - rendered.map { it.first }.toSet()
            val before = history.sortedRows()
            val segmentsBefore = segmentsOf(before)
            val maxBefore = before.maxOfOrNull { rowTruth.getValue(it.id) } ?: -1
            val currentBefore = history.currentRowId

            // Known limit: a block stored after a gap that is older than rows above it sits after
            // them; a read that continues it anchors there and may report its old messages as new.
            // Only reads whose true range touches such a block are excused.
            val touchesOldBlock =
                segmentsBefore.any { segment ->
                    segment.maxOf { rowTruth.getValue(it.id) } < maxBefore &&
                        segment.any { rowTruth.getValue(it.id) in from until to }
                }
            // Known limit: the one-pair rules take a read whose top bubble shows the same as the
            // last row of the current segment (equal, or equal with its time cut off) for new
            // messages below it.
            val endBefore =
                segmentsBefore.firstOrNull { segment -> segment.any { it.id == currentBefore } }?.last()
                    ?: before.lastOrNull()
            val oneAtEndTwin =
                endBefore != null &&
                    rowTruth.getValue(endBefore.id) != from &&
                    endBefore.message.let {
                        it.isMe == read[0].isMe &&
                            it.text == read[0].text &&
                            it.sender == read[0].sender &&
                            (read[0].timeShown == null || read[0].timeShown == it.timeShown)
                    }

            // Known limit: a read message exactly equal (every shown field, time included) to a
            // saved message from far away in the chat (another day) can be taken for it. Twins
            // close by (a burst of "ok"s in one minute) are not excused, and neither is a failure
            // in a read that did not line up with such a far twin.
            val farTwins =
                before
                    .filter { row ->
                        read.any { message ->
                            message.timeShown != null &&
                                abs(rowTruth.getValue(row.id) - readIds.getValue(message)) > FAR &&
                                sameShown(message, row.message)
                        }
                    }.map { it.id }
                    .toSet()

            // Known limit: the top bubble, as shown, could also be the message right above it (a
            // run of identical "ok"s that goes on above the screen): nothing tells how many are new.
            val topAmbiguous =
                (from > 0 && compatible(read[0], truth[from - 1])) ||
                    (to < truth.size && compatible(read.last(), truth[to]))

            // Rows without a time before the merge, by the true message they stand for.
            val timelessBefore = before.filter { it.message.timeShown == null }.map { rowTruth.getValue(it.id) }.toSet()
            val storedBefore = before.map { rowTruth.getValue(it.id) }.toSet()
            val plan = history.merge(read, today = FIRST_DAY.plusDays((clock / MINUTES_PER_DAY).toLong()))
            reads++
            plan.inserts.zip(history.lastInsertedIds).forEach { (insert, rowId) ->
                rowTruth[rowId] = readIds[insert.message] ?: error("an insert that is not a read message")
            }
            val labelsBefore = HashMap(rowTruth)
            relabelTwins(read, readIds, plan, before.associate { it.id to it.message })
            val onFarTwin = plan.matchedRowIds.any { it in farTwins }
            // Known limit: the read misses a message (dropped) in the slot where the history missed
            // another one, which this read stores. Nobody can tell the order of the two.
            val insertedNow = plan.inserts.map { readIds.getValue(it.message) }.toSet()

            /** [invariant] is the number of the broken invariant in the class KDoc. */
            fun fail(
                invariant: Int,
                what: String,
                limit: String? = null,
            ) {
                val report =
                    buildString {
                        appendLine("invariant $invariant, step $step: $what")
                        appendLine(
                            "  read ${from until to} (dropped $dropped): " + read.joinToString(" | ") { describe(it) },
                        )
                        appendLine("  anchored=${plan.anchored} newAtEnd=${plan.newAtEnd.map { readIds[it] }}")
                        appendLine(
                            "  matched (read -> row labelled before): " +
                                rendered.zip(plan.matchedRowIds).joinToString(" ") { (r, id) ->
                                    "#${r.first}->${id?.let { "#${labelsBefore[it]}" } ?: "new"}"
                                },
                        )
                        appendLine("  before, segments near the read and the current one:")
                        val near =
                            segmentsBefore.filter {
                                it.any { row -> rowTruth.getValue(row.id) in from - 3 until to + 3 } ||
                                    it.any { row -> row.id == currentBefore }
                            }
                        (near + listOfNotNull(segmentsBefore.lastOrNull())).distinct().forEach { segment ->
                            appendLine(
                                "    " + segment.joinToString(" | ") { "#${rowTruth[it.id]} ${describe(it.message)}" },
                            )
                        }
                        appendLine("  after:")
                        segmentsOf(history.sortedRows()).forEach { segment ->
                            appendLine("  segment: " + segment.joinToString(" ") { "#${rowTruth[it.id]}" })
                        }
                    }
                if (limit != null) {
                    knownLimit = "$limit, $report"
                    limitClass = limit
                } else {
                    failure = report
                    failedInvariant = invariant
                }
            }

            val rows = history.sortedRows()
            // Invariant 1.
            rows.firstOrNull { !compatible(it.message, truth[rowTruth.getValue(it.id)]) }?.let { row ->
                val t = truth[rowTruth.getValue(row.id)]
                // Known limit: a row saved without a time takes the time of the identical message
                // next to it (same author, sender and text, sent a minute later or earlier) when
                // the read is shifted inside such a run.
                val savedWithoutTime = before.single { it.id == row.id }.message.timeShown == null
                val identicalNeighbour =
                    listOf(t.id - 1, t.id + 1).mapNotNull { truth.getOrNull(it) }.any { u ->
                        u.isMe == t.isMe && u.sender == t.sender && u.text == t.text &&
                            abs(u.minute - t.minute) <= 1 && compatible(row.message, u)
                    }
                // Known limit: the read misses this message and the saved side missed the read
                // message next to it, in the same slot: it looks like an edit, and nobody can tell.
                val rowSegment = segmentsBefore.single { segment -> segment.any { it.id == row.id } }
                val doubleMiss =
                    t.id in dropped &&
                        rendered.any { (u, m) ->
                            abs(u - t.id) <= 2 &&
                                rowSegment.none { rowTruth.getValue(it.id) == u } &&
                                row.message.copy(date = null) == m.copy(sender = row.message.sender, date = null)
                        }
                val limit =
                    when {
                        row.id in farTwins -> "far twin"
                        topAmbiguous && savedWithoutTime && identicalNeighbour -> "wildcard row shifted"
                        doubleMiss -> "drops in one slot"
                        else -> null
                    }
                return fail(1, "row of $t now holds ${describe(row.message)}", limit)
            }
            // Invariant 2. Twins relabelled by this read count as the twin the merge took them for.
            val maxBeforeNow = before.maxOfOrNull { rowTruth.getValue(it.id) } ?: -1
            plan.newAtEnd.firstOrNull { readIds.getValue(it) <= maxBeforeNow }?.let {
                when {
                    touchesOldBlock -> {
                        excusedReads++
                    }

                    else -> {
                        val limit =
                            when {
                                onFarTwin -> "far twin"
                                oneAtEndTwin -> "one-pair twin"
                                else -> null
                            }
                        return fail(2, "newAtEnd holds #${readIds[it]}, not newer than saved #$maxBeforeNow", limit)
                    }
                }
            }
            // Invariant 3. A row with an edited message's old text, next to a row the read stored
            // with the new one, is the documented "edit stored as a new message": not checked.
            val segments = segmentsOf(rows).map { canonicalIds(withoutStaleCopies(it)) }
            segments.firstOrNull { ids -> ids.zipWithNext().any { (a, b) -> a >= b } }?.let { ids ->
                val inverted = ids.zipWithNext().filter { (a, b) -> a >= b }
                val undecidable =
                    inverted.all { (a, b) ->
                        abs(a - b) <= 2 && ((a in dropped && b in insertedNow) || (b in dropped && a in insertedNow))
                    }
                val limit =
                    when {
                        onFarTwin -> "far twin"
                        undecidable -> "drops in one slot"
                        inverted.all { (a, b) -> inRun(a) || inRun(b) } -> "identical run"
                        else -> null
                    }
                return fail(3, "a segment is out of true order or holds a message twice: $ids", limit)
            }
            // Invariant 4.
            if (plan.anchored) {
                val visible = ((if (oneAtEndTwin) from + 1 else from) until to).filter { it !in dropped }
                if (segments.none { ids -> ids.filter { it in from until to }.containsAll(visible) }) {
                    // Known limit: a bubble next to a dropped one shows the same as the dropped
                    // one (a clipped "😂" below a missing "😂"): the read may show either.
                    val shownAsDropped =
                        rendered.any { (v, m) -> dropped.any { abs(it - v) == 1 && compatible(m, truth[it]) } } ||
                            plan.matchedRowIds.any { it != null && labelsBefore[it] in dropped }
                    // Known limit: a row saved without a time next to a message the history missed
                    // (same author and text) is taken for that message when the read shows it.
                    val takenByTimeless =
                        rendered.any { (v, m) ->
                            v !in storedBefore &&
                                listOf(v - 1, v + 1).any {
                                    it in timelessBefore &&
                                        compatible(m.copy(timeShown = null), truth[it])
                                }
                        }
                    val limit =
                        when {
                            onFarTwin -> "far twin"
                            topAmbiguous -> "ambiguous edge"
                            shownAsDropped -> "drops in one slot"
                            takenByTimeless -> "row without a time"
                            missingFromBest(visible, segments).all { inRun(it) } -> "identical run"
                            else -> null
                        }
                    return fail(4, "an anchored read's messages are not all in one segment", limit)
                }
            }
            // Invariant 5.
            val lastRow = history.lastMessages(read.size + 2).last()
            val lastRead = truth[readIds.getValue(read.last())]
            val lastTruth = truth[rowTruth.getValue(lastRow.id)]
            if (lastTruth.key != lastRead.key) {
                // Known limits: the read's last bubble, as shown (clipped, or against a row saved
                // without a time or sender), is also the dropped message above it, or the message
                // right below the screen that the history has and this read does not show.
                val couldBe = compatible(read.last(), lastTruth) || sameShownOrMissing(read.last(), lastRow.message)
                val limit =
                    when {
                        couldBe && lastTruth.id in dropped -> "drops in one slot"
                        couldBe && lastTruth.id == to -> "ambiguous edge"
                        else -> null
                    }
                return fail(5, "lastMessages ends with #${lastTruth.id}, not $lastRead", limit)
            }
            // Invariant 6.
            val rowOfRead = plan.matchedRowIds.toMutableList()
            val stored = BooleanArray(read.size)
            plan.inserts.zip(history.lastInsertedIds).forEach { (insert, rowId) ->
                val k = read.indexOfFirst { it === insert.message }
                rowOfRead[k] = rowId
                stored[k] = true
            }
            read.indices
                .firstOrNull { k ->
                    val date = read[k].date ?: return@firstOrNull false
                    val held = history.row(rowOfRead[k]!!).message.date
                    held != date && (stored[k] || held != null)
                }?.let { k ->
                    return fail(6, "row of ${describe(read[k])} holds date ${history.row(rowOfRead[k]!!).message.date}")
                }
            // How often a dated read message dates the row it lined up with (step 8 of the merge).
            read.indices.forEach { k ->
                val rowId = plan.matchedRowIds[k] ?: return@forEach
                if (read[k].date == null || before.single { it.id == rowId }.message.date != null) return@forEach
                dateChances++
                if (history.row(rowId).message.date != null) dateUpgrades++
            }
        }

        /**
         * A read message lined up with a row that stands for another message close by, whose
         * saved fields do not contradict the read one (a twin identical in every field, or a row
         * saved without a time or sender): nobody can tell which of the two the row is, so the
         * row now stands for the read's one, and a row of the same segment that stood for that one
         * takes the other.
         */
        private fun relabelTwins(
            read: List<ChatMessage>,
            readIds: IdentityHashMap<ChatMessage, Int>,
            plan: MergePlan,
            savedBefore: Map<Long, ChatMessage>,
        ) {
            val segmentOf = HashMap<Long, Int>()
            segmentsOf(history.sortedRows()).forEachIndexed { k, segment -> segment.forEach { segmentOf[it.id] = k } }
            plan.matchedRowIds.forEachIndexed { k, rowId ->
                if (rowId == null) return@forEachIndexed
                val shown = readIds.getValue(read[k])
                val stood = rowTruth.getValue(rowId)
                val saved = savedBefore.getValue(rowId)
                if (shown == stood || !compatible(saved, truth[shown]) ||
                    abs(shown - stood) > FAR
                ) {
                    return@forEachIndexed
                }
                val others = rowTruth.entries.filter { it.value == shown && segmentOf[it.key] == segmentOf[rowId] }
                if (others.any { !compatible(history.row(it.key).message, truth[stood]) }) return@forEachIndexed
                others.forEach { it.setValue(stood) }
                rowTruth[rowId] = shown
            }
        }

        /** The visible messages missing from the segment that holds most of them (never empty here). */
        private fun missingFromBest(
            visible: List<Int>,
            segments: List<List<Int>>,
        ): List<Int> {
            val best = segments.maxBy { ids -> visible.count { it in ids } }
            return visible.filter { it !in best }
        }

        /**
         * A short text ("ok", "😂", a sticker) with another message of the same author and text at
         * most [RUN] messages away: the merge may line a read up one message off inside the run.
         */
        private fun inRun(id: Int): Boolean {
            val t = truth[id]
            return t.text.length < HistoryMerge.DISTINCTIVE_LENGTH &&
                (id - RUN..id + RUN).any { k ->
                    k != id &&
                        truth.getOrNull(k)?.let { it.isMe == t.isMe && it.sender == t.sender && it.text == t.text } ==
                        true
                }
        }

        /** [segment] without rows holding an old text of a message another row of it stands for. */
        private fun withoutStaleCopies(segment: List<StoredRow>): List<StoredRow> =
            segment.filterNot { row ->
                val t = truth[rowTruth.getValue(row.id)]
                row.message.text != t.text && segment.any { it !== row && rowTruth.getValue(it.id) == t.id }
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
            // With the text the row holds: an edited message's old "ok" is a twin of another "ok".
            val key = { k: Int -> truth[ids[k]].let { t -> listOf(t.isMe, t.sender, segment[k].message.text, t.time) } }
            ids.indices.groupBy(key).values.forEach { slots ->
                val sorted = slots.map { ids[it] }.sorted()
                slots.forEachIndexed { k, slot -> ids[slot] = sorted[k] }
            }
            return ids
        }

        private fun compatible(
            saved: ChatMessage,
            t: TrueMessage,
        ) = saved.isMe == t.isMe &&
            saved.text in t.versions &&
            (saved.sender == null || saved.sender == t.sender) &&
            (saved.timeShown == null || saved.timeShown == t.time) &&
            (saved.date == null || saved.date == t.date)

        /** Equal in every field both show, as the merge's exact match: only a sender may be missing. */
        private fun sameShown(
            a: ChatMessage,
            b: ChatMessage,
        ) = !datesDiffer(a, b) &&
            a.isMe == b.isMe &&
            a.text == b.text &&
            a.timeShown == b.timeShown &&
            (a.sender == b.sender || (!a.isMe && (a.sender == null || b.sender == null)))

        private fun datesDiffer(
            a: ChatMessage,
            b: ChatMessage,
        ) = a.date != null && b.date != null && a.date != b.date

        /** As the merge's loose match: a field missing on either side matches anything. */
        private fun sameShownOrMissing(
            a: ChatMessage,
            b: ChatMessage,
        ) = !datesDiffer(a, b) &&
            a.isMe == b.isMe &&
            a.text == b.text &&
            (a.isMe || a.sender == null || b.sender == null || a.sender == b.sender) &&
            (a.timeShown == null || b.timeShown == null || a.timeShown == b.timeShown)

        private fun describe(m: ChatMessage) =
            "${if (m.isMe) "me" else m.sender} \"${m.text}\" ${m.timeShown} ${m.date ?: ""}"
    }

    /** How far apart two equal short messages still make a run. */
    private const val RUN = 3

    /** Twins further apart than two screens count as "far" (another day). */
    private const val FAR = 16
    private const val MINUTES_PER_DAY = 24 * 60

    /** The date of the chat's first day (day 0 of [TrueMessage.minute]). */
    private val FIRST_DAY: LocalDate = LocalDate.of(2026, 9, 1)
    private const val TITLE = "Asha"
    private val MEMBERS = listOf("Asha", "Ravi", "Meera", "Bob")

    /** 9:00 am, in minutes. */
    private const val GM = 9 * 60

    private fun format(clock: Int): String {
        val minute = clock % MINUTES_PER_DAY
        val hour = minute / 60
        val shown = if (hour % 12 == 0) 12 else hour % 12
        return "%d:%02d %s".format(shown, minute % 60, if (hour < 12) "am" else "pm")
    }
}
