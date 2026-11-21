package com.paragrudani.replyly.history

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.history.db.HistoryDatabase
import com.paragrudani.replyly.history.db.HistoryDatabaseHandle
import com.paragrudani.replyly.history.db.HistoryDatabases
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParsedChat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class RoomHistoryRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
    private val handle = HistoryDatabaseHandle { database }
    private val clock = MutableClock()
    private val history = RoomHistoryRepository(handle, clock)
    private val notes = RoomContactNoteStore(handle, clock)

    @After
    fun close() = database.close()

    private fun them(
        text: String,
        time: String = "6:40 pm",
    ) = ChatMessage(sender = "Asha", text = text, timeShown = time, isMe = false)

    private fun me(
        text: String,
        time: String = "6:41 pm",
    ) = ChatMessage(sender = null, text = text, timeShown = time, isMe = true)

    private fun chat(
        vararg messages: ChatMessage,
        title: String = "Asha",
        packageName: String = "com.whatsapp",
        historyApplies: Boolean = true,
    ) = ParsedChat(packageName, title, isGroup = false, messages = messages.toList(), historyApplies = historyApplies)

    private suspend fun texts(
        key: ChatKey,
        limit: Int = 100,
    ) = history.lastMessages(key, limit).map { it.message.text }

    /** Every row of a chat, whatever its current position. */
    private suspend fun allTexts(key: ChatKey) = database.messages().allInChat(key.encoded).map { it.text }

    @Test
    fun `merge stores a chat and reads it back oldest first`() =
        runTest {
            val read = chat(them("hi"), me("hello"), them("free at 6?"))

            assertThat(history.merge(read)).isEqualTo(3)

            val stored = history.lastMessages(read.chatKey, 10)
            assertThat(stored.map { it.message }).containsExactlyElementsIn(read.messages).inOrder()
            assertThat(stored.map { it.chatKey }.toSet()).containsExactly(read.chatKey)
            assertThat(stored.map { it.firstSeenAt }.toSet()).containsExactly(clock.now)
        }

    @Test
    fun `lastMessages returns only the newest ones`() =
        runTest {
            history.merge(chat(them("a"), them("b"), them("c"), them("d")))

            assertThat(texts(ChatKey("com.whatsapp", "Asha"), limit = 2)).containsExactly("c", "d").inOrder()
        }

    @Test
    fun `apps without a full parser get no history`() =
        runTest {
            val read = chat(them("hi"), packageName = "com.slack", historyApplies = false)

            assertThat(history.merge(read)).isEqualTo(0)
            assertThat(history.lastMessages(read.chatKey, 10)).isEmpty()
        }

    @Test
    fun `re-reads, repeats, scrolling up and edits survive the round trip through the DB`() =
        runTest {
            val key = ChatKey("com.whatsapp", "Asha")
            val c = them("coming at 7?")
            val e = them("see you then", "6:46 pm")
            history.merge(chat(c, me("d", "6:45 pm"), e))

            assertThat(history.merge(chat(c, me("d", "6:45 pm"), e))).isEqualTo(0)
            assertThat(history.merge(chat(c, me("d", "6:45 pm"), me("d", "6:45 pm"), e))).isEqualTo(1)
            assertThat(history.merge(chat(them("a"), them("b"), c, me("d", "6:45 pm")))).isEqualTo(2)
            history.merge(chat(c, me("d", "6:45 pm"), me("d", "6:45 pm"), me("d!", "6:46 pm"), e))
            history.merge(chat(c, me("d", "6:45 pm"), me("d", "6:45 pm"), me("d!!", "6:46 pm"), e))

            assertThat(allTexts(key))
                .containsExactly("a", "b", "coming at 7?", "d", "d", "d!!", "see you then")
                .inOrder()
        }

    @Test
    fun `an edit keeps when the message was first seen`() =
        runTest {
            history.merge(chat(them("are we on?"), me("typo", "6:45 pm"), them("b", "6:46 pm")))
            val firstSeen = clock.now
            clock.advance(Duration.ofMinutes(5))

            history.merge(chat(them("are we on?"), me("fixed", "6:45 pm"), them("b", "6:46 pm")))

            val edited = history.lastMessages(ChatKey("com.whatsapp", "Asha"), 2).first()
            assertThat(edited.message.text).isEqualTo("fixed")
            assertThat(edited.firstSeenAt).isEqualTo(firstSeen)
        }

    @Test
    fun `mergeRead reports the messages that arrived below the saved tail`() =
        runTest {
            history.merge(chat(them("a"), me("see you at 6")))

            // Only one saved message is still on screen: enough for a distinctive text.
            val outcome = history.mergeRead(chat(me("see you at 6"), them("c"), me("sent now", "6:50 pm")))

            assertThat(outcome.inserted).isEqualTo(2)
            assertThat(outcome.newAtEnd).containsExactly(them("c"), me("sent now", "6:50 pm")).inOrder()
        }

    @Test
    fun `a long chat merges against its newest rows, and against all of them when needed`() =
        runTest {
            val smallTail = RoomHistoryRepository(handle, clock, tailSize = 3)
            val key = ChatKey("com.whatsapp", "Asha")
            val m = (1..10).map { them("m$it") }
            smallTail.merge(chat(*m.toTypedArray()))

            // At the tail: the newest 3 rows are enough.
            assertThat(smallTail.merge(chat(m[8], m[9], me("new")))).isEqualTo(1)
            // No overlap with the tail, but saved further up: nothing is duplicated.
            assertThat(smallTail.merge(chat(m[2], m[3], m[4]))).isEqualTo(0)
            // Reaches above the tail.
            assertThat(smallTail.merge(chat(m[6], m[7], m[8], m[9]))).isEqualTo(0)
            // Inserted between rows above the tail.
            assertThat(smallTail.merge(chat(m[3], m[4], me("late", "6:50 pm"), m[5], m[6]))).isEqualTo(1)

            assertThat(allTexts(key))
                .containsExactlyElementsIn(
                    listOf("m1", "m2", "m3", "m4", "m5", "late", "m6", "m7", "m8", "m9", "m10", "new"),
                ).inOrder()
        }

    @Test
    fun `chats are kept apart`() =
        runTest {
            history.merge(chat(them("for Asha"), title = "Asha"))
            history.merge(chat(them("for Ravi"), title = "Ravi"))

            assertThat(texts(ChatKey("com.whatsapp", "Asha"))).containsExactly("for Asha")
            assertThat(texts(ChatKey("com.whatsapp", "Ravi"))).containsExactly("for Ravi")
        }

    @Test
    fun `style examples are my newest messages in other chats of the same app, oldest first`() =
        runTest {
            history.merge(chat(me("mine to Ravi 1"), them("x"), title = "Ravi"))
            clock.advance(Duration.ofMinutes(1))
            history.merge(chat(me("mine to Meera"), title = "Meera"))
            clock.advance(Duration.ofMinutes(1))
            history.merge(chat(me("mine to Ravi 1"), them("x"), me("mine to Ravi 2", "7:00 pm"), title = "Ravi"))
            history.merge(chat(me("mine to Asha"), title = "Asha"))
            history.merge(chat(me("on Telegram"), title = "Ravi", packageName = "org.telegram.messenger"))

            val examples =
                history.recentOwnMessagesInOtherChats("com.whatsapp", excluding = ChatKey("com.whatsapp", "Asha"), 2)

            assertThat(examples.map { it.message.text }).containsExactly("mine to Meera", "mine to Ravi 2").inOrder()
        }

    @Test
    fun `lastMessages ends at the current position, not at an old block stored last`() =
        runTest {
            val key = ChatKey("com.whatsapp", "Asha")
            history.merge(chat(them("a1", "6:00 pm"), me("a2", "6:01 pm"), them("a3", "6:02 pm")))
            // A jump far up with no overlap: the old block is stored after the newest rows.
            history.merge(chat(them("o1", "1:00 pm"), me("o2", "1:01 pm")))
            assertThat(texts(key, limit = 2)).containsExactly("o1", "o2").inOrder()

            // Back at the bottom (the assembler merges the screen right before reading).
            history.merge(chat(me("a2", "6:01 pm"), them("a3", "6:02 pm"), me("a4", "6:05 pm")))

            assertThat(texts(key, limit = 3)).containsExactly("a2", "a3", "a4").inOrder()
            val sent = history.mergeRead(chat(them("a3", "6:02 pm"), me("a4", "6:05 pm"), me("a5", "6:06 pm")))
            assertThat(sent.newAtEnd).containsExactly(me("a5", "6:06 pm"))
            assertThat(texts(key, limit = 1)).containsExactly("a5")
        }

    @Test
    fun `a re-read inside the history moves the current position`() =
        runTest {
            val key = ChatKey("com.whatsapp", "Asha")
            history.merge(chat(them("a1", "6:00 pm"), me("a2", "6:01 pm"), them("a3", "6:02 pm"), me("a4", "6:03 pm")))

            assertThat(history.merge(chat(them("a1", "6:00 pm"), me("a2", "6:01 pm")))).isEqualTo(0)

            assertThat(texts(key)).containsExactly("a1", "a2").inOrder()
        }

    @Test
    fun `style examples stop at the current position of each chat`() =
        runTest {
            history.merge(chat(me("recent to Ravi", "6:00 pm"), them("x", "6:01 pm"), title = "Ravi"))
            clock.advance(Duration.ofMinutes(1))
            history.merge(chat(me("old to Ravi", "1:00 pm"), them("y", "1:01 pm"), title = "Ravi"))
            clock.advance(Duration.ofMinutes(1))
            history.merge(chat(me("recent to Ravi", "6:00 pm"), them("x", "6:01 pm"), title = "Ravi"))

            val examples =
                history.recentOwnMessagesInOtherChats("com.whatsapp", excluding = ChatKey("com.whatsapp", "Asha"), 5)

            assertThat(examples.map { it.message.text }).containsExactly("recent to Ravi")
        }

    @Test
    fun `a purged current row falls back to the last row of the chat`() =
        runTest {
            val key = ChatKey("com.whatsapp", "Asha")
            history.merge(chat(them("new 1", "6:00 pm"), me("new 2", "6:01 pm")))
            clock.advance(Duration.ofDays(61))
            history.merge(chat(them("fresh 1", "1:00 pm"), me("fresh 2", "1:01 pm")))
            history.merge(chat(them("new 1", "6:00 pm"), me("new 2", "6:01 pm")))

            history.purgeOlderThan(clock.now - Duration.ofDays(60))

            assertThat(texts(key)).containsExactly("fresh 1", "fresh 2").inOrder()
        }

    @Test
    fun `deleting a chat removes its messages but keeps its note and other chats`() =
        runTest {
            val asha = ChatKey("com.whatsapp", "Asha")
            history.merge(chat(them("a"), title = "Asha"))
            history.merge(chat(them("r"), title = "Ravi"))
            notes.set(asha, "Sister")

            history.deleteChat(asha)

            assertThat(texts(asha)).isEmpty()
            assertThat(database.messages().currentRowId(asha.encoded)).isNull()
            assertThat(texts(ChatKey("com.whatsapp", "Ravi"))).containsExactly("r")
            assertThat(notes.get(asha)).isEqualTo("Sister")
        }

    @Test
    fun `purge deletes messages first seen before the cutoff`() =
        runTest {
            val key = ChatKey("com.whatsapp", "Asha")
            history.merge(chat(them("old message")))
            clock.advance(Duration.ofDays(61))
            history.merge(chat(them("old message"), them("new")))

            val purged = history.purgeOlderThan(clock.now - Duration.ofDays(60))

            assertThat(purged).isEqualTo(1)
            assertThat(texts(key)).containsExactly("new")
        }

    @Test
    fun `a purge that leaves a hole mid-chat marks a gap there`() =
        runTest {
            val key = ChatKey("com.whatsapp", "Asha")
            history.merge(chat(them("m3", "1:03 pm"), them("m4", "1:04 pm")))
            clock.advance(Duration.ofDays(61))
            // Scrolled up, then back down: m1, m2, m5 and m6 are first seen now.
            history.merge(
                chat(them("m1", "1:01 pm"), them("m2", "1:02 pm"), them("m3", "1:03 pm"), them("m4", "1:04 pm")),
            )
            history.merge(
                chat(them("m3", "1:03 pm"), them("m4", "1:04 pm"), them("m5", "1:05 pm"), them("m6", "1:06 pm")),
            )

            history.purgeOlderThan(clock.now - Duration.ofDays(60))

            val rows = database.messages().allInChat(key.encoded)
            assertThat(rows.map { it.text }).containsExactly("m1", "m2", "m5", "m6").inOrder()
            assertThat(rows.map { it.gapBefore }).containsExactly(false, false, true, false).inOrder()
        }

    @Test
    fun `gap markers are stored and move up with older messages`() =
        runTest {
            val key = ChatKey("com.whatsapp", "Asha")
            history.merge(chat(them("a", "1:00 pm"), them("b", "1:01 pm")))
            history.merge(chat(them("x", "3:01 pm"), them("y", "3:02 pm")))
            assertThat(database.messages().allInChat(key.encoded).map { it.gapBefore })
                .containsExactly(false, false, true, false)
                .inOrder()

            history.merge(chat(them("w", "3:00 pm"), them("x", "3:01 pm"), them("y", "3:02 pm")))

            val rows = database.messages().allInChat(key.encoded)
            assertThat(rows.map { it.text }).containsExactly("a", "b", "w", "x", "y").inOrder()
            assertThat(rows.map { it.gapBefore }).containsExactly(false, false, true, false, false).inOrder()
        }

    @Test
    fun `contact notes are saved, replaced, cleared by a blank note, and observed`() =
        runTest {
            val asha = ChatKey("com.whatsapp", "Asha")

            notes.observe(asha).test {
                assertThat(awaitItem()).isNull()
                notes.set(asha, "Sister, lives in Pune")
                assertThat(awaitItem()).isEqualTo("Sister, lives in Pune")
                notes.set(asha, "Sister")
                assertThat(awaitItem()).isEqualTo("Sister")
                notes.set(asha, "  ")
                assertThat(awaitItem()).isNull()
            }
            assertThat(notes.get(asha)).isNull()
            assertThat(notes.get(ChatKey("com.whatsapp", "Ravi"))).isNull()
        }

    @Test
    fun `an unreadable database file is discarded and opened fresh`() {
        var opens = 0
        var discarded = 0
        val fresh = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()

        val opened =
            HistoryDatabases.openOrWipe(
                open = {
                    opens++
                    if (opens ==
                        1
                    ) {
                        UnreadableDatabase.create(context, SQLiteDatabaseCorruptException("not a db"))
                    } else {
                        fresh
                    }
                },
                discard = { discarded++ },
            )

        assertThat(opened).isSameInstanceAs(fresh)
        assertThat(discarded).isEqualTo(1)
        fresh.close()
    }

    @Test
    fun `a full disk or other SQLite error fails without wiping the database`() {
        var discarded = 0

        assertThrows(SQLiteFullException::class.java) {
            HistoryDatabases.openOrWipe(
                open = { UnreadableDatabase.create(context, SQLiteFullException("disk full")) },
                discard = { discarded++ },
            )
        }
        assertThat(discarded).isEqualTo(0)
    }
}

/**
 * A DB whose open helper throws [error] on open. SQLCipher reports a wrong passphrase or a file
 * that is not a database (SQLITE_NOTADB) as [SQLiteDatabaseCorruptException].
 */
private object UnreadableDatabase {
    fun create(
        context: Context,
        error: SQLiteException,
    ): HistoryDatabase =
        Room
            .inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
            .openHelperFactory { configuration ->
                object : androidx.sqlite.db.SupportSQLiteOpenHelper {
                    override val databaseName: String? = configuration.name

                    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {}

                    override val writableDatabase: androidx.sqlite.db.SupportSQLiteDatabase
                        get() = throw error

                    override val readableDatabase: androidx.sqlite.db.SupportSQLiteDatabase
                        get() = writableDatabase

                    override fun close() {}
                }
            }.build()
}
