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
    @ColumnInfo(name = "is_me") val isMe: Boolean,
    val sender: String?,
    val text: String,
    @ColumnInfo(name = "time_shown") val timeShown: String?,
    /** [com.paragrudani.replyly.history.MessageHash] of the current content. */
    val hash: String,
    /** Epoch millis when Replyly first saw the message. Retention purges by this. */
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
)

@Entity(tableName = "contact_notes")
data class ContactNoteEntity(
    @PrimaryKey @ColumnInfo(name = "chat_key") val chatKey: String,
    val note: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chat_key = :chatKey ORDER BY position")
    suspend fun allInChat(chatKey: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chat_key = :chatKey ORDER BY position DESC LIMIT :limit")
    suspend fun newestInChat(
        chatKey: String,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE package_name = :packageName AND chat_key != :excludedChatKey AND is_me = 1
        ORDER BY first_seen_at DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun newestOwnInOtherChats(
        packageName: String,
        excludedChatKey: String,
        limit: Int,
    ): List<MessageEntity>

    @Insert
    suspend fun insert(rows: List<MessageEntity>)

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

    @Query("DELETE FROM messages WHERE chat_key = :chatKey")
    suspend fun deleteChat(chatKey: String): Int

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
@Database(entities = [MessageEntity::class, ContactNoteEntity::class], version = 1, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao

    abstract fun contactNotes(): ContactNoteDao
}
