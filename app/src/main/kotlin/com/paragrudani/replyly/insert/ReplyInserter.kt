package com.paragrudani.replyly.insert

/**
 * Puts a suggestion into the focused input field of the chat app (ticket #1, ACTION_SET_TEXT).
 *
 * This is the only way Replyly touches another app, and it can only set text. Replyly never
 * sends: no API in this app may click, press Enter, or act on any other node.
 */
interface ReplyInserter {
    /** Replaces the text of the focused, editable field with [text]. */
    suspend fun insert(text: String): InsertResult
}

sealed interface InsertResult {
    data object Inserted : InsertResult

    /** The chat app's input field is gone or no longer focused. */
    data object NoFocusedField : InsertResult

    data class Failed(
        val reason: String,
    ) : InsertResult
}

/** Stub until ticket #1. */
object UnavailableReplyInserter : ReplyInserter {
    override suspend fun insert(text: String): InsertResult = InsertResult.Failed("Inserter not implemented yet (#1)")
}
