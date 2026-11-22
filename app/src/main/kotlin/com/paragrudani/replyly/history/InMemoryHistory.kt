package com.paragrudani.replyly.history

import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.parser.ParsedChat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** In-memory fake for tests (AppGraph binds the real one). Not persisted, not encrypted, and it appends without any dedup. */
class InMemoryHistoryRepository(
    private val clock: Clock,
) : HistoryRepository {
    private val messages = mutableListOf<StoredMessage>()

    override suspend fun merge(chat: ParsedChat): Int {
        if (!chat.historyApplies) return 0
        val now = clock.instant()
        val today = LocalDate.ofInstant(now, clock.zone)
        synchronized(messages) {
            chat.messages.forEach {
                messages +=
                    StoredMessage(
                        chat.chatKey,
                        it,
                        now,
                        it.date ?: today,
                        it.date == null,
                    )
            }
        }
        return chat.messages.size
    }

    override suspend fun lastMessages(
        chatKey: ChatKey,
        limit: Int,
    ): List<StoredMessage> = synchronized(messages) { messages.filter { it.chatKey == chatKey }.takeLast(limit) }

    override suspend fun recentOwnMessagesInOtherChats(
        packageName: String,
        excluding: ChatKey,
        limit: Int,
    ): List<StoredMessage> =
        synchronized(messages) {
            messages
                .filter { it.chatKey.packageName == packageName && it.chatKey != excluding && it.message.isMe }
                .takeLast(limit)
        }

    override suspend fun deleteChat(chatKey: ChatKey) {
        synchronized(messages) { messages.removeAll { it.chatKey == chatKey } }
    }

    override suspend fun purgeOlderThan(cutoff: Instant): Int =
        synchronized(messages) {
            val before = messages.size
            messages.removeAll { it.firstSeenAt < cutoff }
            before - messages.size
        }
}

/** In-memory fake for tests (AppGraph binds the real one). Not persisted. */
class InMemoryContactNoteStore : ContactNoteStore {
    private val notes = MutableStateFlow<Map<ChatKey, String>>(emptyMap())

    override suspend fun get(chatKey: ChatKey): String? = notes.value[chatKey]

    override suspend fun set(
        chatKey: ChatKey,
        note: String,
    ) {
        notes.value = if (note.isBlank()) notes.value - chatKey else notes.value + (chatKey to note)
    }

    override fun observe(chatKey: ChatKey): Flow<String?> = notes.map { it[chatKey] }
}
