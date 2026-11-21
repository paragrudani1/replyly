package com.paragrudani.replyly.history

import com.paragrudani.replyly.parser.ChatMessage
import java.security.MessageDigest
import kotlin.math.abs

/** A saved message as the merge sees it: its row id, its ordering key, and its content. */
data class StoredRow(
    val id: Long,
    /** Orders the rows of one chat. Only the order matters; values have gaps between them. */
    val position: Long,
    val message: ChatMessage,
    /**
     * True on the first row of a block that was stored without overlapping the rows above it: the
     * history may miss messages right above this row, and the rows above may even be newer.
     */
    val gapBefore: Boolean = false,
)

/** A new row to store. */
data class PlannedInsert(
    val position: Long,
    val message: ChatMessage,
    /** See [StoredRow.gapBefore]. */
    val gapBefore: Boolean = false,
)

/**
 * A saved row whose content changed on screen (an edited or deleted-for-everyone message), or that
 * gets a sender or time it was stored without.
 */
data class PlannedEdit(
    val id: Long,
    val message: ChatMessage,
)

/** A saved row that needs a new [position] because there was no room left for an insert. */
data class PlannedMove(
    val id: Long,
    val position: Long,
)

/** What one screen read changes in the history of one chat. Apply it in a single transaction. */
data class MergePlan(
    val inserts: List<PlannedInsert>,
    val edits: List<PlannedEdit>,
    val moves: List<PlannedMove>,
    /** True when the read overlapped the saved history, so its place in the history is known. */
    val anchored: Boolean,
    /**
     * Messages stored after the last saved message, in an anchored read whose overlap reaches the
     * last saved message: these arrived or were sent since the last read. Empty otherwise, because
     * then it is not known whether they are new (the history may have a gap).
     */
    val newAtEnd: List<ChatMessage>,
    /** Saved rows whose [StoredRow.gapBefore] is cleared: the gap above them is closed or moved up. */
    val gapsCleared: List<Long> = emptyList(),
) {
    val isNoOp: Boolean get() = inserts.isEmpty() && edits.isEmpty() && moves.isEmpty() && gapsCleared.isEmpty()
}

