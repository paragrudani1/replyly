package com.paragrudani.replyly.history

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.history.db.HistoryDatabase
import com.paragrudani.replyly.history.db.HistoryDatabaseHandle
import com.paragrudani.replyly.history.db.HistoryMigrations
import com.paragrudani.replyly.parser.ChatKey
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Room migrations of the history DB, against the exported schemas in `app/schemas/`.
 *
 * [MigrationTestHelper] reads the schemas from the test context's assets, and Robolectric only
 * serves the app's own merged assets (AGP does not merge unit-test assets). Rather than ship the
 * schemas in the APK, [SchemaAssets] hands the helper a context whose assets hold them.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryMigrationTest {
    private val india = ZoneId.of("Asia/Kolkata")
    private val name = "history-migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(SchemaAssets.instrumentation(), HistoryDatabase::class.java)

    private fun insertV1(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
        text: String,
        firstSeenAt: Instant,
    ) = db.execSQL(
        "INSERT INTO messages (id, chat_key, package_name, position, gap_before, is_me, sender, text, time_shown, " +
            "hash, first_seen_at) VALUES (?, 'com.whatsapp:Asha', 'com.whatsapp', ?, 0, 0, 'Asha', ?, '6:40 pm', " +
            "'h', ?)",
        arrayOf<Any>(id, id * 65536, text, firstSeenAt.toEpochMilli()),
    )

    @Test
    fun `1 to 2 gives every row the local date it was first seen on, marked approx`() {
        helper.createDatabase(name, 1).use { db ->
            insertV1(db, 1, "morning", Instant.parse("2026-11-21T03:00:00Z"))
            // 20:00 UTC is already the next day in India.
            insertV1(db, 2, "late night", Instant.parse("2026-11-21T20:00:00Z"))
        }

        val db = helper.runMigrationsAndValidate(name, 2, true, HistoryMigrations.from1To2(india))

        db.query("SELECT id, message_date, date_is_approx, replies_to_me FROM messages ORDER BY id").use { c ->
            val rows =
                buildList { while (c.moveToNext()) add(listOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3))) }
            assertThat(rows)
                .containsExactly(
                    listOf(1L, LocalDate.of(2026, 11, 21).toEpochDay(), 1L, 0L),
                    listOf(2L, LocalDate.of(2026, 11, 22).toEpochDay(), 1L, 0L),
                ).inOrder()
        }
        db.close()
    }

    @Test
    fun `a migrated DB opens with Room and reads back approx dates`() =
        runTest {
            helper.createDatabase(name, 1).use { db ->
                insertV1(db, 1, "hi", Instant.parse("2026-11-21T10:00:00Z"))
            }
            val context: Context = ApplicationProvider.getApplicationContext()
            val database =
                Room
                    .databaseBuilder(context, HistoryDatabase::class.java, name)
                    .addMigrations(*HistoryMigrations.all(india))
                    .allowMainThreadQueries()
                    .build()
            helper.closeWhenFinished(database)
            val history = RoomHistoryRepository(HistoryDatabaseHandle { database }, Clock.system(india))

            val stored = history.lastMessages(ChatKey("com.whatsapp", "Asha"), 10).single()

            assertThat(stored.message.text).isEqualTo("hi")
            assertThat(stored.date).isEqualTo(LocalDate.of(2026, 11, 21))
            assertThat(stored.dateIsApprox).isTrue()
            assertThat(stored.message.date).isNull()
        }
}

/** An [Instrumentation] whose context's assets are the exported Room schemas of `app/schemas/`. */
private object SchemaAssets {
    fun instrumentation(): Instrumentation {
        val real = InstrumentationRegistry.getInstrumentation()
        val context = SchemaContext(real.targetContext, assetManager())
        return object : Instrumentation() {
            override fun getContext(): Context = context

            override fun getTargetContext(): Context = context
        }
    }

    private class SchemaContext(
        base: Context,
        private val schemaAssets: AssetManager,
    ) : ContextWrapper(base) {
        override fun getAssets(): AssetManager = schemaAssets
    }

    /** The schemas packed as `assets/...` in a zip, loaded the way the framework loads an APK. */
    private fun assetManager(): AssetManager {
        // Unit tests run in the module directory.
        val schemas = File("schemas").takeIf { it.isDirectory } ?: File("app/schemas")
        val zip = File.createTempFile("room-schemas", ".apk").apply { deleteOnExit() }
        ZipOutputStream(zip.outputStream()).use { out ->
            schemas.walkTopDown().filter { it.isFile }.forEach { file ->
                out.putNextEntry(ZipEntry("assets/" + file.relativeTo(schemas).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        val apkAssets =
            Class
                .forName(
                    "android.content.res.ApkAssets",
                ).getMethod("loadFromPath", String::class.java)
                .invoke(null, zip.path)
        val builderClass = Class.forName("android.content.res.AssetManager\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        builderClass.getMethod("addApkAssets", apkAssets.javaClass).invoke(builder, apkAssets)
        return builderClass.getMethod("build").invoke(builder) as AssetManager
    }
}
