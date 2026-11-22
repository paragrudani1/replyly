package com.paragrudani.replyly.history

import com.paragrudani.replyly.parser.ChatMessage
import java.time.LocalDate

/**
 * In-memory stand-in for the messages table of one chat: applies [HistoryMerge] plans the way
 * [RoomHistoryRepository] does, including the tail-first planning when [tailSize] is set. With a
 * `today` passed to [merge], rows stored without an exact date get that day, approx.
 */
internal class FakeHistory(
    private val tailSize: Int? = null,
) {
    private var nextId = 1L
    private val rows = mutableListOf<StoredRow>()

    /** Row ids given to the last plan's inserts, in [MergePlan.inserts] order. */
    var lastInsertedIds: List<Long> = emptyList()
        private set

    /** The chat's current position: the row of the last read's last message. */
    var currentRowId: Long? = null
        private set

    fun merge(
        read: List<ChatMessage>,
        today: LocalDate? = null,
    ): MergePlan {
        val all = sortedRows()
        val plan =
            if (tailSize == null || all.size < tailSize) {
                HistoryMerge.plan(all, read, currentRowId, today)
            } else {
                HistoryMerge.planAgainstTail(all.takeLast(tailSize), read, currentRowId, today)
                    ?: HistoryMerge.plan(all, read, currentRowId, today)
            }
        plan.moves.forEach { move -> update(move.id) { it.copy(position = move.position) } }
        plan.gapsCleared.forEach { id -> update(id) { it.copy(gapBefore = false) } }
        // As RoomHistoryRepository: an edit's null date leaves the saved one, metadata after edits.
        plan.edits.forEach { edit ->
            update(edit.id) { it.withExactDate(edit.message.date, edit.message) }
        }
        plan.metaUpdates.forEach { meta ->
            update(meta.id) {
                it.withExactDate(meta.date, it.message.copy(repliesToMe = it.message.repliesToMe || meta.repliesToMe))
            }
        }
        lastInsertedIds =
            plan.inserts.map { insert ->
                val id = nextId++
                val m = insert.message
                rows +=
                    StoredRow(
                        id,
                        insert.position,
                        m,
                        insert.gapBefore,
                        date = m.date ?: today,
                        dateIsApprox = m.date == null && today != null,
                    )
                id
            }
        val positions = rows.map { it.position }
        check(positions.toSet().size == positions.size) { "two rows share a position" }
        if (read.isNotEmpty()) {
            if (plan.currentRowId ==
                null
            ) {
                check(plan.inserts.last().message === read.last()) { "last insert is not the read's last" }
            }
            currentRowId = plan.currentRowId ?: lastInsertedIds.last()
        }
        return plan
    }

    fun sortedRows(): List<StoredRow> = rows.sortedBy { it.position }

    fun row(id: Long): StoredRow = rows.single { it.id == id }

    /** As [RoomHistoryRepository.lastMessages]: the [limit] rows ending at the current position. */
    fun lastMessages(limit: Int): List<StoredRow> {
        val sorted = sortedRows()
        val end = sorted.indexOfFirst { it.id == currentRowId }.takeIf { it >= 0 } ?: sorted.lastIndex
        return sorted.subList(maxOf(0, end + 1 - limit), end + 1)
    }

    fun messages(): List<ChatMessage> = sortedRows().map { it.message }

    fun texts(): List<String> = messages().map { it.text }

    /** Texts split at gap markers. */
    fun segments(): List<List<String>> =
        sortedRows().fold(mutableListOf<MutableList<String>>()) { segments, row ->
            if (segments.isEmpty() || row.gapBefore) segments += mutableListOf<String>()
            segments.last() += row.message.text
            segments
        }

    /** [message] with the row's exact date, or [date] (exact) when not null. */
    private fun StoredRow.withExactDate(
        date: LocalDate?,
        message: ChatMessage,
    ): StoredRow =
        if (date == null) {
            copy(message = message.copy(date = this.message.date))
        } else {
            copy(message = message.copy(date = date), date = date, dateIsApprox = false)
        }

    private fun update(
        id: Long,
        change: (StoredRow) -> StoredRow,
    ) {
        val i = rows.indexOfFirst { it.id == id }
        rows[i] = change(rows[i])
    }
}
