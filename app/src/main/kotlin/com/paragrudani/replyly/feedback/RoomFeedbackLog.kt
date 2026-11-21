package com.paragrudani.replyly.feedback

import com.paragrudani.replyly.feedback.db.FeedbackDatabase
import com.paragrudani.replyly.feedback.db.FeedbackEntryEntity
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.parser.ChatKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * [FeedbackLog] on the encrypted [FeedbackDatabase]. Recording also purges entries older than
 * [retentionDays] (from settings), at most once a day per process: no WorkManager needed.
 */
class RoomFeedbackLog(
    db: FeedbackDatabase,
    private val clock: Clock,
    private val retentionDays: Flow<Int>,
) : FeedbackLog {
    private val dao = db.feedbackDao()

    @Volatile private var lastPurgeAt: Instant? = null

    override suspend fun record(
        chatKey: ChatKey,
        model: String,
        prompt: String,
        suggestions: List<Suggestion>,
    ): Long {
        purgeIfDue()
        return dao.insert(
            FeedbackEntryEntity(
                chatKey = chatKey.encoded,
                createdAt = clock.millis(),
                model = model,
                prompt = prompt,
                suggestionsJson =
                    json.encodeToString(
                        suggestionListSerializer,
                        suggestions.map {
                            StoredSuggestion(it.intent, it.text)
                        },
                    ),
            ),
        )
    }

    override suspend fun markInserted(
        entryId: Long,
        index: Int,
    ) = dao.markInserted(entryId, index)

    override suspend fun attachFinalMessage(
        chatKey: ChatKey,
        text: String,
    ): Boolean = dao.attachFinalMessage(chatKey.encoded, text)

    override fun observeRecent(limit: Int): Flow<List<FeedbackEntry>> =
        dao.observeRecent(limit).map { rows ->
            rows.map {
                it.toEntry()
            }
        }

    override suspend fun purgeOlderThan(cutoff: Instant): Int = dao.deleteOlderThan(cutoff.toEpochMilli())

    override suspend fun clear(): Int = dao.deleteAll()

    /** Runs the retention purge unless it already ran in the last 24 hours. */
    suspend fun purgeIfDue(): Int {
        val now = clock.instant()
        val last = lastPurgeAt
        if (last != null && Duration.between(last, now) < Duration.ofDays(1)) return 0
        lastPurgeAt = now
        return purgeOlderThan(now.minus(Duration.ofDays(retentionDays.first().toLong())))
    }

    private fun FeedbackEntryEntity.toEntry() =
        FeedbackEntry(
            id = id,
            chatKey = ChatKey.decode(chatKey),
            createdAt = Instant.ofEpochMilli(createdAt),
            model = model,
            prompt = prompt,
            suggestions =
                json
                    .decodeFromString(
                        suggestionListSerializer,
                        suggestionsJson,
                    ).map { Suggestion(it.intent, it.text) },
            insertedIndex = insertedIndex,
            finalSentText = finalSentText,
        )

    @Serializable
    private data class StoredSuggestion(
        val intent: String,
        val text: String,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val suggestionListSerializer = ListSerializer(StoredSuggestion.serializer())
    }
}
