package com.paragrudani.replyly.feedback

import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.parser.ChatKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Instant

data class FeedbackEntry(
    val id: Long,
    val chatKey: ChatKey,
    val createdAt: Instant,
    val model: String,
    val prompt: String,
    val suggestions: List<Suggestion>,
    /** Index into [suggestions] of the one the user inserted, if any. */
    val insertedIndex: Int? = null,
    /** The message the user actually sent, seen in the next history read. */
    val finalSentText: String? = null,
)

/**
 * Local log for judging suggestion quality (spec 8). Contains chat text, so ticket #6 stores it
 * encrypted (SQLCipher via [com.paragrudani.replyly.security.DatabaseKeyProvider]).
 */
interface FeedbackLog {
    /** @return the new entry id. */
    suspend fun record(
        chatKey: ChatKey,
        model: String,
        prompt: String,
        suggestions: List<Suggestion>,
    ): Long

    suspend fun markInserted(
        entryId: Long,
        index: Int,
    )

    /**
     * Attaches [text] to the newest entry of [chatKey] that has an insert but no final message.
     * Called when history sees a new message from me after an insert.
     * @return true if an entry was updated.
     */
    suspend fun attachFinalMessage(
        chatKey: ChatKey,
        text: String,
    ): Boolean

    fun observeRecent(limit: Int): Flow<List<FeedbackEntry>>

    suspend fun purgeOlderThan(cutoff: Instant): Int
}

/** Stub until ticket #6. Not persisted. */
class InMemoryFeedbackLog(
    private val clock: Clock,
) : FeedbackLog {
    private val entries = MutableStateFlow<List<FeedbackEntry>>(emptyList())
    private var nextId = 1L

    override suspend fun record(
        chatKey: ChatKey,
        model: String,
        prompt: String,
        suggestions: List<Suggestion>,
    ): Long {
        val id = synchronized(this) { nextId++ }
        entries.update { it + FeedbackEntry(id, chatKey, clock.instant(), model, prompt, suggestions) }
        return id
    }

    override suspend fun markInserted(
        entryId: Long,
        index: Int,
    ) {
        entries.update { list -> list.map { if (it.id == entryId) it.copy(insertedIndex = index) else it } }
    }

    override suspend fun attachFinalMessage(
        chatKey: ChatKey,
        text: String,
    ): Boolean {
        var updated = false
        entries.update { list ->
            val target =
                list.lastOrNull { it.chatKey == chatKey && it.insertedIndex != null && it.finalSentText == null }
            updated = target != null
            list.map { if (it.id == target?.id) it.copy(finalSentText = text) else it }
        }
        return updated
    }

    override fun observeRecent(limit: Int): Flow<List<FeedbackEntry>> = entries.map { it.takeLast(limit).reversed() }

    override suspend fun purgeOlderThan(cutoff: Instant): Int {
        var removed = 0
        entries.update { list ->
            list.filter { it.createdAt >= cutoff }.also { removed = list.size - it.size }
        }
        return removed
    }
}
