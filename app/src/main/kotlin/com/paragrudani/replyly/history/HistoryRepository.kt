package com.paragrudani.replyly.history

import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParsedChat
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** A message kept in local history. */
data class StoredMessage(
    val chatKey: ChatKey,
    val message: ChatMessage,
    /** When Replyly first saw this message on screen (not the time shown in the bubble). */
    val firstSeenAt: Instant,
)

/**
 * Encrypted per-chat message history (ticket #3: Room on SQLCipher).
 * Lists are always oldest first.
 */
interface HistoryRepository {
    /**
     * Merges one screen read into the stored history (hash + overlap merge, spec 5).
     * Does nothing and returns 0 when [ParsedChat.historyApplies] is false.
     * @return number of new messages stored.
     */
    suspend fun merge(chat: ParsedChat): Int

    /** The last [limit] messages of a chat. */
    suspend fun lastMessages(
        chatKey: ChatKey,
        limit: Int,
    ): List<StoredMessage>

    /** The user's own most recent messages in other chats of the same app: the style examples. */
    suspend fun recentOwnMessagesInOtherChats(
        packageName: String,
        excluding: ChatKey,
        limit: Int,
    ): List<StoredMessage>

    /** Deletes the messages of one chat. Its contact note stays. */
    suspend fun deleteChat(chatKey: ChatKey)

    /** Deletes messages first seen before [cutoff]. @return number deleted. */
    suspend fun purgeOlderThan(cutoff: Instant): Int
}

/** Free-text note about a contact, stored in the encrypted DB (ticket #3). */
interface ContactNoteStore {
    suspend fun get(chatKey: ChatKey): String?

    /** A blank [note] deletes it. */
    suspend fun set(
        chatKey: ChatKey,
        note: String,
    )

    fun observe(chatKey: ChatKey): Flow<String?>
}
