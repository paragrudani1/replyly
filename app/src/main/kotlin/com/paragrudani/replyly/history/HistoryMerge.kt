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
)

/** A new row to store. */
data class PlannedInsert(
    val position: Long,
    val message: ChatMessage,
)

/** A saved row whose content changed on screen (an edited or deleted-for-everyone message). */
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
     * Messages stored after the last saved message, in an anchored read: these are messages that
     * arrived or were sent since the last read. Empty for an unanchored read, because then it is
     * not known whether they are new (the history may have a gap).
     */
    val newAtEnd: List<ChatMessage>,
) {
    val isNoOp: Boolean get() = inserts.isEmpty() && edits.isEmpty() && moves.isEmpty()
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
 * a diff:
 *
 * 1. **Find the overlap.** Every pair (read index `i`, saved index `j`) of *exactly* equal
 *    messages votes for the offset `d = j - i`. Exactly equal means the same hash, or someone
 *    else's message with the same text and the same shown time where one side has no sender (a
 *    group bubble whose name label is above the screen). A missing time never counts as exact. The offset with the most votes wins;
 *    ties go to the offset whose overlap has the fewest messages that cannot match, then to the
 *    newest offset.
 * 2. **Align.** A longest common subsequence between the read and the saved rows around that
 *    offset pairs up equal messages. Here, and only here, two messages also count as equal when
 *    they differ only by a field the screen did not show: a `null` time (the bubble's bottom is
 *    cut off) or a `null` sender of someone else's message (a group bubble whose name label is
 *    above the screen). Among equally long alignments it keeps the pairs closest to the voted
 *    offset, so when "ok", "ok" is saved and the screen shows "ok", "b", the "ok" pairs with the
 *    second one (the screen's top).
 * 3. **Accept the overlap** only if the alignment has at least two pairs, one of them exact, and
 *    covers at least half of the read messages that fall inside the saved range at the offset.
 *    One pair is enough only for new messages below: the read's first message is exactly the
 *    saved last one. This rejects a lone coincidence (yesterday's "gm" at 9:00 am, or a clipped
 *    "ok" that is the oldest saved row).
 * 4. **Edits.** Only strictly between two aligned pairs, never before the first or after the last
 *    one (that is where gaps in the history sit): a read message and the saved message in the same
 *    slot are taken for an edit when both show a time and it is the same, they have the same
 *    author, and for someone else's message both show the same sender. The saved row keeps its
 *    place and gets the new content. Pairing stops at the first slot that does not qualify.
 * 5. **Fill in senders.** An aligned saved message from someone else that was stored without a
 *    sender gets the sender the read shows.
 * 6. **Place new messages.** Each remaining read message is stored right before the saved row of
 *    the next read message that has one; if none follows, after the last saved row. Saved rows
 *    that the read skips (deleted for me, or a parser miss) are kept.
 *
 * Results by case: an identical re-read or a read inside the saved range is a no-op; new messages
 * below the tail are appended; scrolling up stores the older messages before the saved ones; a
 * read with no accepted overlap is appended after the last saved row (its true place is unknown,
 * so it is not reported in [MergePlan.newAtEnd]); reading the same screen again then overlaps and
 * adds nothing. Known limits: a read far above the saved range with no overlap lands at the end,
 * out of order, because nothing on screen says how old it is; a scroll-up that shows only one
 * saved message does not overlap; an edit of the first or last visible message is stored as a
 * new message.
 *
 * Positions are spaced by [POSITION_STEP] so most inserts fit between neighbours. When a gap is
 * full, every row of the chat is renumbered (a [PlannedMove] per changed row).
 */
object HistoryMerge {
    const val POSITION_STEP: Long = 1L shl 16

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
     * older rows: no overlap with the tail, the read reaches above the tail's first row, or an
     * insert needs every row renumbered. Then call [plan] with the whole chat.
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
        val saved = stored.map { it.message }
        val alignment = align(saved, read)
        if (tailOnly && (!alignment.anchored || alignment.offset < 0)) return null

        val edits =
            read.indices
                .filter { alignment.edited[it] }
                .map { PlannedEdit(stored[alignment.counterpart[it]].id, read[it]) } +
                senderFills(stored, read, alignment)

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
        if (pending.isNotEmpty()) groups[stored.size] = pending
        if (tailOnly && groups.containsKey(0)) return null

        val newAtEnd = if (alignment.anchored) groups[stored.size].orEmpty() else emptyList()
        val placed = placeWithoutMoving(stored, groups)
        return when {
            placed != null -> MergePlan(placed, edits, emptyList(), alignment.anchored, newAtEnd)
            tailOnly -> null
            else -> renumber(stored, groups, edits, alignment, newAtEnd)
        }
    }

    private class Alignment(
        /** For each read index, the saved index it is the same message as (or an edit of), or -1. */
        val counterpart: IntArray,
        val edited: BooleanArray,
        /** The (read index, saved index) pairs of equal messages, in order. */
        val pairs: List<Pair<Int, Int>>,
        val anchored: Boolean,
        /** The voted `saved index - read index`; meaningful only when [anchored]. */
        val offset: Int,
    )

    private fun align(
        saved: List<ChatMessage>,
        read: List<ChatMessage>,
    ): Alignment {
        val counterpart = IntArray(read.size) { -1 }
        val edited = BooleanArray(read.size)
        val none = Alignment(counterpart, edited, emptyList(), anchored = false, offset = 0)
        val offset = bestOffset(saved, read) ?: return none

        val from = maxOf(0, offset - read.size)
        val to = minOf(saved.size, offset + 2 * read.size)
        val pairs = commonSubsequence(read, saved, from, to, offset)
        if (!credible(pairs, offset, read, saved)) return none
        pairs.forEach { (i, j) -> counterpart[i] = j }

        // Step 4: edits only strictly between two aligned pairs.
        pairs.zipWithNext { (i1, j1), (i2, j2) ->
            var i = i1 + 1
            var j = j1 + 1
            while (i < i2 && j < j2 && isEdit(read[i], saved[j])) {
                counterpart[i] = j
                edited[i] = true
                i++
                j++
            }
        }
        return Alignment(counterpart, edited, pairs, anchored = true, offset = offset)
    }

    /** Step 3. */
    private fun credible(
        pairs: List<Pair<Int, Int>>,
        offset: Int,
        read: List<ChatMessage>,
        saved: List<ChatMessage>,
    ): Boolean {
        if (pairs.size == 1) {
            val (i, j) = pairs.single()
            return i == 0 && j == saved.lastIndex && exactMatch(read[i], saved[j])
        }
        return pairs.size >= 2 &&
            pairs.any { (i, j) -> exactMatch(read[i], saved[j]) } &&
            2 * pairs.size >= overlapLength(offset, read.size, saved.size)
    }

    /** Step 1: the best offset `saved index - read index` among those with exact matches, or null. */
    private fun bestOffset(
        saved: List<ChatMessage>,
        read: List<ChatMessage>,
    ): Int? {
        if (saved.isEmpty()) return null
        // Candidates share everything but the sender; exactMatch then checks the sender.
        val byMessage = saved.indices.groupBy { saved[it].copy(sender = null) }
        val votes = HashMap<Int, Int>()
        read.forEachIndexed { i, message ->
            byMessage[message.copy(sender = null)]
                ?.filter { j -> exactMatch(message, saved[j]) }
                ?.forEach { j -> votes.merge(j - i, 1, Int::plus) }
        }
        if (votes.isEmpty()) return null
        val most = votes.values.max()
        return votes
            .filterValues { it == most }
            .keys
            .minWithOrNull(compareBy<Int>({ mismatches(it, read, saved) }, { -it }))
    }

    /** How many read messages that land inside the saved range at [offset] cannot match there. */
    private fun mismatches(
        offset: Int,
        read: List<ChatMessage>,
        saved: List<ChatMessage>,
    ): Int {
        val first = maxOf(0, -offset)
        val last = minOf(read.size, saved.size - offset)
        return (first until last).count { !sameMessage(read[it], saved[it + offset]) }
    }

    /** How many read messages land inside the saved range at [offset]. */
    private fun overlapLength(
        offset: Int,
        readSize: Int,
        savedSize: Int,
    ): Int = minOf(readSize, savedSize - offset) - maxOf(0, -offset)

    /**
     * Step 2: longest common subsequence of [read] and `saved[from until to]`, as (read index,
     * saved index) pairs. Among alignments with the most matches it picks the one closest to the
     * voted [offset] (smallest total `|j - i - offset|`), which settles which of two equal "ok"s a
     * read message is.
     */
    private fun commonSubsequence(
        read: List<ChatMessage>,
        saved: List<ChatMessage>,
        from: Int,
        to: Int,
        offset: Int,
    ): List<Pair<Int, Int>> {
        val width = to - from
        // score = matches * MATCH_WEIGHT - distance from the offset diagonal; higher is better.
        val score = Array(read.size + 1) { LongArray(width + 1) }
        for (i in read.indices.reversed()) {
            for (j in (0 until width).reversed()) {
                val skip = maxOf(score[i + 1][j], score[i][j + 1])
                score[i][j] =
                    if (sameMessage(read[i], saved[from + j])) {
                        maxOf(skip, score[i + 1][j + 1] + matchScore(i, from + j, offset))
                    } else {
                        skip
                    }
            }
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < read.size && j < width) {
            val matches =
                sameMessage(read[i], saved[from + j]) &&
                    score[i][j] == score[i + 1][j + 1] + matchScore(i, from + j, offset)
            when {
                matches -> {
                    pairs += i to from + j
                    i++
                    j++
                }

                score[i][j] == score[i][j + 1] -> {
                    j++
                }

                else -> {
                    i++
                }
            }
        }
        return pairs
    }

    private fun matchScore(
        i: Int,
        j: Int,
        offset: Int,
    ): Long = MATCH_WEIGHT - abs(j - i - offset)

    /**
     * Steps 1 and 3: the same hash, or someone else's message that differs only by a sender one side
     * did not show, with the time shown on both sides and equal. Two different known senders never
     * match, and neither does a missing time.
     */
    private fun exactMatch(
        a: ChatMessage,
        b: ChatMessage,
    ): Boolean {
        if (a == b) return true
        if (a.isMe || b.isMe || a.text != b.text) return false
        if (a.timeShown == null || a.timeShown != b.timeShown) return false
        return a.sender == null || b.sender == null
    }

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

    /** Step 4: same slot, changed text. Every identifying field must be shown and equal. */
    private fun isEdit(
        read: ChatMessage,
        saved: ChatMessage,
    ): Boolean {
        if (read.isMe != saved.isMe || read.text == saved.text) return false
        if (read.timeShown == null || read.timeShown != saved.timeShown) return false
        return read.isMe || (read.sender != null && read.sender == saved.sender)
    }

    /** Step 5. */
    private fun senderFills(
        stored: List<StoredRow>,
        read: List<ChatMessage>,
        alignment: Alignment,
    ): List<PlannedEdit> =
        alignment.pairs.mapNotNull { (i, j) ->
            val row = stored[j]
            val sender = read[i].sender
            if (row.message.isMe || row.message.sender != null || sender == null) {
                null
            } else {
                PlannedEdit(row.id, row.message.copy(sender = sender))
            }
        }

    /** Step 4 positions when every group fits into its gap; null if one gap is full. */
    private fun placeWithoutMoving(
        stored: List<StoredRow>,
        groups: Map<Int, List<ChatMessage>>,
    ): List<PlannedInsert>? {
        val inserts = mutableListOf<PlannedInsert>()
        for ((before, messages) in groups) {
            val low = stored.getOrNull(before - 1)?.position
            val high = stored.getOrNull(before)?.position
            val positions = positionsBetween(low, high, messages.size) ?: return null
            messages.zip(positions).mapTo(inserts) { (message, position) -> PlannedInsert(position, message) }
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
        edits: List<PlannedEdit>,
        alignment: Alignment,
        newAtEnd: List<ChatMessage>,
    ): MergePlan {
        val inserts = mutableListOf<PlannedInsert>()
        val moves = mutableListOf<PlannedMove>()
        var next = POSITION_STEP
        for (index in 0..stored.size) {
            groups[index]?.forEach { message ->
                inserts += PlannedInsert(next, message)
                next += POSITION_STEP
            }
            val row = stored.getOrNull(index) ?: continue
            if (row.position != next) moves += PlannedMove(row.id, next)
            next += POSITION_STEP
        }
        return MergePlan(inserts, edits, moves, alignment.anchored, newAtEnd)
    }
}
