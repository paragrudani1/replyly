package com.paragrudani.replyly.history

import com.paragrudani.replyly.parser.ChatMessage
import java.security.MessageDigest

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
 * [MessageHash] of (isMe, sender, text, time shown). Two messages also count as the same when
 * they differ only by a field the screen did not show: a `null` time (the bubble's bottom is cut
 * off) or a `null` sender of someone else's message (a group bubble whose name label is above the
 * screen). The same message can legitimately appear twice ("ok", "ok" at 6:41), and a bubble shows
 * the time but not the date, so a set of hashes cannot tell old from new. The merge instead lines
 * the read up against the saved sequence, like a diff:
 *
 * 1. **Find the overlap.** Every pair (read index `i`, saved index `j`) of equal messages votes for
 *    the offset `d = j - i`. The offset with the most votes wins (ties go to the newest offset),
 *    if at least half of the read messages that fall inside the saved range at that offset match.
 *    That rule rejects a lone coincidence deep in the history (yesterday's "gm" at 9:00 am),
 *    while a read whose first message is the saved last one (new messages below) or whose last
 *    message is the saved first one (scrolled up) is accepted.
 * 2. **Align.** A longest common subsequence between the read and the saved rows around that
 *    offset pairs up equal messages. On ties it prefers the latest saved rows, so when "ok", "ok"
 *    is saved and the screen shows "ok", "b", the "ok" pairs with the second one.
 * 3. **Edits.** Between aligned pairs, and before the first or after the last one, a read message
 *    and the saved message in the same slot are taken for an edit when they have the same author
 *    and the same time shown but different text. The saved row keeps its place and gets the new
 *    content. Pairing stops at the first slot that does not qualify.
 * 4. **Place new messages.** Each remaining read message is stored right before the saved row of
 *    the next read message that has one; if none follows, after the last saved row. Saved rows
 *    that the read skips (deleted for me, or a parser miss) are kept.
 *
 * Results by case: an identical re-read or a read inside the saved range is a no-op; new messages
 * below the tail are appended; scrolling up stores the older messages before the saved ones; a
 * read with no accepted overlap is appended after the last saved row (its true place is unknown,
 * so it is not reported in [MergePlan.newAtEnd]); reading the same screen again then overlaps and
 * adds nothing. Known limit: a read far above the saved range with no overlap lands at the end,
 * out of order, because nothing on screen says how old it is.
 *
 * Positions are spaced by [POSITION_STEP] so most inserts fit between neighbours. When a gap is
 * full, every row of the chat is renumbered (a [PlannedMove] per changed row).
 */
object HistoryMerge {
    const val POSITION_STEP: Long = 1L shl 16

    fun plan(
        stored: List<StoredRow>,
        read: List<ChatMessage>,
    ): MergePlan {
        if (read.isEmpty()) return MergePlan(emptyList(), emptyList(), emptyList(), anchored = false, emptyList())
        val saved = stored.map { it.message }
        val alignment = align(saved, read)

        val edits =
            read.indices
                .filter { alignment.edited[it] }
                .map { PlannedEdit(stored[alignment.counterpart[it]].id, read[it]) }

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

        val newAtEnd = if (alignment.anchored) groups[stored.size].orEmpty() else emptyList()
        val placed = placeWithoutMoving(stored, groups) ?: return renumber(stored, groups, edits, alignment, newAtEnd)
        return MergePlan(placed, edits, emptyList(), alignment.anchored, newAtEnd)
    }

    private class Alignment(
        /** For each read index, the saved index it is the same message as (or an edit of), or -1. */
        val counterpart: IntArray,
        val edited: BooleanArray,
        val anchored: Boolean,
    )

    private fun align(
        saved: List<ChatMessage>,
        read: List<ChatMessage>,
    ): Alignment {
        val counterpart = IntArray(read.size) { -1 }
        val edited = BooleanArray(read.size)
        val offset = bestOffset(saved, read) ?: return Alignment(counterpart, edited, anchored = false)

        val from = maxOf(0, offset - read.size)
        val to = minOf(saved.size, offset + 2 * read.size)
        val pairs = commonSubsequence(read, saved, from, to)
        if (pairs.isEmpty()) return Alignment(counterpart, edited, anchored = false)
        pairs.forEach { (i, j) -> counterpart[i] = j }

        fun pairEdits(
            startI: Int,
            startJ: Int,
            step: Int,
            endI: Int,
            endJ: Int,
        ) {
            var i = startI
            var j = startJ
            while (i != endI && j != endJ && canBeSameSlot(read[i], saved[j])) {
                counterpart[i] = j
                edited[i] = !sameMessage(read[i], saved[j])
                i += step
                j += step
            }
        }

        val (firstI, firstJ) = pairs.first()
        pairEdits(firstI - 1, firstJ - 1, step = -1, endI = -1, endJ = -1)
        pairs.zipWithNext { (i1, j1), (i2, j2) -> pairEdits(i1 + 1, j1 + 1, step = 1, endI = i2, endJ = j2) }
        val (lastI, lastJ) = pairs.last()
        pairEdits(lastI + 1, lastJ + 1, step = 1, endI = read.size, endJ = saved.size)
        return Alignment(counterpart, edited, anchored = true)
    }

    /** Step 1: the most-voted offset `saved index - read index`, or null if no offset is credible. */
    private fun bestOffset(
        saved: List<ChatMessage>,
        read: List<ChatMessage>,
    ): Int? {
        if (saved.isEmpty()) return null
        val byContent = saved.indices.groupBy { saved[it].isMe to saved[it].text }
        val votes = HashMap<Int, Int>()
        read.forEachIndexed { i, message ->
            byContent[message.isMe to message.text]?.forEach { j ->
                if (sameMessage(message, saved[j])) votes.merge(j - i, 1, Int::plus)
            }
        }
        return votes.entries
            .filter { (offset, count) -> 2 * count >= overlapLength(offset, read.size, saved.size) }
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>>({ it.value }, { it.key }))
            ?.key
    }

    /** How many read messages land inside the saved range at [offset]. */
    private fun overlapLength(
        offset: Int,
        readSize: Int,
        savedSize: Int,
    ): Int = minOf(readSize, savedSize - offset) - maxOf(0, -offset)

    /**
     * Step 2: longest common subsequence of [read] and `saved[from until to]`, as (read index,
     * saved index) pairs. Backtracking skips saved rows whenever that loses nothing, so matches
     * land on the latest saved rows.
     */
    private fun commonSubsequence(
        read: List<ChatMessage>,
        saved: List<ChatMessage>,
        from: Int,
        to: Int,
    ): List<Pair<Int, Int>> {
        val width = to - from
        val lengths = Array(read.size + 1) { IntArray(width + 1) }
        for (i in read.indices.reversed()) {
            for (j in (0 until width).reversed()) {
                lengths[i][j] =
                    if (sameMessage(read[i], saved[from + j])) {
                        lengths[i + 1][j + 1] + 1
                    } else {
                        maxOf(lengths[i + 1][j], lengths[i][j + 1])
                    }
            }
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < read.size && j < width) {
            when {
                lengths[i][j + 1] == lengths[i][j] -> {
                    j++
                }

                sameMessage(read[i], saved[from + j]) -> {
                    pairs += i to from + j
                    i++
                    j++
                }

                else -> {
                    i++
                }
            }
        }
        return pairs
    }

    private fun sameMessage(
        a: ChatMessage,
        b: ChatMessage,
    ): Boolean = a.text == b.text && canBeSameSlot(a, b)

    /** Same author and same time, allowing for fields the screen did not show. */
    private fun canBeSameSlot(
        a: ChatMessage,
        b: ChatMessage,
    ): Boolean {
        if (a.isMe != b.isMe) return false
        val senderOk = a.isMe || a.sender == null || b.sender == null || a.sender == b.sender
        val timeOk = a.timeShown == null || b.timeShown == null || a.timeShown == b.timeShown
        return senderOk && timeOk
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
