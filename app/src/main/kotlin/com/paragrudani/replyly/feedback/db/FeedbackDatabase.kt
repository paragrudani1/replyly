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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.paragrudani.replyly.security.DatabaseKeyProvider
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * One feedback log row. [suggestionsJson] is a JSON array of `{intent, text}`. Version 2 added
 * [promptVersion] (rows from before are prompt v1), [analysis] (the model's reasoning, local only)
 * [panelId] (one id per panel open, for the insert rate per panel) and [cacheReadTokens].
 */
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
    @ColumnInfo(name = "prompt_version", defaultValue = "1") val promptVersion: Int = 1,
    val analysis: String? = null,
    @ColumnInfo(name = "panel_id") val panelId: String? = null,
    /** Input tokens read from the prompt cache (Anthropic), a count only; null when not reported. */
    @ColumnInfo(name = "cache_read_tokens") val cacheReadTokens: Int? = null,
)

/** The columns the quality stats need: no prompt or analysis (the chat key groups v1 rows into panels). */
data class FeedbackStatsColumns(
    val id: Long,
    @ColumnInfo(name = "chat_key") val chatKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "prompt_version") val promptVersion: Int,
    @ColumnInfo(name = "panel_id") val panelId: String?,
    @ColumnInfo(name = "suggestions_json") val suggestionsJson: String,
    @ColumnInfo(name = "inserted_index") val insertedIndex: Int?,
    @ColumnInfo(name = "final_sent_text") val finalSentText: String?,
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

    @Query(
        "SELECT id, chat_key, created_at, prompt_version, panel_id, suggestions_json, inserted_index, " +
            "final_sent_text " +
            "FROM feedback_entries ORDER BY id",
    )
    fun observeStatsColumns(): Flow<List<FeedbackStatsColumns>>

    @Query("DELETE FROM feedback_entries WHERE created_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("DELETE FROM feedback_entries")
    suspend fun deleteAll(): Int
}

/**
 * The feedback log's own encrypted DB file (`feedback.db`), separate from #3's history DB so the
 * two Room schemas never touch. Opened with SQLCipher and the shared [DatabaseKeyProvider].
 */
@Database(entities = [FeedbackEntryEntity::class], version = 2, exportSchema = true)
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
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Prompt v2 (issue #26): old rows are prompt v1, without analysis, panel id or cache count. */
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE feedback_entries ADD COLUMN prompt_version INTEGER NOT NULL DEFAULT 1")
                    db.execSQL("ALTER TABLE feedback_entries ADD COLUMN analysis TEXT")
                    db.execSQL("ALTER TABLE feedback_entries ADD COLUMN panel_id TEXT")
                    db.execSQL("ALTER TABLE feedback_entries ADD COLUMN cache_read_tokens INTEGER")
                }
            }

        private fun sqlCipherFactory(passphrase: ByteArray): SupportSQLiteOpenHelper.Factory {
            System.loadLibrary("sqlcipher")
            return SupportOpenHelperFactory(passphrase)
        }
    }
}
