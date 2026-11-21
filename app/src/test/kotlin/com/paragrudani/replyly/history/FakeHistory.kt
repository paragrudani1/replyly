package com.paragrudani.replyly.history

import com.paragrudani.replyly.parser.ChatMessage

/**
 * In-memory stand-in for the messages table of one chat: applies [HistoryMerge] plans the way
 * [RoomHistoryRepository] does, including the tail-first planning when [tailSize] is set.
 */
internal class FakeHistory(
    private val tailSize: Int? = null,
) {
    private var nextId = 1L
    private val rows = mutableListOf<StoredRow>()

    /** Row ids given to the last plan's inserts, in [MergePlan.inserts] order. */
    var lastInsertedIds: List<Long> = emptyList()
        private set

    fun merge(read: List<ChatMessage>): MergePlan {
        val all = sortedRows()
        val plan =
            if (tailSize == null || all.size < tailSize) {
                HistoryMerge.plan(all, read)
            } else {
                HistoryMerge.planAgainstTail(all.takeLast(tailSize), read) ?: HistoryMerge.plan(all, read)
            }
        plan.moves.forEach { move -> update(move.id) { it.copy(position = move.position) } }
        plan.gapsCleared.forEach { id -> update(id) { it.copy(gapBefore = false) } }
        plan.edits.forEach { edit -> update(edit.id) { it.copy(message = edit.message) } }
        lastInsertedIds =
            plan.inserts.map { insert ->
                val id = nextId++
                rows += StoredRow(id, insert.position, insert.message, insert.gapBefore)
                id
            }
        val positions = rows.map { it.position }
        check(positions.toSet().size == positions.size) { "two rows share a position" }
        return plan
    }

    fun sortedRows(): List<StoredRow> = rows.sortedBy { it.position }

    fun messages(): List<ChatMessage> = sortedRows().map { it.message }

    fun texts(): List<String> = messages().map { it.text }

    /** Texts split at gap markers. */
    fun segments(): List<List<String>> =
        sortedRows().fold(mutableListOf<MutableList<String>>()) { segments, row ->
            if (segments.isEmpty() || row.gapBefore) segments += mutableListOf<String>()
            segments.last() += row.message.text
            segments
        }

    private fun update(
        id: Long,
        change: (StoredRow) -> StoredRow,
    ) {
        val i = rows.indexOfFirst { it.id == id }
        rows[i] = change(rows[i])
    }
}
