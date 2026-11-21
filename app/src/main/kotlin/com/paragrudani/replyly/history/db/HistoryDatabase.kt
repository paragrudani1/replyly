package com.paragrudani.replyly.history.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One saved message. Rows of a chat are ordered by [position], not by id or time. */
@Entity(
    tableName = "messages",
    indices = [
        Index("chat_key", "position"),
        Index("package_name", "is_me", "first_seen_at"),
        Index("first_seen_at"),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [com.paragrudani.replyly.parser.ChatKey.encoded]. */
    @ColumnInfo(name = "chat_key") val chatKey: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    val position: Long,
    /** [com.paragrudani.replyly.history.StoredRow.gapBefore]: messages may be missing right above this row. */
    @ColumnInfo(name = "gap_before", defaultValue = "0") val gapBefore: Boolean = false,
    @ColumnInfo(name = "is_me") val isMe: Boolean,
    val sender: String?,
    val text: String,
    @ColumnInfo(name = "time_shown") val timeShown: String?,
    /** [com.paragrudani.replyly.history.MessageHash] of the current content. */
    val hash: String,
    /** Epoch millis when Replyly first saw the message. Retention purges by this. */
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
)

/**
 * A chat's current position: the row of the last read's last message, where the latest merge
 * placed or lined up its read ([com.paragrudani.replyly.history.MergePlan.currentRowId]).
 */
@Entity(tableName = "chat_positions")
data class ChatPositionEntity(
    @PrimaryKey @ColumnInfo(name = "chat_key") val chatKey: String,
    /** A [MessageEntity.id]. When that row is purged, readers fall back to the chat's last row. */
    @ColumnInfo(name = "message_id") val messageId: Long,
)

@Entity(tableName = "contact_notes")
data class ContactNoteEntity(
    @PrimaryKey @ColumnInfo(name = "chat_key") val chatKey: String,
    val note: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Above any real position: "no current position" compares as the chat's end. */
private const val MAX_POSITION = Long.MAX_VALUE

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chat_key = :chatKey ORDER BY position")
    suspend fun allInChat(chatKey: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chat_key = :chatKey ORDER BY position DESC LIMIT :limit")
    suspend fun newestInChat(
        chatKey: String,
        limit: Int,
    ): List<MessageEntity>

    /** The [limit] rows ending at the chat's current position (its last row if none), newest first. */
    @Query(
        """
        SELECT * FROM messages
        WHERE chat_key = :chatKey AND position <= COALESCE((
            SELECT here.position FROM chat_positions AS p
            JOIN messages AS here ON here.id = p.message_id
            WHERE p.chat_key = :chatKey
        ), $MAX_POSITION)
        ORDER BY position DESC LIMIT :limit
        """,
    )
    suspend fun endingAtCurrent(
        chatKey: String,
        limit: Int,
    ): List<MessageEntity>

    /** My messages at or above the current position of their chat, newest first seen first. */
    @Query(
        """
        SELECT * FROM messages
        WHERE package_name = :packageName AND chat_key != :excludedChatKey AND is_me = 1
        AND position <= COALESCE((
            SELECT here.position FROM chat_positions AS p
            JOIN messages AS here ON here.id = p.message_id
            WHERE p.chat_key = messages.chat_key
        ), $MAX_POSITION)
        ORDER BY first_seen_at DESC, position DESC
        LIMIT :limit
        """,
    )
    suspend fun newestOwnInOtherChats(
        packageName: String,
        excludedChatKey: String,
        limit: Int,
    ): List<MessageEntity>

    /** @return the new row ids, in order. */
    @Insert
    suspend fun insert(rows: List<MessageEntity>): List<Long>

    @Query("SELECT message_id FROM chat_positions WHERE chat_key = :chatKey")
    suspend fun currentRowId(chatKey: String): Long?

    @Upsert
    suspend fun setCurrent(position: ChatPositionEntity)

    @Query("UPDATE messages SET position = :position WHERE id = :id")
    suspend fun move(
        id: Long,
        position: Long,
    )

    @Query("UPDATE messages SET sender = :sender, text = :text, time_shown = :timeShown, hash = :hash WHERE id = :id")
    suspend fun edit(
        id: Long,
        sender: String?,
        text: String,
        timeShown: String?,
        hash: String,
    )

    @Query("UPDATE messages SET gap_before = 0 WHERE id = :id")
    suspend fun clearGap(id: Long)

    /**
     * Before a purge: marks a gap on every row that stays while the row right above it in its chat
     * goes, so the merge never lines up a read across the hole.
     */
    @Query(
        """
        UPDATE messages SET gap_before = 1
        WHERE gap_before = 0 AND first_seen_at >= :cutoffMillis AND (
            SELECT above.first_seen_at FROM messages AS above
            WHERE above.chat_key = messages.chat_key AND above.position < messages.position
            ORDER BY above.position DESC LIMIT 1
        ) < :cutoffMillis
        """,
    )
    suspend fun markGapsBeforePurge(cutoffMillis: Long): Int

    @Query("DELETE FROM messages WHERE chat_key = :chatKey")
    suspend fun deleteChat(chatKey: String): Int

    @Query("DELETE FROM chat_positions WHERE chat_key = :chatKey")
    suspend fun deleteCurrent(chatKey: String)

    @Query("DELETE FROM messages WHERE first_seen_at < :cutoffMillis")
    suspend fun deleteFirstSeenBefore(cutoffMillis: Long): Int
}

@Dao
interface ContactNoteDao {
    @Query("SELECT note FROM contact_notes WHERE chat_key = :chatKey")
    suspend fun get(chatKey: String): String?

    @Query("SELECT note FROM contact_notes WHERE chat_key = :chatKey")
    fun observe(chatKey: String): Flow<String?>

    @Upsert
    suspend fun upsert(note: ContactNoteEntity)

    @Query("DELETE FROM contact_notes WHERE chat_key = :chatKey")
    suspend fun delete(chatKey: String)
}

/**
 * The encrypted `history` DB (ticket #3): messages and contact notes. Opened with SQLCipher by
 * [HistoryDatabases]. Schema JSON is exported to `app/schemas/`; a version bump needs a migration.
 */
@Database(
    entities = [MessageEntity::class, ChatPositionEntity::class, ContactNoteEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao

    abstract fun contactNotes(): ContactNoteDao
}