/** The identity hash of a message: (isMe, sender, text, time shown), spec 5. */
object MessageHash {
    fun of(message: ChatMessage): String {
        val fields =
            listOf(
                if (message.isMe) "me" else "them",
                message.sender.orEmpty(),
                message.text,
                message.timeShown.orEmpty(),
            )
        val digest = MessageDigest.getInstance("SHA-256").digest(fields.joinToString(SEPARATOR).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private const val SEPARATOR = "\u001F"
}

/**
 * Merges one screen read of a chat into its saved history (spec 5). Pure logic: the caller loads
 * the chat's saved rows, asks for a [MergePlan] and applies it.
 *
 * A screen read shows a contiguous window of the chat, oldest first. Message identity is the
 * [MessageHash] of (isMe, sender, text, time shown). The same message can legitimately appear
 * twice ("ok", "ok" at 6:41), and a bubble shows the time but not the date, so a set of hashes
 * cannot tell old from new. The merge instead lines the read up against the saved sequence, like
 * a diff.
 *
 * **Segments.** A read that does not overlap the saved rows is appended at the end, and its first
 * row is marked [StoredRow.gapBefore]: messages may be missing above it, and it may even be older
 * than the rows above (a jump far up). The markers cut the history into gap-free segments. One
 * alignment lives inside one segment: pairs, edits and sender or time fills never cross a marker,
 * and new messages are stored inside the segment of the rows they line up with.
 *
 * 1. **Find the overlap.** Every pair (read index `i`, saved index `j`) of *exactly* equal
 *    messages votes for its segment and the offset `d = j - i`. Exactly equal means both show the
 *    same time, and either the same hash, or someone else's message with the same text where one
 *    side has no sender (a group bubble whose name label is above the screen). The (segment,
 *    offset) with the most votes wins; ties go to the one whose overlap has the fewest messages
 *    that cannot match, then to the newest.
 * 2. **Align.** A longest common subsequence between the read and that segment pairs up equal
 *    messages, only within [BAND] rows of the voted offset's diagonal. Here two messages also count
 *    as equal when they differ only by a field the screen did not show: a `null` time (the
 *    bubble's top or bottom is cut off) or a `null` sender of someone else's message. Among equally
 *    long alignments it keeps the pairs closest to the diagonal, so when "ok", "ok" is saved and
 *    the screen shows "ok", "b", the "ok" pairs with the second one (the screen's top). A pair
 *    that is not exact is kept only when an exact pair sits right next to it, with no read or
 *    saved message skipped between them.
 * 3. **Accept the overlap** only if the alignment
 *    - has at least two pairs, one of them exact;
 *    - covers at least half of the read messages that fall inside the segment at the offset;
 *    - leaves at most one read message unpaired between its first and last pair;
 *    - is not contradicted at its edges: a segment has no holes, so the read message right above
 *      the first pair must be the saved row right above it when both exist (likewise below the
 *      last pair), unless that slot looks like an edit.
 *
 *    One pair is enough only for new messages below the last saved row, with a text of at least
 *    [DISTINCTIVE_LENGTH] characters: the read's first message equals the saved last one in every
 *    field, or it is the saved last one with its time cut off (same author, sender and text; the
 *    saved one shows a time); and no read message equals another saved row at an offset where the
 *    read would fit just as well. This rejects a lone coincidence (yesterday's "gm" at 9:00 am, or
 *    a clipped "ok" that is the oldest saved row).
 * 4. **Close gaps.** If the read goes on past the end of its segment, and that remainder lines up
 *    on its own (step 1 to 3, two pairs or more) with the start of the next segment, the read
 *    shows that nothing is missing there: the two segments join and the marker is cleared. The
 *    same holds upwards for the previous segment.
 * 5. **Edits.** Only strictly between two aligned pairs of one segment, never before the first or
 *    after the last one (there the read may show a new message): a read message and the saved message in the same
 *    slot are taken for an edit when both show a time and it is the same, they have the same
 *    author, and for someone else's message both show the same sender. The saved row keeps its
 *    place and gets the new content. Pairing stops at the first slot that does not qualify.
 * 6. **Fill in.** An aligned saved message stored without a time gets the time the read shows,
 *    and one from someone else stored without a sender gets the sender the read shows, so a row
 *    does not keep matching every message with its text.
 * 7. **Place new messages.** Each remaining read message is stored right before the saved row of
 *    the next read message that has one; messages after the last pair go right after that pair's
 *    row, inside its segment, even when later segments follow. New messages above the first row
 *    of a segment take over its gap marker. Saved rows that the read skips (deleted for me, or a
 *    parser miss) are kept.
 *
 * Results by case: an identical re-read or a read inside the saved range is a no-op; new messages
 * below the tail are appended and reported in [MergePlan.newAtEnd]; scrolling up stores the older
 * messages before the saved ones; a read with no accepted overlap is appended after the last
 * saved row with a gap marker (not reported in [MergePlan.newAtEnd]); reading the same screen
 * again then overlaps and adds nothing.
 *
 * Known limits:
 * - A read far above the saved range with no overlap lands at the end, after newer rows, because
 *   nothing on screen says how old it is. Reads that continue it then anchor at the end of the
 *   history, so their messages are reported in [MergePlan.newAtEnd] although they are old.
 * - A scroll-up that shows only one saved message does not overlap; it is appended after a gap.
 *   So is a read whose only overlap is a short message ("ok", "😂", a sticker): it repeats too
 *   often to anchor on its own, so its new messages are not reported in [MergePlan.newAtEnd].
 * - A message deleted for me (or missed by the parser) right next to the first or last aligned
 *   message makes the read look like a coincidence: it is appended after a gap, and the messages
 *   it shares with the history are stored twice, in two segments.
 * - An edit of the first or last visible message is stored as a new message.
 * - The one-pair rules cannot tell the saved last message from a later or older message equal to
 *   it in every shown field (a clipped "see you soon" sent again, or "gm 9:00 am" from another
 *   day). The read is then placed after the last saved row without a gap marker: the messages
 *   between are missing, and if the read was older, its messages are reported as new.
 * - A read whose top or bottom bubble, as shown, could also be the message just above or below it
 *   (inside a run of identical "ok"s in one minute) cannot tell how many of the run it shows; the
 *   merge may store one too few, and a run member saved without a time (a clipped group bubble
 *   with no name label) may get the sender and time of its identical neighbour.
 * - A read whose overlap is made only of messages identical in every shown field to a saved run
 *   (same authors, texts and times, on another day) is taken for that run.
 * - A read that spans a gap but lines up with only one side stores the messages of the other side
 *   again, inside this side's segment (a duplicate across the gap).
 *
 * Positions are spaced by [POSITION_STEP] so most inserts fit between neighbours. When a gap is
 * full, every row of the chat is renumbered (a [PlannedMove] per changed row).
 */
object HistoryMerge {
    const val POSITION_STEP: Long = 1L shl 16

    /** How many rows a pair may sit off the voted offset's diagonal (skipped or deleted messages). */
    const val BAND: Int = 3

    /**
     * At most this many read messages between the first and last pair may be unpaired (an edit,
     * or a message an earlier read missed). More means the pairs are a coincidence.
     */
    private const val MAX_UNPAIRED_INSIDE = 1

    /** One pair anchors a read only with a text this long: not "ok", "😂" or a sticker. */
    const val DISTINCTIVE_LENGTH: Int = 8

    /** Larger than any total diagonal distance, so match count always wins over closeness. */
    private const val MATCH_WEIGHT: Long = 1L shl 32

    /** Plans a read against every saved row of a chat ([stored] in position order). */
    fun plan(
        stored: List<StoredRow>,
        read: List<ChatMessage>,
    ): MergePlan = planOrNull(stored, read, tailOnly = false)!!

    /**
     * Plans a read against only the newest saved rows of a chat ([tail], in position order), so a
     * capture in a long chat does not load all of it. Returns `null` when the read may concern
     * older rows: no overlap with the tail, the read reaches above the tail's first row, the
     * overlap is weak (a single pair, or a new or edited message between the pairs), or an insert
     * needs every row renumbered. Then call [plan] with the whole chat.
     */
    fun planAgainstTail(
        tail: List<StoredRow>,
        read: List<ChatMessage>,
    ): MergePlan? = planOrNull(tail, read, tailOnly = true)

    private fun planOrNull(
        stored: List<StoredRow>,
        read: List<ChatMessage>,
        tailOnly: Boolean,
    ): MergePlan? {
        if (read.isEmpty()) return MergePlan(emptyList(), emptyList(), emptyList(), anchored = false, emptyList())
        val alignment = Aligner(stored, read).align()
        val firstPair = alignment.pairs.firstOrNull()
        val lastPair = alignment.pairs.lastOrNull()
        if (tailOnly) {
            // In the tail, a weak alignment may be a coincidence that the whole chat would outvote:
            // one pair, or a read message between the pairs that is new or edited.
            if (firstPair == null || lastPair == null || firstPair.second < firstPair.first) return null
            if (alignment.pairs.size == 1 || alignment.pairs.size != lastPair.first - firstPair.first + 1) return null
        }

        val edits =
            read.indices
                .filter { alignment.edited[it] }
                .map { PlannedEdit(stored[alignment.counterpart[it]].id, read[it]) } +
                fills(stored, read, alignment.pairs)

        // Read messages without a saved counterpart, grouped by the saved index they go before.
        val groups = sortedMapOf<Int, MutableList<ChatMessage>>()
        var pending = mutableListOf<ChatMessage>()
        for (i in read.indices) {
            val j = alignment.counterpart[i]
            if (j < 0) {
                pending += read[i]
            } else if (pending.isNotEmpty()) {
                groups[j] = pending
                pending = mutableListOf()
            }
        }
        // After the last pair: right after its row, inside its segment.
        if (pending.isNotEmpty()) groups[lastPair?.let { it.second + 1 } ?: stored.size] = pending
        if (tailOnly && groups.containsKey(0)) return null

        // The group whose first message gets a gap marker: an unanchored read, or new messages
        // above the first row of a segment (that row's marker moves up to them).
        val gapGroup =
            when {
                firstPair == null -> stored.size.takeIf { stored.isNotEmpty() }
                stored[firstPair.second].gapBefore && groups.containsKey(firstPair.second) -> firstPair.second
                else -> null
            }
        val gapsCleared =
            alignment.joins.map { stored[it].id } +
                listOfNotNull(gapGroup?.takeIf { firstPair != null }?.let { stored[it].id })

        val newAtEnd =
            if (lastPair != null && lastPair.second == stored.lastIndex) groups[stored.size].orEmpty() else emptyList()
        val placed = placeWithoutMoving(stored, groups, gapGroup)
        return when {
            placed != null -> MergePlan(placed, edits, emptyList(), alignment.anchored, newAtEnd, gapsCleared)
            tailOnly -> null
            else -> renumber(stored, groups, gapGroup, edits, alignment.anchored, newAtEnd, gapsCleared)
        }
    }

    private class Alignment(
        /** For each read index, the saved index it is the same message as (or an edit of), or -1. */
        val counterpart: IntArray,
        val edited: BooleanArray,
        /** The (read index, saved index) pairs of equal messages, in order. Empty when not anchored. */
        val pairs: List<Pair<Int, Int>>,
        /** Saved indices of segment starts whose gap the read closed (step 4). */
        val joins: List<Int>,
    ) {
        val anchored: Boolean get() = pairs.isNotEmpty()
    }

    /** Steps 1 to 5 for one read. */
    private class Aligner(
        stored: List<StoredRow>,
        private val read: List<ChatMessage>,
    ) {
        private val saved = stored.map { it.message }

        /** The gap-free segments, as saved index ranges, in order. */
        private val segments: List<IntRange>

        /** For each saved index, the index of its segment in [segments]. */
        private val segmentOf = IntArray(stored.size)

        // Candidates share everything but the sender; exactMatch then checks the sender.
        private val byMessage = saved.indices.groupBy { saved[it].copy(sender = null) }

        init {
            val starts = stored.indices.filter { it == 0 || stored[it].gapBefore }
            segments = starts.mapIndexed { k, start -> start until (starts.getOrNull(k + 1) ?: stored.size) }
            segments.forEachIndexed { k, range -> range.forEach { segmentOf[it] = k } }
        }

        fun align(): Alignment {
            val counterpart = IntArray(read.size) { -1 }
            val edited = BooleanArray(read.size)
            var pairs =
                pairsIn(read.indices, segments.indices, oneAtEndAllowed = true)
                    ?: clippedTopOnly()
                    ?: return Alignment(counterpart, edited, emptyList(), emptyList())

            // Step 4: close gaps the read spans, downwards then upwards.
            val joins = mutableListOf<Int>()
            while (true) {
                val (i, j) = pairs.last()
                val segment = segmentOf[j]
                if (i == read.lastIndex || j != segments[segment].last || segment == segments.lastIndex) break
                val next = segments[segment + 1]
                val more = pairsIn(i + 1 until read.size, segment + 1..segment + 1, oneAtEndAllowed = false)
                if (more == null || more.first().second != next.first) break
                pairs = pairs + more
                joins += next.first
            }
            while (true) {
                val (i, j) = pairs.first()
                val segment = segmentOf[j]
                if (i == 0 || j != segments[segment].first || segment == 0) break
                val more = pairsIn(0 until i, segment - 1..segment - 1, oneAtEndAllowed = false)
                if (more == null || more.last().second != segments[segment - 1].last) break
                pairs = more + pairs
                joins += j
            }
            pairs.forEach { (i, j) -> counterpart[i] = j }

            // Step 5: edits only strictly between two aligned pairs of one segment.
            pairs.zipWithNext { (i1, j1), (i2, j2) ->
                if (segmentOf[j1] != segmentOf[j2]) return@zipWithNext
                var i = i1 + 1
                var j = j1 + 1
                while (i < i2 && j < j2 && isEdit(read[i], saved[j])) {
                    counterpart[i] = j
                    edited[i] = true
                    i++
                    j++
                }
            }
            return Alignment(counterpart, edited, pairs, joins)
        }

        /**
         * Steps 1 to 3 for the read messages in [range] against one of the [allowed] segments: the
         * accepted pairs, or null.
         */
        private fun pairsIn(
            range: IntRange,
            allowed: IntRange,
            oneAtEndAllowed: Boolean,
        ): List<Pair<Int, Int>>? {
            val (segment, offset) = bestAnchor(range, allowed) ?: return null
            val pairs = adjacentToExact(commonSubsequence(range, segments[segment], offset))
            return pairs.takeIf { credible(it, offset, range, segments[segment], oneAtEndAllowed) }
        }

        /** Step 1: the best (segment index, offset `saved index - read index`), or null. */
        private fun bestAnchor(
            range: IntRange,
            allowed: IntRange,
        ): Pair<Int, Int>? {
            val votes = HashMap<Pair<Int, Int>, Int>()
            for (i in range) {
                byMessage[read[i].copy(sender = null)]
                    ?.filter { j -> segmentOf[j] in allowed && exactMatch(read[i], saved[j]) }
                    ?.forEach { j -> votes.merge(segmentOf[j] to j - i, 1, Int::plus) }
            }
            if (votes.isEmpty()) return null
            val most = votes.values.max()
            return votes
                .filterValues { it == most }
                .keys
                .minWithOrNull(
                    compareBy<Pair<Int, Int>>(
                        { (segment, offset) -> mismatches(offset, range, segments[segment]) },
                        { -it.first },
                        { -it.second },
                    ),
                )
        }

        /** How many read messages in [range] that land inside [segment] at [offset] cannot match there. */
        private fun mismatches(
            offset: Int,
            range: IntRange,
            segment: IntRange,
        ): Int = range.count { it + offset in segment && !sameMessage(read[it], saved[it + offset]) }

        /** How many read messages in [range] land inside [segment] at [offset]. */
        private fun overlapLength(
            offset: Int,
            range: IntRange,
            segment: IntRange,
        ): Int = range.count { it + offset in segment }

        /** Step 3. */
        private fun credible(
            pairs: List<Pair<Int, Int>>,
            offset: Int,
            range: IntRange,
            segment: IntRange,
            oneAtEndAllowed: Boolean,
        ): Boolean {
            if (pairs.isEmpty() || contradicted(pairs, range, segment)) return false
            if (pairs.size == 1) {
                val (i, j) = pairs.single()
                // Every field, sender included: a missing sender is too weak for a lone pair.
                return oneAtEndAllowed &&
                    i == 0 &&
                    j == saved.lastIndex &&
                    read[i] == saved[j] &&
                    distinctive(read[i]) &&
                    !matchesElsewhere(j)
            }
            val (first, last) = pairs.first().first to pairs.last().first
            return pairs.any { (i, j) -> exactMatch(read[i], saved[j]) } &&
                2 * pairs.size >= overlapLength(offset, range, segment) &&
                last - first + 1 - pairs.size <= MAX_UNPAIRED_INSIDE
        }

        /**
         * Step 3: a segment has no holes, so the read message right above the first pair must be
         * the saved row right above it, if both exist; the same below the last pair. A different
         * message there means the pairs are a coincidence, unless it looks like an edit.
         */
        private fun contradicted(
            pairs: List<Pair<Int, Int>>,
            range: IntRange,
            segment: IntRange,
        ): Boolean {
            val (i0, j0) = pairs.first()
            val (i1, j1) = pairs.last()
            val above = i0 > range.first && j0 > segment.first && !isEdit(read[i0 - 1], saved[j0 - 1])
            val below = i1 < range.last && j1 < segment.last && !isEdit(read[i1 + 1], saved[j1 + 1])
            return above || below
        }

        /**
         * Step 3, one pair: some read message is exactly equal to a saved row other than [anchor],
         * at an offset where the rest of the read fits that row's segment (so the read may be there).
         */
        private fun matchesElsewhere(anchor: Int): Boolean =
            read.indices.any { i ->
                byMessage[read[i].copy(sender = null)].orEmpty().any { j ->
                    j != anchor && exactMatch(read[i], saved[j]) && fitsAt(j - i, segments[segmentOf[j]])
                }
            }

        private fun fitsAt(
            offset: Int,
            segment: IntRange,
        ): Boolean = read.indices.all { k -> k + offset !in segment || sameMessage(read[k], saved[k + offset]) }

        /** Step 3, one pair: the read's top bubble is the saved last message with its time cut off. */
        private fun clippedTopOnly(): List<Pair<Int, Int>>? {
            val top = read.first()
            val last = saved.lastOrNull() ?: return null
            val sameShown =
                top.isMe == last.isMe &&
                    top.text == last.text &&
                    distinctive(top) &&
                    top.sender == last.sender &&
                    (top.isMe || top.sender != null)
            val clipped = top.timeShown == null && last.timeShown != null
            return if (sameShown && clipped &&
                !matchesElsewhere(saved.lastIndex)
            ) {
                listOf(0 to saved.lastIndex)
            } else {
                null
            }
        }

        /** Step 2: drops pairs that are not exact unless an exact pair sits right next to them. */
        private fun adjacentToExact(pairs: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
            fun exact(k: Int) = pairs.getOrNull(k)?.let { (i, j) -> exactMatch(read[i], saved[j]) } == true

            fun next(
                a: Int,
                b: Int,
            ) = pairs[b].first == pairs[a].first + 1 && pairs[b].second == pairs[a].second + 1
            return pairs.filterIndexed { k, _ ->
                exact(k) || (k > 0 && exact(k - 1) && next(k - 1, k)) || (exact(k + 1) && next(k, k + 1))
            }
        }

        /**
         * Step 2: longest common subsequence of `read[range]` and [segment], as (read index, saved
         * index) pairs within [BAND] of the [offset] diagonal. Among alignments with the most
         * matches it picks the one closest to the diagonal (smallest total `|j - i - offset|`),
         * which settles which of two equal "ok"s a read message is.
         */
        private fun commonSubsequence(
            range: IntRange,
            segment: IntRange,
            offset: Int,
        ): List<Pair<Int, Int>> {
            val from = maxOf(segment.first, range.first + offset - BAND)
            val to = minOf(segment.last + 1, range.last + 1 + offset + BAND)
            if (from >= to) return emptyList()
            val rows = range.last - range.first + 1
            val width = to - from

            fun matches(
                r: Int,
                c: Int,
            ): Boolean {
                val i = range.first + r
                val j = from + c
                return abs(j - i - offset) <= BAND && sameMessage(read[i], saved[j])
            }

            fun gain(
                r: Int,
                c: Int,
            ): Long = MATCH_WEIGHT - abs(from + c - (range.first + r) - offset)

            // score = matches * MATCH_WEIGHT - distance from the offset diagonal; higher is better.
            val score = Array(rows + 1) { LongArray(width + 1) }
            for (r in (0 until rows).reversed()) {
                for (c in (0 until width).reversed()) {
                    val skip = maxOf(score[r + 1][c], score[r][c + 1])
                    score[r][c] = if (matches(r, c)) maxOf(skip, score[r + 1][c + 1] + gain(r, c)) else skip
                }
            }
            val pairs = mutableListOf<Pair<Int, Int>>()
            var r = 0
            var c = 0
            while (r < rows && c < width) {
                when {
                    matches(r, c) && score[r][c] == score[r + 1][c + 1] + gain(r, c) -> {
                        pairs += range.first + r to from + c
                        r++
                        c++
                    }

                    score[r][c] == score[r][c + 1] -> {
                        c++
                    }

                    else -> {
                        r++
                    }
                }
            }
            return pairs
        }
    }

    /**
     * Steps 1 to 3: both show the same time, and either the same hash, or someone else's message
     * with the same text where one side did not show the sender. Two different known senders never
     * match, and neither does a missing time.
     */
    private fun exactMatch(
        a: ChatMessage,
        b: ChatMessage,
    ): Boolean {
        if (a.timeShown == null || a.timeShown != b.timeShown) return false
        if (a == b) return true
        if (a.isMe || b.isMe || a.text != b.text) return false
        return a.sender == null || b.sender == null
    }

    /** Long enough that a lone pair is unlikely to be a coincidence. */
    private fun distinctive(message: ChatMessage): Boolean = message.text.trim().length >= DISTINCTIVE_LENGTH

    /** Equal, allowing for a time or a sender of someone else's message that the screen did not show. */
    private fun sameMessage(
        a: ChatMessage,
        b: ChatMessage,
    ): Boolean {
        if (a.isMe != b.isMe || a.text != b.text) return false
        val senderOk = a.isMe || a.sender == null || b.sender == null || a.sender == b.sender
        val timeOk = a.timeShown == null || b.timeShown == null || a.timeShown == b.timeShown
        return senderOk && timeOk
    }

    /** Step 5: same slot, changed text. Every identifying field must be shown and equal. */
    private fun isEdit(
        read: ChatMessage,
        saved: ChatMessage,
    ): Boolean {
        if (read.isMe != saved.isMe || read.text == saved.text) return false
        if (read.timeShown == null || read.timeShown != saved.timeShown) return false
        return read.isMe || (read.sender != null && read.sender == saved.sender)
    }

    /** Step 6. */
    private fun fills(
        stored: List<StoredRow>,
        read: List<ChatMessage>,
        pairs: List<Pair<Int, Int>>,
    ): List<PlannedEdit> =
        pairs.mapNotNull { (i, j) ->
            val row = stored[j].message
            val filled =
                row.copy(
                    sender = if (row.isMe) row.sender else row.sender ?: read[i].sender,
                    timeShown = row.timeShown ?: read[i].timeShown,
                )
            if (filled == row) null else PlannedEdit(stored[j].id, filled)
        }

    /** Step 7 positions when every group fits into its gap; null if one gap is full. */
    private fun placeWithoutMoving(
        stored: List<StoredRow>,
        groups: Map<Int, List<ChatMessage>>,
        gapGroup: Int?,
    ): List<PlannedInsert>? {
        val inserts = mutableListOf<PlannedInsert>()
        for ((before, messages) in groups) {
            val low = stored.getOrNull(before - 1)?.position
            val high = stored.getOrNull(before)?.position
            val positions = positionsBetween(low, high, messages.size) ?: return null
            messages.zip(positions).mapIndexedTo(inserts) { k, (message, position) ->
                PlannedInsert(position, message, gapBefore = k == 0 && before == gapGroup)
            }
        }
        return inserts
    }

    private fun positionsBetween(
        low: Long?,
        high: Long?,
        count: Int,
    ): List<Long>? =
        when {
            low == null && high == null -> {
                (1..count).map { it * POSITION_STEP }
            }

            low == null -> {
                (count downTo 1).map { high!! - it * POSITION_STEP }
            }

            high == null -> {
                (1..count).map { low + it * POSITION_STEP }
            }

            else -> {
                val step = (high - low) / (count + 1)
                if (step < 1) null else (1..count).map { low + it * step }
            }
        }

    private fun renumber(
        stored: List<StoredRow>,
        groups: Map<Int, List<ChatMessage>>,
        gapGroup: Int?,
        edits: List<PlannedEdit>,
        anchored: Boolean,
        newAtEnd: List<ChatMessage>,
        gapsCleared: List<Long>,
    ): MergePlan {
        val inserts = mutableListOf<PlannedInsert>()
        val moves = mutableListOf<PlannedMove>()
        var next = POSITION_STEP
        for (index in 0..stored.size) {
            groups[index]?.forEachIndexed { k, message ->
                inserts += PlannedInsert(next, message, gapBefore = k == 0 && index == gapGroup)
                next += POSITION_STEP
            }
            val row = stored.getOrNull(index) ?: continue
            if (row.position != next) moves += PlannedMove(row.id, next)
            next += POSITION_STEP
        }
        return MergePlan(inserts, edits, moves, anchored, newAtEnd, gapsCleared)
    }
}
