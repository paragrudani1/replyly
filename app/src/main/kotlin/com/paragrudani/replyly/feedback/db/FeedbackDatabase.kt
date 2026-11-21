package com.paragrudani.replyly.feedback.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.paragrudani.replyly.security.DatabaseKeyProvider
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** One feedback log row. [suggestionsJson] is a JSON array of `{intent, text}`. */
@Entity(
    tableName = "feedback_entries",
    indices = [Index("chat_key"), Index("created_at")],
)
data class FeedbackEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "chat_key") val chatKey: String,
    /** Epoch millis, UTC. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val model: String,
    val prompt: String,
    @ColumnInfo(name = "suggestions_json") val suggestionsJson: String,
    @ColumnInfo(name = "inserted_index") val insertedIndex: Int? = null,
    @ColumnInfo(name = "final_sent_text") val finalSentText: String? = null,
)

@Dao
interface FeedbackDao {
    @Insert
    suspend fun insert(entry: FeedbackEntryEntity): Long

    @Query("UPDATE feedback_entries SET inserted_index = :index WHERE id = :id")
    suspend fun markInserted(
        id: Long,
        index: Int,
    )

    @Query(
        "SELECT id FROM feedback_entries WHERE chat_key = :chatKey AND inserted_index IS NOT NULL " +
            "AND final_sent_text IS NULL AND created_at > :createdAfterMillis ORDER BY id DESC LIMIT 1",
    )
    suspend fun newestOpenInsert(
        chatKey: String,
        createdAfterMillis: Long,
    ): Long?

    @Query("UPDATE feedback_entries SET final_sent_text = :text WHERE id = :id")
    suspend fun setFinalText(
        id: Long,
        text: String,
    )

    @Transaction
    suspend fun attachFinalMessage(
        chatKey: String,
        text: String,
        createdAfterMillis: Long,
    ): Boolean {
        val id = newestOpenInsert(chatKey, createdAfterMillis) ?: return false
        setFinalText(id, text)
        return true
    }

    @Query("SELECT * FROM feedback_entries ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FeedbackEntryEntity>>

    @Query("DELETE FROM feedback_entries WHERE created_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("DELETE FROM feedback_entries")
    suspend fun deleteAll(): Int
}

/**
 * The feedback log's own encrypted DB file (`feedback.db`), separate from #3's history DB so the
 * two Room schemas never touch. Opened with SQLCipher and the shared [DatabaseKeyProvider].
 */
@Database(entities = [FeedbackEntryEntity::class], version = 1, exportSchema = true)
abstract class FeedbackDatabase : RoomDatabase() {
    abstract fun feedbackDao(): FeedbackDao

    companion object {
        const val NAME = "feedback.db"

        /**
         * @param openHelperFactory builds the SQLite factory from the passphrase. The default is
         *   SQLCipher; tests pass the framework factory (SQLCipher's native lib is Android-only).
         */
        fun open(
            context: Context,
            keyProvider: DatabaseKeyProvider,
            name: String = NAME,
            openHelperFactory: (ByteArray) -> SupportSQLiteOpenHelper.Factory = ::sqlCipherFactory,
        ): FeedbackDatabase =
            Room
                .databaseBuilder(context.applicationContext, FeedbackDatabase::class.java, name)
                .openHelperFactory(openHelperFactory(keyProvider.getOrCreatePassphrase()))
                .build()

        private fun sqlCipherFactory(passphrase: ByteArray): SupportSQLiteOpenHelper.Factory {
            System.loadLibrary("sqlcipher")
            return SupportOpenHelperFactory(passphrase)
        }
    }
}
