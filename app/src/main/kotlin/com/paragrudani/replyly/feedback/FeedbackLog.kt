package com.paragrudani.replyly.feedback

import com.paragrudani.replyly.llm.PromptBuilder
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.parser.x.XMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Duration
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
    /** [PromptBuilder.VERSION] that produced the entry; entries from before prompt v2 are 1. */
    val promptVersion: Int = 1,
    /** The model's short reasoning (prompt v2, local only); null before v2, empty if the model left it out. */
    val analysis: String? = null,
    /** One id per panel open; null for entries from before prompt v2. */
    val panelId: String? = null,
    /** Input tokens read from the prompt cache (Anthropic only); null when the provider did not say. */
    val cacheReadTokens: Int? = null,
    /** X reply or quote (#30 PR 2); null for chats, X DMs and entries from before. */
    val xMode: XMode? = null,
)

/**
 * Local log for judging suggestion quality (spec 8). Contains chat text, so ticket #6 stores it
 * encrypted (SQLCipher via [com.paragrudani.replyly.security.DatabaseKeyProvider]).
 */
interface FeedbackLog {
    /**
     * @param analysis the model's reasoning: stored locally, never shown in the panel or logged.
     * @param panelId the same for every generation of one panel open (insert rate per panel).
     * @param cacheReadTokens [com.paragrudani.replyly.llm.TokenUsage.cacheReadTokens], a count only.
     * @param xMode X reply or quote (#30 PR 2, additive); null for chats. The app is in [chatKey].
     * @return the new entry id.
     */
    suspend fun record(
        chatKey: ChatKey,
        model: String,
        prompt: String,
        suggestions: List<Suggestion>,
        analysis: String? = null,
        promptVersion: Int = PromptBuilder.VERSION,
        panelId: String? = null,
        cacheReadTokens: Int? = null,
        xMode: XMode? = null,
    ): Long

    suspend fun markInserted(
        entryId: Long,
        index: Int,
    )

    /**
     * Attaches [text] to the newest entry of [chatKey] that has an insert but no final message and
     * is younger than [FINAL_MESSAGE_WINDOW]. Called when history sees a new message from me after
     * an insert.
     * @return true if an entry was updated.
     */
    suspend fun attachFinalMessage(
        chatKey: ChatKey,
        text: String,
    ): Boolean

    fun observeRecent(limit: Int): Flow<List<FeedbackEntry>>

    /** Insert rate and edit amount per prompt version, over every entry kept ([FeedbackStats]). */
    fun observeStats(): Flow<List<PromptVersionStats>> = flowOf(emptyList())

    suspend fun purgeOlderThan(cutoff: Instant): Int

    /**
     * Deletes entries older than the `retentionDays` setting. Passive capture calls it when the
     * accessibility service connects. @return how many were deleted.
     */
    suspend fun purgeExpired(): Int = 0

    /** Deletes every entry ("Clear log" in settings). @return how many were deleted. */
    suspend fun clear(): Int = purgeOlderThan(Instant.ofEpochMilli(Long.MAX_VALUE))
}

/** A message sent later than this after the suggestion is not treated as its final version. */
val FINAL_MESSAGE_WINDOW: Duration = Duration.ofHours(6)

/** In-memory fake for tests (AppGraph binds the real one). Not persisted. */
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
        analysis: String?,
        promptVersion: Int,
        panelId: String?,
        cacheReadTokens: Int?,
        xMode: XMode?,
    ): Long {
        val id = synchronized(this) { nextId++ }
        val entry =
            FeedbackEntry(
                id,
                chatKey,
                clock.instant(),
                model,
                prompt,
                suggestions,
                promptVersion = promptVersion,
                analysis = analysis,
                panelId = panelId,
                cacheReadTokens = cacheReadTokens,
                xMode = xMode,
            )
        entries.update { it + entry }
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
        val oldest = clock.instant().minus(FINAL_MESSAGE_WINDOW)
        entries.update { list ->
            val target =
                list.lastOrNull {
                    it.chatKey == chatKey && it.insertedIndex != null && it.finalSentText == null &&
                        it.createdAt > oldest
                }
            updated = target != null
            list.map { if (it.id == target?.id) it.copy(finalSentText = text) else it }
        }
        return updated
    }

    override fun observeRecent(limit: Int): Flow<List<FeedbackEntry>> = entries.map { it.takeLast(limit).reversed() }

    override fun observeStats(): Flow<List<PromptVersionStats>> =
        entries.map { list ->
            FeedbackStats.compute(
                list.map {
                    FeedbackStatsRow(
                        it.id,
                        it.promptVersion,
                        it.panelId,
                        it.insertedIndex?.let { i -> it.suggestions.getOrNull(i)?.text },
                        it.finalSentText,
                        chatKey = it.chatKey.encoded,
                        createdAtMillis = it.createdAt.toEpochMilli(),
                    )
                },
            )
        }

    override suspend fun purgeOlderThan(cutoff: Instant): Int {
        var removed = 0
        entries.update { list ->
            list.filter { it.createdAt >= cutoff }.also { removed = list.size - it.size }
        }
        return removed
    }
}
