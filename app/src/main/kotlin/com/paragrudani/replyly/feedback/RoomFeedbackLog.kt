package com.paragrudani.replyly.feedback

import com.paragrudani.replyly.feedback.db.FeedbackDao
import com.paragrudani.replyly.feedback.db.FeedbackDatabase
import com.paragrudani.replyly.feedback.db.FeedbackEntryEntity
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.parser.ChatKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * [FeedbackLog] on the encrypted [FeedbackDatabase]. Recording also purges entries older than
 * [retentionDays] (from settings), at most once a day per process: no WorkManager needed. The
 * service purges on connect too ([purgeExpired]).
 *
 * The DB opens on first use, on [ioDispatcher]: opening asks the Keystore for the passphrase, which
 * must not run on the main thread, and may fail for a moment (`DatabaseKeyUnavailableException`).
 * A failed open is retried on the next call instead of breaking the binding for the process.
 */
class RoomFeedbackLog(
    private val openDatabase: () -> FeedbackDatabase,
    private val clock: Clock,
    private val retentionDays: Flow<Int>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FeedbackLog {
    /** For an already open DB (tests). */
    constructor(db: FeedbackDatabase, clock: Clock, retentionDays: Flow<Int>) :
        this({ db }, clock, retentionDays, Dispatchers.Unconfined)

    private val openLock = Mutex()

    @Volatile private var database: FeedbackDatabase? = null

    @Volatile private var lastPurgeAt: Instant? = null

    private suspend fun dao(): FeedbackDao =
        (
            database ?: openLock.withLock {
                database ?: withContext(ioDispatcher) { openDatabase() }.also { database = it }
            }
        ).feedbackDao()

    override suspend fun record(
        chatKey: ChatKey,
        model: String,
        prompt: String,
        suggestions: List<Suggestion>,
    ): Long {
        purgeIfDue()
        return dao().insert(
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
    ) = dao().markInserted(entryId, index)

    override suspend fun attachFinalMessage(
        chatKey: ChatKey,
        text: String,
    ): Boolean =
        dao().attachFinalMessage(chatKey.encoded, text, clock.instant().minus(FINAL_MESSAGE_WINDOW).toEpochMilli())

    override fun observeRecent(limit: Int): Flow<List<FeedbackEntry>> =
        flow { emitAll(dao().observeRecent(limit)) }.map { rows ->
            rows.map {
                it.toEntry()
            }
        }

    override suspend fun purgeOlderThan(cutoff: Instant): Int = dao().deleteOlderThan(cutoff.toEpochMilli())

    override suspend fun clear(): Int = dao().deleteAll()

    /** Runs the retention purge unless it already ran in the last 24 hours. */
    suspend fun purgeIfDue(): Int {
        val last = lastPurgeAt
        if (last != null && Duration.between(last, clock.instant()) < Duration.ofDays(1)) return 0
        return purgeExpired()
    }

    /** Runs the retention purge now (service connect). */
    override suspend fun purgeExpired(): Int {
        val now = clock.instant()
        val removed = purgeOlderThan(now.minus(Duration.ofDays(retentionDays.first().toLong())))
        lastPurgeAt = now // Only after success, so a failed purge is retried on the next record.
        return removed
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
