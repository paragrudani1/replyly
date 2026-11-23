package com.paragrudani.replyly.feedback

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.feedback.db.FeedbackDatabase
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.parser.x.XMode
import com.paragrudani.replyly.security.DatabaseKeyProvider
import com.paragrudani.replyly.testing.MutableClock
import com.paragrudani.replyly.testing.SchemaAssets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

/**
 * feedback.db 1 -> 2 (issue #26): old rows become prompt v1, with no analysis, panel id or cache
 * count. 2 -> 3 (issue #30): old rows have no X mode.
 */
@RunWith(RobolectricTestRunner::class)
class FeedbackDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper =
        MigrationTestHelper(
            SchemaAssets.instrumentation(),
            FeedbackDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @After fun tearDown() {
        context.deleteDatabase(NAME)
    }

    @Test
    fun `migrating 1 to 2 keeps the rows, marks them prompt v1 and matches the v2 schema`() {
        helper.createDatabase(NAME, 1).apply {
            execSQL(
                "INSERT INTO feedback_entries (id, chat_key, created_at, model, prompt, suggestions_json, " +
                    "inserted_index, final_sent_text) VALUES (5, 'com.whatsapp:Riya', 1000, 'm', 'old prompt', " +
                    "'[{\"intent\":\"agree\",\"text\":\"Haan\"}]', 0, 'Haan pakka')",
            )
            close()
        }

        // Validates every table, column, default and index against the exported 2.json.
        val db = helper.runMigrationsAndValidate(NAME, 2, true, FeedbackDatabase.MIGRATION_1_2)

        db
            .query(
                "SELECT prompt_version, analysis, panel_id, prompt, final_sent_text, cache_read_tokens FROM feedback_entries",
            ).use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getInt(0)).isEqualTo(1)
                assertThat(it.isNull(1)).isTrue()
                assertThat(it.isNull(2)).isTrue()
                assertThat(it.getString(3)).isEqualTo("old prompt")
                assertThat(it.getString(4)).isEqualTo("Haan pakka")
                assertThat(it.isNull(5)).isTrue()
            }
        db.close()
    }

    @Test
    fun `migrating 2 to 3 keeps the rows without an X mode and matches the v3 schema`() {
        helper.createDatabase(NAME, 2).apply {
            execSQL(
                "INSERT INTO feedback_entries (id, chat_key, created_at, model, prompt, suggestions_json, " +
                    "prompt_version, analysis) VALUES (7, 'com.whatsapp:Riya', 1000, 'm', 'p', '[]', 2, 'a')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(NAME, 3, true, FeedbackDatabase.MIGRATION_2_3)

        db.query("SELECT x_mode, prompt_version, analysis FROM feedback_entries WHERE id = 7").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.isNull(0)).isTrue()
            assertThat(it.getInt(1)).isEqualTo(2)
            assertThat(it.getString(2)).isEqualTo("a")
        }
        db.close()
    }

    @Test
    fun `the app opens a version 1 file through the migration and reads old and new entries`() =
        runBlocking<Unit> {
            helper.createDatabase(NAME, 1).apply {
                execSQL(
                    "INSERT INTO feedback_entries (chat_key, created_at, model, prompt, suggestions_json) " +
                        "VALUES ('com.whatsapp:Riya', 1795000000000, 'm', 'old', '[]')", // 18 Nov 2026
                )
                close()
            }
            val keys =
                object : DatabaseKeyProvider {
                    override fun getOrCreatePassphrase(): ByteArray = ByteArray(32)
                }
            val db = FeedbackDatabase.open(context, keys, NAME) { FrameworkSQLiteOpenHelperFactory() }
            val log = RoomFeedbackLog(db, MutableClock.atLocal(LocalDateTime.of(2026, 11, 22, 10, 0)), flowOf(60))

            log.record(
                ChatKey("com.whatsapp", "Riya"),
                "m",
                "new",
                emptyList(),
                analysis = "a",
                promptVersion = 2,
                cacheReadTokens = 1_024,
            )
            log.record(ChatKey("com.twitter.android", "Meera Iyer"), "m", "x", emptyList(), xMode = XMode.QUOTE)

            val all = log.observeRecent(10).first()
            assertThat(all.first().xMode).isEqualTo(XMode.QUOTE)
            val entries = all.drop(1)
            assertThat(entries.map { it.promptVersion }).containsExactly(2, 1).inOrder()
            assertThat(entries.map { it.analysis }).containsExactly("a", null).inOrder()
            assertThat(entries.map { it.cacheReadTokens }).containsExactly(1_024, null).inOrder()
            db.close()
        }

    private companion object {
        const val NAME = "feedback-migration-test.db"
    }
}
