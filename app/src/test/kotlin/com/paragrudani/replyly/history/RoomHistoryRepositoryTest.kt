package com.paragrudani.replyly.history

import android.content.Context
import android.database.sqlite.SQLiteException
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** A clock the test moves by hand. */
class MutableClock(
    var now: Instant = Instant.parse("2026-11-21T10:00:00Z"),
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneOffset = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this

    fun advance(duration: Duration) {
        now += duration
    }
}

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
            history.merge(chat(them("c"), me("d", "6:45 pm")))

            assertThat(history.merge(chat(them("c"), me("d", "6:45 pm")))).isEqualTo(0)
            assertThat(history.merge(chat(them("c"), me("d", "6:45 pm"), me("d", "6:45 pm")))).isEqualTo(1)
            assertThat(history.merge(chat(them("a"), them("b"), them("c")))).isEqualTo(2)
            history.merge(chat(them("c"), me("d", "6:45 pm"), me("d!", "6:45 pm")))

            assertThat(texts(key)).containsExactly("a", "b", "c", "d", "d!").inOrder()
        }

    @Test
    fun `an edit keeps when the message was first seen`() =
        runTest {
            history.merge(chat(them("a"), me("typo", "6:45 pm")))
            val firstSeen = clock.now
            clock.advance(Duration.ofMinutes(5))

            history.merge(chat(them("a"), me("fixed", "6:45 pm")))

            val last = history.lastMessages(ChatKey("com.whatsapp", "Asha"), 1).single()
            assertThat(last.message.text).isEqualTo("fixed")
            assertThat(last.firstSeenAt).isEqualTo(firstSeen)
        }

    @Test
    fun `mergeRead reports the messages that arrived below the saved tail`() =
        runTest {
            history.merge(chat(them("a"), me("b")))

            val outcome = history.mergeRead(chat(me("b"), them("c"), me("sent now", "6:50 pm")))

            assertThat(outcome.inserted).isEqualTo(2)
            assertThat(outcome.newAtEnd).containsExactly(them("c"), me("sent now", "6:50 pm")).inOrder()
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
    fun `deleting a chat removes its messages but keeps its note and other chats`() =
        runTest {
            val asha = ChatKey("com.whatsapp", "Asha")
            history.merge(chat(them("a"), title = "Asha"))
            history.merge(chat(them("r"), title = "Ravi"))
            notes.set(asha, "Sister")

            history.deleteChat(asha)

            assertThat(texts(asha)).isEmpty()
            assertThat(texts(ChatKey("com.whatsapp", "Ravi"))).containsExactly("r")
            assertThat(notes.get(asha)).isEqualTo("Sister")
        }

    @Test
    fun `purge deletes messages first seen before the cutoff`() =
        runTest {
            val key = ChatKey("com.whatsapp", "Asha")
            history.merge(chat(them("old")))
            clock.advance(Duration.ofDays(61))
            history.merge(chat(them("old"), them("new")))

            val purged = history.purgeOlderThan(clock.now - Duration.ofDays(60))

            assertThat(purged).isEqualTo(1)
            assertThat(texts(key)).containsExactly("new")
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
                    if (opens == 1) UnreadableDatabase.create(context) else fresh
                },
                discard = { discarded++ },
            )

        assertThat(opened).isSameInstanceAs(fresh)
        assertThat(discarded).isEqualTo(1)
        fresh.close()
    }
}

/** A DB whose open helper fails the way SQLCipher does with a wrong passphrase. */
private object UnreadableDatabase {
    fun create(context: Context): HistoryDatabase =
        Room
            .inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
            .openHelperFactory { configuration ->
                object : androidx.sqlite.db.SupportSQLiteOpenHelper {
                    override val databaseName: String? = configuration.name

                    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {}

                    override val writableDatabase: androidx.sqlite.db.SupportSQLiteDatabase
                        get() = throw SQLiteException("file is not a database")

                    override val readableDatabase: androidx.sqlite.db.SupportSQLiteDatabase
                        get() = writableDatabase

                    override fun close() {}
                }
            }.build()
}
