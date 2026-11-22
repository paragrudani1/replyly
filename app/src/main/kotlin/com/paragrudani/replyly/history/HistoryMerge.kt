package com.paragrudani.replyly.history

import com.paragrudani.replyly.parser.ChatMessage
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.math.abs

/** A saved message as the merge sees it: its row id, its ordering key, and its content. */
data class StoredRow(
    val id: Long,
    /** Orders the rows of one chat. Only the order matters; values have gaps between them. */
    val position: Long,
    /**
     * The saved content. Its [ChatMessage.date] is the exact date only (from a separator), `null`
     * when the row has none or only an approximate one.
     */
    val message: ChatMessage,
    /**
     * True on the first row of a block that was stored without overlapping the rows above it: the
     * history may miss messages right above this row, and the rows above may even be newer.
     */
    val gapBefore: Boolean = false,
    /**
     * The row's day: [message]'s exact date, or else the local day it was first seen on
     * ([dateIsApprox]). `null` when unknown (tests). An approximate day is never given to a read
     * message; the merge uses it only as the latest day the row's message can be from (see
     * [HistoryMerge], "Dates") and to refuse edits and fills across days (steps 5 and 6).
     */
    val date: LocalDate? = message.date,
    /** [date] is the day the row was first seen, not a date a separator showed. */
    val dateIsApprox: Boolean = false,
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
 * gets a sender or time it was stored without. [message] keeps the saved row's date and reply flag
 * when the read did not show them; a `null` date leaves the saved one as it is.
 */
data class PlannedEdit(
    val id: Long,
    val message: ChatMessage,
)

/**
 * A saved row that the read lined up with and that learns metadata it lacked: an exact [date]
 * (`null`: unchanged), or that it replies to me (never cleared).
 */
data class PlannedMeta(
    val id: Long,
    val date: LocalDate?,
    val repliesToMe: Boolean,
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
     * Messages stored after the end of the current segment (the segment that holds the current
     * position, see [HistoryMerge]), in an anchored read whose overlap reaches that end: these
     * arrived or were sent since the last read. Empty otherwise, because then it is not known
     * whether they are new (the history may have a gap).
     */
    val newAtEnd: List<ChatMessage>,
    /** Saved rows whose [StoredRow.gapBefore] is cleared: the gap above them is closed or moved up. */
    val gapsCleared: List<Long> = emptyList(),
    /**
     * For each read message, in read order: the id of the saved row it is (or is an edit of), or
     * null when it is stored as a new row (one of [inserts]).
     */
    val matchedRowIds: List<Long?> = emptyList(),
    /** Metadata the read adds to rows it lined up with. Apply after [edits]. */
    val metaUpdates: List<PlannedMeta> = emptyList(),
) {
    val isNoOp: Boolean
        get() =
            inserts.isEmpty() && edits.isEmpty() && moves.isEmpty() && gapsCleared.isEmpty() &&
                metaUpdates.isEmpty()

    /**
     * The new current position: the saved row id of the read's last message, or null when that
     * message is the last of [inserts]. Null for an empty read.
     */
    val currentRowId: Long? get() = matchedRowIds.lastOrNull()
}

/** The identity hash of a message: (isMe, sender, text, time shown), spec 5. The date is not part of it. */
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
 * **Dates** ([ChatMessage.date], issue #26) are metadata, not identity: every comparison below
 * ignores them and [ChatMessage.repliesToMe], with two exceptions. Two messages whose dates are
 * both known (exact) and differ are never equal, exactly equal or an edit of one another: "gm
 * 9:00 am" yesterday and today are two messages once a separator shows it. Likewise a read
 * message whose date is after the day a saved row was first seen ([StoredRow.dateIsApprox]) is
 * never that row: it was sent after the row was saved. A saved row the read lines up with takes
 * a reply flag it lacked, and an exact date it lacked only by step 8 ([MergePlan.metaUpdates]);
 * an edit keeps the saved ones the read does not show.
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
 * **Current position.** The row of the last read's last message ([MergePlan.currentRowId]): the
 * row it lined up with, or the row it was stored in. The caller keeps it per chat and passes it to
 * the next merge. The segment that holds it is the *current segment*; "the end" below means the
 * current segment's last row, not the history's last row, so a block of old messages stored last
 * after a jump far up stops counting as the newest once the user is back at the bottom.
 *
 * 1. **Find the overlap.** Every pair (read index `i`, saved index `j`) of *exactly* equal
 *    messages votes for its segment and the offset `d = j - i`. Exactly equal means both show the
 *    same time, and either the same hash, or someone else's message with the same text where one
 *    side has no sender (a group bubble whose name label is above the screen). The (segment,
 *    offset) with the most votes wins; ties go to the one whose overlap has the fewest messages
 *    that cannot match, then the most exactly equal [DISTINCTIVE_LENGTH] texts, then the newest.
 * 2. **Align.** A longest common subsequence between the read and that segment pairs up equal
 *    messages, only within [BAND] rows of the voted offset's diagonal. Here two messages also count
 *    as equal when they differ only by a field the screen did not show: a `null` time (the
 *    bubble's top or bottom is cut off) or a `null` sender of someone else's message. Among equally
 *    long alignments it keeps the most pairs with a [DISTINCTIVE_LENGTH] text, then weighs how
 *    equal the pairs are (every field shown, then exactly equal) against how far they sit off the
 *    diagonal (an exact pair one row off beats a time-less one on it), and closeness decides the
 *    rest: when "ok", "ok" is saved and the screen shows "ok", "b", the "ok" pairs with the second
 *    one (the screen's top). A pair that is not exact is kept only when an exact pair sits right next
 *    to it, with no read or saved message skipped between them.
 * 3. **Accept the overlap** only if the alignment
 *    - has at least two pairs; at least one of them exact, or two when every paired text is
 *      shorter than [DISTINCTIVE_LENGTH] ("ok", "😂", "gm", a sticker);
 *    - covers at least half of the read messages that fall inside the segment at the offset;
 *    - leaves at most one read message unpaired between its first and last pair;
 *    - has no slot between two pairs where the read shows a message and the saved side skips a
 *      row, unless the slot has as many read messages as saved rows and the read message is an
 *      edit of that row (step 5, or one whose days may differ between two distinctive exact
 *      pairs) in an alignment with a distinctive exact pair: two messages missed in one place are
 *      less likely than a coincidental alignment, or a run lined up one message off;
 *    - is not contradicted at its edges: a segment has no holes, so the read message right above
 *      the first pair must be the saved row right above it when both exist (likewise below the
 *      last pair), even when it looks like an edit (same author and time: a burst in one minute
 *      looks like that too).
 *
 *    One pair is enough only for new messages below the end, with a text of at least
 *    [DISTINCTIVE_LENGTH] characters: the read's first message equals the saved row at the end in
 *    every field, or it is that row with its time cut off (same author, sender and text; the saved
 *    one shows a time); and no read message equals another saved row at an offset where the read
 *    would fit just as well. This rejects a lone coincidence (yesterday's "gm" at 9:00 am, or a
 *    clipped "ok" that is the oldest saved row).
 * 4. **Close gaps.** If the read goes on past the end of its segment, and that remainder lines up
 *    on its own (step 1 to 3, two pairs or more) with the start of the next segment, the read
 *    shows that nothing is missing there: the two segments join and the marker is cleared. The
 *    same holds upwards for the previous segment.
 * 5. **Edits.** Only in an alignment with an exact pair of [DISTINCTIVE_LENGTH] text, only on one
 *    day (below), and only
 *    strictly between two aligned pairs of one segment with as many read messages as saved rows
 *    between them, never before the first or after the last one (there the read may show a new
 *    message): a read message and the saved message in the same slot are taken for an edit
 *    when both show a time and it is the same, they have the same author, and for someone else's
 *    message both show the same sender; and not when the pair
 *    before or after the slot could move into it (a neighbour equals the other side's message:
 *    then the read message may be new and the saved row one this read skipped), or could move one
 *    row along a run of equal messages and leave the read message an edit of the row it frees
 *    (the read message is then stored as new in the slot). The saved row keeps its place and gets the new
 *    content. Pairing stops at the first slot that does not
 *    qualify. *One day* ([Days]): a read message without a date is only known to be from the
 *    capture day or earlier (before the day of the first dated message below it on screen), and
 *    a row with an approximate date from the day it was first seen or earlier.
 *    - Days known to differ (two exact dates differ, the read message is dated after the day the
 *      row was first seen, or the row's exact date is after any day the read message can be
 *      from): never an edit, and step 3 does not accept the slot.
 *    - Days that may differ (a read message without a date captured on a later day than the row
 *      was first seen, or one dated before that day): the shared time may be a daily habit ("gm"
 *      at 9:00 am every day), so the slot must look like an edit on its own: a [DISTINCTIVE_LENGTH]
 *      new text, and both neighbours exact pairs (short ones too). With distinctive exact
 *      neighbours but a short new text, step 3 accepts the slot and the read message is stored
 *      as a new message there; otherwise step 3 does not accept the slot.
 *    - Otherwise (the same exact date, a read dated the day the row was first seen, an undated
 *      read on that day, or a day unknown): as above.
 *    So yesterday's edit re-read today without a separator is applied, and a "gm" at the old
 *    message's time is not.
 * 6. **Fill in.** An aligned saved message stored without a time gets the time the read shows,
 *    and one from someone else stored without a sender gets the sender the read shows, so a row
 *    does not keep matching every message with its text. Only for pairs with another pair on
 *    each side, on days not known or thought to differ (step 5, the last case), and not inside a
 *    run of the same text from the same side
 *    (two "ok"s in a row), on screen or saved: at the edge of the overlap, or inside a run, the
 *    read message may be one the history missed, right next to that row.
 * 7. **Place new messages.** Each remaining read message is stored right before the saved row of
 *    the next read message that has one; messages after the last pair go right after that pair's
 *    row, inside its segment, even when later segments follow. New messages above the first row
 *    of a segment take over its gap marker. Saved rows that the read skips (deleted for me, or a
 *    parser miss) are kept.
 * 8. **Date upgrades.** A saved row without an exact date takes the date a read message shows
 *    only from a pairing sure enough to stick for good (an exact date is never corrected): the
 *    read anchored; the message is neither the read's first nor its last (where the edge limits
 *    below act); the row was not first seen before that date; and the message has a
 *    [DISTINCTIVE_LENGTH] text, or both its neighbours on screen paired with the rows right
 *    above and below that row in its segment. Otherwise the row stays approx, and a later read
 *    can date it. An "ok" or "gm" lined up with another day's twin (one of the limits below)
 *    then costs at most a duplicate-looking copy, never a wrong date.
 *
 * Results by case: an identical re-read or a read inside the saved range is a no-op (it only moves
 * the current position); new messages below the end are appended and reported in
 * [MergePlan.newAtEnd]; scrolling up stores the older messages before the saved ones; a read with
 * no accepted overlap is appended after the last saved row with a gap marker (not reported in
 * [MergePlan.newAtEnd]) and becomes the current segment; reading the same screen again then
 * overlaps and adds nothing.
 *
 * Known limits:
 * - A read far above the saved range with no overlap lands at the end, after newer rows, because
 *   nothing on screen says how old it is, and it stays there. While the user reads on from it,
 *   it is the current segment, so messages below it are reported in [MergePlan.newAtEnd]
 *   although they are old. Back at the bottom, the first read lines up with the newest rows
 *   (before the old block) and moves the current position there; that read reports nothing in
 *   [MergePlan.newAtEnd], the reads after it do.
 * - A scroll-up that shows only one saved message does not overlap; it is appended after a gap.
 *   So is a read whose only overlap is one short message ("ok", "😂", a sticker): it repeats too
 *   often to anchor on its own, so its new messages are not reported in [MergePlan.newAtEnd].
 * - A message deleted for me (or missed by the parser) right next to the first or last aligned
 *   message makes the read look like a coincidence: it is appended after a gap, and the messages
 *   it shares with the history are stored twice, in two segments. The same happens to an edit of
 *   the first or last visible message.
 * - An edit right after or before a message equal to the edited one as shown (the second of two
 *   "ok"s), right next to a run of two equal messages from its author in its minute ("haan",
 *   "haan"), or in an alignment of short messages only, is stored as a new message next to the
 *   old one.
 * - An edit to a short text ("ok" to "okk") read on a later day than the row was first seen,
 *   without a date on screen: nothing tells it from a daily habit. Between two distinctive exact
 *   pairs it is stored as a new message next to the old one; next to a short neighbour the read
 *   is not accepted and is appended after a gap (the messages it shares with the history are
 *   stored twice, as for an edit at the edges).
 * - The read misses a message (a parser miss) in the slot where an earlier read missed another
 *   one. Nobody can tell their order; with the same author and minute the read one is taken for
 *   an edit of the saved one (it overwrites it), otherwise both are kept in a guessed order.
 * - A run of identical messages that ends the current segment, with a saved row without a time:
 *   saved `ok (no time), ok 8:03 pm, ok 8:03 pm`, read `ok 7:38 pm, ok 8:03 pm` three times. The
 *   time-less row is taken for the 7:38 one, so one "ok 8:03 pm" is stored and reported in
 *   [MergePlan.newAtEnd], although the time-less row may have been one of the 8:03 ones and
 *   nothing arrived. Accepted: it only affects the feedback log (about 1 in 36,000 reads in the
 *   simulator). Likewise, a run where an earlier read missed one message: the read that shows it
 *   stores it last in the run, and reports it when the run ends the current segment.
 * - The one-pair rules cannot tell the saved row at the end from a later or older message equal to
 *   it in every shown field (a clipped "see you soon" sent again, or "gm 9:00 am" from another
 *   day). The read is then placed after that row without a gap marker: the messages between are
 *   missing, and if the read was older, its messages are reported as new.
 * - A read whose top or bottom bubble, as shown, could also be the message just above or below it
 *   (inside a run of identical "ok"s in one minute, or next to a bubble missing from the read)
 *   cannot tell how many of the run it shows; the merge may store one too few, and the current
 *   position may be one row off inside the run.
 * - A row saved without a time next to a message the history missed (same author and text) is
 *   taken for that message when a read shows it: the missed one is not stored.
 * - A read whose overlap is made only of messages identical in every shown field to a saved run
 *   (same authors, texts and times, on another day, or a run of identical short messages in one
 *   minute) is taken for that run, possibly one message off inside it.
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

    /** Larger than any total of [DISTINCTIVE_WEIGHT]s, so match count always wins. */
    private const val MATCH_WEIGHT: Long = 1L shl 40

    /** Larger than any total of fidelity and distance terms, so distinctive pairs win next. */
    private const val DISTINCTIVE_WEIGHT: Long = 1L shl 30

    /**
     * Per fidelity level of a pair, against [DISTANCE_WEIGHT] per row off the diagonal: an exact
     * pair one row off beats a pair with a missing time on the diagonal, but not three rows off.
     */
    private const val FIDELITY_WEIGHT: Long = 3

    private const val DISTANCE_WEIGHT: Long = 2

    /**
     * Plans a read against every saved row of a chat ([stored] in position order). [currentRowId]
     * is the chat's current position before this read (null: none known, the last row is used).
     */
    fun plan(
        stored: List<StoredRow>,
        read: List<ChatMessage>,
        currentRowId: Long? = null,
        today: LocalDate? = null,
    ): MergePlan = planOrNull(stored, read, currentRowId, tailOnly = false, today)!!

    /**
     * Plans a read against only the newest saved rows of a chat ([tail], in position order), so a
     * capture in a long chat does not load all of it. Returns `null` when the read may concern
     * older rows: no overlap with the tail, the read reaches above the tail's first row, the
     * overlap is weak (a single pair, or a new or edited message between the pairs), the current
     * position is above the tail, or an insert needs every row renumbered. Then call [plan] with
     * the whole chat.
     */
    fun planAgainstTail(
        tail: List<StoredRow>,
        read: List<ChatMessage>,
        currentRowId: Long? = null,
        today: LocalDate? = null,
    ): MergePlan? = planOrNull(tail, read, currentRowId, tailOnly = true, today)

    private fun planOrNull(
        stored: List<StoredRow>,
        read: List<ChatMessage>,
        currentRowId: Long?,
        tailOnly: Boolean,
        today: LocalDate?,
    ): MergePlan? {
        if (read.isEmpty()) return MergePlan(emptyList(), emptyList(), emptyList(), anchored = false, emptyList())
        val current = currentRowId?.let { id -> stored.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        if (tailOnly && currentRowId != null && current == null) return null
        // The end of the current segment: new messages right after it are new (step 3, step 7).
        val currentEnd =
            (current ?: stored.lastIndex).let { c ->
                if (c < 0) -1 else ((c + 1..stored.lastIndex).firstOrNull { stored[it].gapBefore } ?: stored.size) - 1
            }
        val alignment = Aligner(stored, read, currentEnd, today).align()
        val firstPair = alignment.pairs.firstOrNull()
        val lastPair = alignment.pairs.lastOrNull()
        if (tailOnly) {
            // In the tail, a weak alignment may be a coincidence that the whole chat would outvote:
            // one pair, or a read message between the pairs that is new or edited.
            if (firstPair == null || lastPair == null || firstPair.second < firstPair.first) return null
            if (alignment.pairs.size == 1 || alignment.pairs.size != lastPair.first - firstPair.first + 1) return null
        }

        val dateFor = DateTrust(stored, read, alignment)
        val edits =
            read.indices
                .filter { alignment.edited[it] }
                .map { i ->
                    val saved = stored[alignment.counterpart[i]]
                    val m = read[i]
                    PlannedEdit(
                        saved.id,
                        m.copy(
                            date = dateFor(i) ?: saved.message.date,
                            repliesToMe =
                                m.repliesToMe || saved.message.repliesToMe,
                        ),
                    )
                } + fills(stored, read, alignment)
        val metaUpdates =
            read.indices.mapNotNull { i ->
                val j = alignment.counterpart[i]
                if (j < 0 || alignment.edited[i]) return@mapNotNull null
                val saved = stored[j].message
                val date = dateFor(i)?.takeIf { saved.date == null }
                val replies = read[i].repliesToMe && !saved.repliesToMe
                if (date == null && !replies) null else PlannedMeta(stored[j].id, date, replies)
            }

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
            if (lastPair != null && lastPair.second == currentEnd) groups[currentEnd + 1].orEmpty() else emptyList()
        val matched = read.indices.map { i -> alignment.counterpart[i].takeIf { it >= 0 }?.let { stored[it].id } }
        val placed = placeWithoutMoving(stored, groups, gapGroup)
        return when {
            placed != null -> {
                MergePlan(placed, edits, emptyList(), alignment.anchored, newAtEnd, gapsCleared, matched, metaUpdates)
            }

            tailOnly -> {
                null
            }

            else -> {
                renumber(stored, groups, gapGroup, edits, alignment.anchored, newAtEnd, gapsCleared)
                    .copy(matchedRowIds = matched, metaUpdates = metaUpdates)
            }
        }
    }

    /**
     * Step 8: the date a read message gives the saved row it lined up with, or `null` when the
     * pairing is not sure enough to make the row's date exact for good. See the KDoc.
     */
    private class DateTrust(
        private val stored: List<StoredRow>,
        private val read: List<ChatMessage>,
        private val alignment: Alignment,
    ) {
        private val pairs = alignment.pairs.toHashSet()

        operator fun invoke(i: Int): LocalDate? {
            val date = read[i].date ?: return null
            val j = alignment.counterpart[i]
            if (!alignment.anchored || j < 0 || i == 0 || i == read.lastIndex) return null
            // A row first seen before the day the read shows cannot be that message.
            val row = stored[j]
            if (row.dateIsApprox && row.date != null && date.isAfter(row.date)) return null
            if (distinctive(read[i])) return date
            val neighboursPaired =
                (i - 1 to j - 1) in pairs && (i + 1 to j + 1) in pairs &&
                    !stored[j].gapBefore && !stored[j + 1].gapBefore
            return date.takeIf { neighboursPaired }
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
        /** For each read index, it has a counterpart known to be on the same day ([Days.SAME]). */
        val sameDay: BooleanArray,
    ) {
        val anchored: Boolean get() = pairs.isNotEmpty()
    }

    /** Steps 1 to 5 for one read. */
    private class Aligner(
        private val stored: List<StoredRow>,
        private val read: List<ChatMessage>,
        /** The saved index of the current segment's last row (-1 for an empty history). */
        private val currentEnd: Int,
        /** The capture day, for [days]; `null` when unknown. */
        private val today: LocalDate?,
    ) {
        private val saved = stored.map { it.message }

        /** For a row with an approximate date: the day it was first seen, the latest day it can be from. */
        private val latest = stored.map { row -> row.date?.takeIf { row.dateIsApprox } }

        /**
         * Read message [i] shows a date after the day saved row [j] was first seen: it was sent
         * after that row was saved, so it is another message, as with two known dates that differ.
         */
        private fun tooLate(
            i: Int,
            j: Int,
        ): Boolean {
            val bound = latest[j] ?: return false
            return read[i].date?.isAfter(bound) == true
        }

        private fun exact(
            i: Int,
            j: Int,
        ) = !tooLate(i, j) && exactMatch(read[i], saved[j])

        private fun same(
            i: Int,
            j: Int,
        ) = !tooLate(i, j) && sameMessage(read[i], saved[j])

        private fun identical(
            i: Int,
            j: Int,
        ) = !tooLate(i, j) && sameIdentity(read[i], saved[j])

        private fun edit(
            i: Int,
            j: Int,
        ) = !tooLate(i, j) && isEdit(read[i], saved[j])

        /**
         * For each read message, the latest day it can be from: its exact date; else the day
         * before the first dated message below it on screen (a separator lies between them), or
         * the capture day if earlier or nothing below is dated. `null` when nothing bounds it.
         */
        private val readLatest: List<LocalDate?> =
            run {
                val latest = arrayOfNulls<LocalDate>(read.size)
                var below: LocalDate? = null
                for (i in read.indices.reversed()) {
                    val date = read[i].date
                    latest[i] = date ?: listOfNotNull(today, below?.minusDays(1)).minOrNull()
                    if (date != null) below = date
                }
                latest.toList()
            }

        /**
         * Steps 5 and 6: what is known about the days of read message [i] and saved row [j]. See
         * [Days].
         */
        private fun days(
            i: Int,
            j: Int,
        ): Days {
            val row = stored[j]
            val rowDay = row.date ?: return Days.SAME
            val readDate = read[i].date
            if (readDate != null) {
                return when {
                    readDate == rowDay -> Days.SAME

                    // An exact row on another day, or a read message sent after the row was seen.
                    !row.dateIsApprox || readDate.isAfter(rowDay) -> Days.DIFFERENT

                    else -> Days.UNSURE
                }
            }
            val latest = readLatest[i] ?: return Days.SAME
            return when {
                // The row's exact day is after any day the read message can be from.
                !row.dateIsApprox && rowDay.isAfter(latest) -> Days.DIFFERENT

                today == null || today == rowDay -> Days.SAME

                else -> Days.UNSURE
            }
        }

        /**
         * Step 5: on [Days.UNSURE] days, the slot looks like a real edit on its own: the new text
         * is [distinctive] and both neighbours are exact pairs (short texts too: the alignment
         * already has a distinctive exact anchor), so the slot sits between the right two
         * messages. A daily "gm" at 9:00 am lined up with another day's message at 9:00 am has
         * no distinctive new text.
         */
        private fun editOnItsOwn(
            i: Int,
            j: Int,
            pairs: Set<Pair<Int, Int>>,
        ): Boolean = distinctive(read[i]) && slotBetweenExactPairs(i, j, pairs)

        /** The pairs right above and below slot ([i], [j]) are exact pairs. */
        private fun slotBetweenExactPairs(
            i: Int,
            j: Int,
            pairs: Set<Pair<Int, Int>>,
        ): Boolean = listOf(i - 1 to j - 1, i + 1 to j + 1).all { (a, b) -> (a to b) in pairs && exact(a, b) }

        /** The pairs right above and below slot ([i], [j]) are exact pairs with a [distinctive] text. */
        private fun slotBetweenSurePairs(
            i: Int,
            j: Int,
            pairs: Set<Pair<Int, Int>>,
        ): Boolean =
            listOf(i - 1 to j - 1, i + 1 to j + 1).all { (a, b) ->
                (a to b) in pairs && distinctive(read[a]) && exact(a, b)
            }

        /** Step 5: read message [i] may be taken for an edit of saved row [j] as far as days go. */
        private fun editDaysOk(
            i: Int,
            j: Int,
            pairs: Set<Pair<Int, Int>>,
        ): Boolean =
            when (days(i, j)) {
                Days.SAME -> true
                Days.UNSURE -> editOnItsOwn(i, j, pairs)
                Days.DIFFERENT -> false
            }

        /** The gap-free segments, as saved index ranges, in order. */
        private val segments: List<IntRange>

        /** For each saved index, the index of its segment in [segments]. */
        private val segmentOf = IntArray(stored.size)

        // Candidates share everything but the sender (and the metadata); exactMatch checks those.
        private val byMessage = saved.indices.groupBy { candidateKey(saved[it]) }

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
                    ?: return Alignment(counterpart, edited, emptyList(), emptyList(), BooleanArray(read.size))

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

            // Step 5: edits only strictly between two aligned pairs of one segment, in an alignment
            // with a distinctive exact pair.
            val strong = strongAnchor(pairs)
            val pairSet = pairs.toHashSet()
            pairs.zipWithNext { (i1, j1), (i2, j2) ->
                // A slot with as many read messages as saved rows; otherwise a saved row is missing
                // from the read anyway, and the read message may as well be new.
                if (!strong || segmentOf[j1] != segmentOf[j2] || i2 - i1 != j2 - j1) return@zipWithNext
                var i = i1 + 1
                var j = j1 + 1
                while (i < i2 && j < j2 && edit(i, j) && !shiftable(i, j) && editDaysOk(i, j, pairSet)) {
                    counterpart[i] = j
                    edited[i] = true
                    i++
                    j++
                }
            }
            val sameDay = BooleanArray(read.size) { i -> counterpart[i] >= 0 && days(i, counterpart[i]) == Days.SAME }
            return Alignment(counterpart, edited, pairs, joins, sameDay)
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

        /**
         * Step 5: the pair before or after the slot could move into it (a neighbour equals the
         * other side's message), so the read message may as well be new and the saved row one the
         * read skipped. Then it is not taken for an edit: an insert never overwrites a message.
         */
        private fun shiftable(
            i: Int,
            j: Int,
        ): Boolean =
            same(i - 1, j) ||
                same(i, j - 1) ||
                (i + 1 < read.size && same(i + 1, j)) ||
                (j + 1 < saved.size && same(i, j + 1)) ||
                editOfNeighbour(i, j)

        /**
         * Step 5: the pair before the slot could move one row up inside a run of equal
         * messages ("ok", "ok"), and the read message is then an edit of the row it frees (or the
         * same below the slot). The read missed a bubble, and the slot's saved row is that one:
         * taking the read message for an edit of it would overwrite another message ("ok" edited
         * to "ok!" lined up with the sticker the read missed).
         */
        private fun editOfNeighbour(
            i: Int,
            j: Int,
        ): Boolean =
            (i >= 1 && j >= 2 && same(i - 1, j - 2) && edit(i, j - 1)) ||
                (i + 1 < read.size && j + 2 < saved.size && same(i + 1, j + 2) && edit(i, j + 1))

        /** Step 1: the best (segment index, offset `saved index - read index`), or null. */
        private fun bestAnchor(
            range: IntRange,
            allowed: IntRange,
        ): Pair<Int, Int>? {
            val votes = HashMap<Pair<Int, Int>, Int>()
            for (i in range) {
                byMessage[candidateKey(read[i])]
                    ?.filter { j -> segmentOf[j] in allowed && exact(i, j) }
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
                        { (segment, offset) -> -distinctiveVotes(offset, range, segments[segment]) },
                        { -it.first },
                        { -it.second },
                    ),
                )
        }

        /** How many read messages with a [distinctive] text in [range] are exactly equal at [offset]. */
        private fun distinctiveVotes(
            offset: Int,
            range: IntRange,
            segment: IntRange,
        ): Int =
            range.count {
                it + offset in segment && distinctive(read[it]) &&
                    exact(it, it + offset)
            }

        /** How many read messages in [range] that land inside [segment] at [offset] cannot match there. */
        private fun mismatches(
            offset: Int,
            range: IntRange,
            segment: IntRange,
        ): Int = range.count { it + offset in segment && !same(it, it + offset) }

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
                    j == currentEnd &&
                    identical(i, j) &&
                    distinctive(read[i]) &&
                    !matchesElsewhere(j)
            }
            val (first, last) = pairs.first().first to pairs.last().first
            val exactPairs = pairs.count { (i, j) -> exact(i, j) }
            // Short texts repeat too often: with only those, a second exact pair is needed.
            val exactNeeded = if (pairs.none { (i, _) -> distinctive(read[i]) }) 2 else 1
            return exactPairs >= exactNeeded &&
                2 * pairs.size >= overlapLength(offset, range, segment) &&
                last - first + 1 - pairs.size <= MAX_UNPAIRED_INSIDE &&
                !substitutes(pairs)
        }

        /**
         * Step 3: between two pairs, the read shows a message where the saved side skips a row,
         * and the read message is not an edit of that row (another author, time or sender): the
         * pairs around such a slot are more likely a coincidence, or a run lined up one message
         * off, than two messages missed in the same place.
         */
        private fun substitutes(pairs: List<Pair<Int, Int>>): Boolean {
            val pairSet = pairs.toHashSet()
            return pairs.zipWithNext().any { (a, b) ->
                val (i1, j1) = a
                val (i2, j2) = b
                val (i, j) = i1 + 1 to j1 + 1
                val editSlot =
                    i2 - i1 == j2 - j1 && strongAnchor(pairs) && edit(i, j) &&
                        when (days(i, j)) {
                            Days.SAME -> true

                            // Edit or not, the slot sits between two sure pairs, or looks like
                            // an edit on its own: an edit the days cannot confirm is stored as a
                            // new message there.
                            Days.UNSURE -> slotBetweenSurePairs(i, j, pairSet) || editOnItsOwn(i, j, pairSet)

                            Days.DIFFERENT -> false
                        }
                i2 > i1 + 1 && j2 > j1 + 1 && !editSlot
            }
        }

        /**
         * Steps 3 and 5: an exact pair with a [distinctive] text. Only such an alignment is sure
         * enough to change a saved message's text (an edit): "gm" and "ok" pairs can be another day.
         */
        private fun strongAnchor(pairs: List<Pair<Int, Int>>): Boolean =
            pairs.any { (i, j) -> distinctive(read[i]) && exact(i, j) }

        /**
         * Step 3: a segment has no holes, so the read message right above the first pair must be
         * the saved row right above it, if both exist; the same below the last pair. A different
         * message there means the pairs are a coincidence, even when it looks like an edit: edits
         * are never applied at the edges, and a burst from one author in one minute looks like one.
         */
        private fun contradicted(
            pairs: List<Pair<Int, Int>>,
            range: IntRange,
            segment: IntRange,
        ): Boolean {
            val (i0, j0) = pairs.first()
            val (i1, j1) = pairs.last()
            return (i0 > range.first && j0 > segment.first) || (i1 < range.last && j1 < segment.last)
        }

        /**
         * Step 3, one pair: some read message is exactly equal to a saved row other than [anchor],
         * at an offset where the rest of the read fits that row's segment (so the read may be there).
         */
        private fun matchesElsewhere(anchor: Int): Boolean =
            read.indices.any { i ->
                byMessage[candidateKey(read[i])].orEmpty().any { j ->
                    j != anchor && exact(i, j) && fitsAt(j - i, segments[segmentOf[j]])
                }
            }

        private fun fitsAt(
            offset: Int,
            segment: IntRange,
        ): Boolean = read.indices.all { k -> k + offset !in segment || same(k, k + offset) }

        /** Step 3, one pair: the read's top bubble is the saved last message with its time cut off. */
        private fun clippedTopOnly(): List<Pair<Int, Int>>? {
            val top = read.first()
            val last = saved.getOrNull(currentEnd) ?: return null
            val sameShown =
                top.isMe == last.isMe &&
                    top.text == last.text &&
                    !datesDiffer(top, last) &&
                    !tooLate(0, currentEnd) &&
                    distinctive(top) &&
                    top.sender == last.sender &&
                    (top.isMe || top.sender != null)
            val clipped = top.timeShown == null && last.timeShown != null
            return if (sameShown && clipped &&
                !matchesElsewhere(currentEnd)
            ) {
                listOf(0 to currentEnd)
            } else {
                null
            }
        }

        /** Step 2: drops pairs that are not exact unless an exact pair sits right next to them. */
        private fun adjacentToExact(pairs: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
            fun exactAt(k: Int) = pairs.getOrNull(k)?.let { (i, j) -> exact(i, j) } == true

            fun next(
                a: Int,
                b: Int,
            ) = pairs[b].first == pairs[a].first + 1 && pairs[b].second == pairs[a].second + 1
            return pairs.filterIndexed { k, _ ->
                exactAt(k) || (k > 0 && exactAt(k - 1) && next(k - 1, k)) || (exactAt(k + 1) && next(k, k + 1))
            }
        }

        /**
         * Step 2: longest common subsequence of `read[range]` and [segment], as (read index, saved
         * index) pairs within [BAND] of the [offset] diagonal. Among alignments with the most
         * matches it picks the one with the most [distinctive] pairs (a "message 12" rather than
         * an "ok" next to it), then by a score of how equal the pairs are (every field shown and
         * equal, then [exactMatch], then only [sameMessage]) against how far they sit off the
         * diagonal (`|j - i - offset|`): a read "ok 5:57 pm" pairs with the saved "ok 5:57 pm" one
         * row off rather than a clipped "ok" on the diagonal, but not with one three rows off.
         * Closeness also settles which of two equal "ok"s a read message is.
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
                return abs(j - i - offset) <= BAND && same(i, j)
            }

            fun gain(
                r: Int,
                c: Int,
            ): Long {
                val i = range.first + r
                val j = from + c
                // Fully equal counts only with every field shown: two missing senders are not equal.
                val fidelity =
                    when {
                        !exact(i, j) -> 0
                        identical(i, j) && (read[i].isMe || read[i].sender != null) -> 2
                        else -> 1
                    }
                val bonus = (if (distinctive(read[i])) DISTINCTIVE_WEIGHT else 0L) + fidelity * FIDELITY_WEIGHT
                return MATCH_WEIGHT + bonus - DISTANCE_WEIGHT * abs(j - i - offset)
            }

            // score = matches * MATCH_WEIGHT + distinctive matches * DISTINCTIVE_WEIGHT + fidelity *
            // FIDELITY_WEIGHT - distance from the offset diagonal * DISTANCE_WEIGHT; higher is better.
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
        if (a.timeShown == null || a.timeShown != b.timeShown || datesDiffer(a, b)) return false
        if (sameIdentity(a, b)) return true
        if (a.isMe || b.isMe || a.text != b.text) return false
        return a.sender == null || b.sender == null
    }

    /** Both dates are known and differ: never the same message, whatever else they share. */
    private fun datesDiffer(
        a: ChatMessage,
        b: ChatMessage,
    ): Boolean = a.date != null && b.date != null && a.date != b.date

    /** Equal in every identity field (author, sender, text, time shown) and not on two known days. */
    private fun sameIdentity(
        a: ChatMessage,
        b: ChatMessage,
    ): Boolean =
        a.isMe == b.isMe && a.sender == b.sender && a.text == b.text && a.timeShown == b.timeShown && !datesDiffer(a, b)

    /** The lookup key of [Aligner]'s candidates: the message without sender and metadata. */
    private fun candidateKey(message: ChatMessage): ChatMessage =
        message.copy(sender = null, date = null, repliesToMe = false)

    /** Long enough that a lone pair is unlikely to be a coincidence. */
    private fun distinctive(message: ChatMessage): Boolean = message.text.trim().length >= DISTINCTIVE_LENGTH

    /** Equal, allowing for a time or a sender of someone else's message that the screen did not show. */
    private fun sameMessage(
        a: ChatMessage,
        b: ChatMessage,
    ): Boolean {
        if (a.isMe != b.isMe || a.text != b.text || datesDiffer(a, b)) return false
        val senderOk = a.isMe || a.sender == null || b.sender == null || a.sender == b.sender
        val timeOk = a.timeShown == null || b.timeShown == null || a.timeShown == b.timeShown
        return senderOk && timeOk
    }

    /** Step 5: same slot, changed text. Every identifying field must be shown and equal. */
    private fun isEdit(
        read: ChatMessage,
        saved: ChatMessage,
    ): Boolean {
        if (read.isMe != saved.isMe || read.text == saved.text || datesDiffer(read, saved)) return false
        if (read.timeShown == null || read.timeShown != saved.timeShown) return false
        return read.isMe || (read.sender != null && read.sender == saved.sender)
    }

    /** Steps 5 and 6: what is known about the days of a read message and a saved row. */
    private enum class Days {
        /**
         * The same day, or as good as on `main`: both exact and equal, or the read message's
         * exact date is the day the row was first seen, or a read message without a date is read
         * on the day the row was first seen; also when a day is unknown (tests).
         */
        SAME,

        /**
         * Possibly two days: a read message without a date read on a later day than the row was
         * first seen (the message may be older than the capture), or a read message dated before
         * the day the row was first seen. Nothing proves either way.
         */
        UNSURE,

        /**
         * Two days for sure: two exact dates differ, the read message is dated after the day the
         * row was first seen, or the row's exact date is after any day the read message can be from.
         */
        DIFFERENT,
    }

    /** Step 6: only pairs with another pair on each side, outside runs, on one day; see the KDoc. */
    private fun fills(
        stored: List<StoredRow>,
        read: List<ChatMessage>,
        alignment: Alignment,
    ): List<PlannedEdit> =
        alignment.pairs.drop(1).dropLast(1).mapNotNull { (i, j) ->
            // Inside a run ("ok" 10:18, "ok" 10:19), on screen or saved, the row may be any of the run.
            val inRun =
                listOf(i - 1, i + 1).any { k ->
                    read.getOrNull(k)?.let { it.isMe == read[i].isMe && it.text == read[i].text } == true
                } ||
                    listOf(j - 1, j + 1).any { k ->
                        stored.getOrNull(k)?.message?.let { it.isMe == read[i].isMe && it.text == read[i].text } == true
                    }
            if (inRun || !alignment.sameDay[i]) return@mapNotNull null
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
