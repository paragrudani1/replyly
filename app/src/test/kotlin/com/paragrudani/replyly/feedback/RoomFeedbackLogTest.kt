package com.paragrudani.replyly.feedback

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.feedback.db.FeedbackDatabase
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.security.DatabaseKeyProvider
import com.paragrudani.replyly.testing.MutableClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * Runs the real Room schema and queries. SQLCipher's native library is Android-only, so the
 * test swaps in the framework SQLite factory and checks the passphrase reaches the factory.
 */
@RunWith(RobolectricTestRunner::class)
class RoomFeedbackLogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = MutableClock.atLocal(LocalDateTime.of(2026, 11, 21, 10, 0))
    private val retentionDays = MutableStateFlow(60)
    private val passphrasesSeen = mutableListOf<ByteArray>()

    private val fakeKeyProvider =
        object : DatabaseKeyProvider {
            var calls = 0

            override fun getOrCreatePassphrase(): ByteArray {
                calls++
                return "fake-passphrase-32-bytes-long!!!".toByteArray()
            }
        }

    private val db =
        FeedbackDatabase.open(context, fakeKeyProvider, name = "feedback-test.db") { passphrase ->
            passphrasesSeen += passphrase.copyOf()
            FrameworkSQLiteOpenHelperFactory()
        }
    private val log = RoomFeedbackLog(db, clock, retentionDays)

    private val chat = ChatKey("com.whatsapp", "Riya")
    private val other = ChatKey("org.telegram.messenger", "Family")
    private val three =
        listOf(
            Suggestion("agree", "Haan, 6 baje milte hain"),
            Suggestion("delay", "Kal karein?"),
            Suggestion("ask", "Kahan?"),
        )

    @After fun tearDown() {
        db.close()
        context.deleteDatabase("feedback-test.db")
    }

    @Test
    fun `opens with the passphrase from the key provider`() {
        assertThat(fakeKeyProvider.calls).isEqualTo(1)
        assertThat(passphrasesSeen.single()).isEqualTo("fake-passphrase-32-bytes-long!!!".toByteArray())
    }

    @Test
    fun `records an entry with prompt and suggestions, newest first`() =
        runBlocking<Unit> {
            val first = log.record(chat, "anthropic/claude-haiku-5.5", "prompt one", three)
            clock.advance(Duration.ofMinutes(1))
            val second = log.record(other, "google/gemini-3.8-flash", "prompt two", three.reversed())

            val recent = log.observeRecent(10).first()
            assertThat(recent.map { it.id }).containsExactly(second, first).inOrder()
            with(recent[1]) {
                assertThat(chatKey).isEqualTo(chat)
                assertThat(model).isEqualTo("anthropic/claude-haiku-5.5")
                assertThat(prompt).isEqualTo("prompt one")
                assertThat(suggestions).isEqualTo(three)
                assertThat(createdAt).isEqualTo(Instant.parse("2026-11-21T04:30:00Z"))
                assertThat(insertedIndex).isNull()
                assertThat(finalSentText).isNull()
            }
        }

    @Test
    fun `observeRecent honours the limit`() =
        runBlocking<Unit> {
            repeat(5) { log.record(chat, "m", "p$it", three) }
            assertThat(log.observeRecent(2).first().map { it.prompt }).containsExactly("p4", "p3").inOrder()
        }

    @Test
    fun `the final message goes to the newest inserted entry of that chat only`() =
        runBlocking<Unit> {
            val older = log.record(chat, "m", "p1", three)
            log.markInserted(older, 0)
            val newer = log.record(chat, "m", "p2", three)
            log.markInserted(newer, 2)
            val notInserted = log.record(chat, "m", "p3", three)
            val otherChat = log.record(other, "m", "p4", three)
            log.markInserted(otherChat, 1)

            assertThat(log.attachFinalMessage(chat, "Kahan milna hai?")).isTrue()

            val byId = log.observeRecent(10).first().associateBy { it.id }
            assertThat(byId.getValue(newer).finalSentText).isEqualTo("Kahan milna hai?")
            assertThat(byId.getValue(newer).insertedIndex).isEqualTo(2)
            assertThat(byId.getValue(older).finalSentText).isNull()
            assertThat(byId.getValue(notInserted).finalSentText).isNull()
            assertThat(byId.getValue(otherChat).finalSentText).isNull()
        }

    @Test
    fun `a second sent message goes to the next open entry, then nothing is left`() =
        runBlocking<Unit> {
            val older = log.record(chat, "m", "p1", three)
            log.markInserted(older, 0)
            val newer = log.record(chat, "m", "p2", three)
            log.markInserted(newer, 1)

            assertThat(log.attachFinalMessage(chat, "first")).isTrue()
            assertThat(log.attachFinalMessage(chat, "second")).isTrue()
            assertThat(log.attachFinalMessage(chat, "third")).isFalse()
            val byId = log.observeRecent(10).first().associateBy { it.id }
            assertThat(byId.getValue(newer).finalSentText).isEqualTo("first")
            assertThat(byId.getValue(older).finalSentText).isEqualTo("second")
        }

    @Test
    fun `the final message only goes to an insert younger than 6 hours`() =
        runBlocking<Unit> {
            val stale = log.record(chat, "m", "p1", three)
            log.markInserted(stale, 0)
            clock.advance(Duration.ofHours(6))
            assertThat(log.attachFinalMessage(chat, "much later")).isFalse()

            val fresh = log.record(chat, "m", "p2", three)
            log.markInserted(fresh, 1)
            clock.advance(Duration.ofHours(5).plusMinutes(59))
            assertThat(log.attachFinalMessage(chat, "in time")).isTrue()
            val byId = log.observeRecent(10).first().associateBy { it.id }
            assertThat(byId.getValue(fresh).finalSentText).isEqualTo("in time")
            assertThat(byId.getValue(stale).finalSentText).isNull()
        }

    @Test
    fun `a failed purge is retried on the next call`() =
        runBlocking<Unit> {
            var failNext = true
            val flaky =
                flow {
                    if (failNext) {
                        failNext = false
                        throw IOException("settings unreadable")
                    }
                    emit(30)
                }
            val flakyLog = RoomFeedbackLog(db, clock, flaky)
            log.record(chat, "m", "old", three)
            clock.advance(Duration.ofDays(31))

            assertThat(runCatching { flakyLog.purgeIfDue() }.isFailure).isTrue()
            // Not marked as done, so the next call purges instead of waiting a day.
            assertThat(flakyLog.purgeIfDue()).isEqualTo(1)
        }

    @Test
    fun `purges entries older than the cutoff`() =
        runBlocking<Unit> {
            log.record(chat, "m", "old", three)
            clock.advance(Duration.ofDays(10))
            log.record(chat, "m", "new", three)

            val removed = log.purgeOlderThan(clock.instant().minus(Duration.ofDays(5)))
            assertThat(removed).isEqualTo(1)
            assertThat(log.observeRecent(10).first().map { it.prompt }).containsExactly("new")
        }

    @Test
    fun `recording purges entries past retentionDays, at most once a day`() =
        runBlocking<Unit> {
            retentionDays.value = 30
            log.record(chat, "m", "day 0", three)
            clock.advance(Duration.ofDays(31))
            log.record(chat, "m", "day 31", three) // purge runs: "day 0" is past 30 days
            assertThat(log.observeRecent(10).first().map { it.prompt }).containsExactly("day 31")

            retentionDays.value = 1
            clock.advance(Duration.ofHours(23))
            log.record(chat, "m", "day 31 + 23h", three) // purged less than a day ago: skipped
            assertThat(log.observeRecent(10).first()).hasSize(2)

            clock.advance(Duration.ofHours(26))
            log.record(chat, "m", "day 33", three) // due again: retention 1 day keeps only this one
            assertThat(log.observeRecent(10).first().map { it.prompt }).containsExactly("day 33")
        }

    @Test
    fun `clear empties the log and the viewer sees it`() =
        runBlocking<Unit> {
            log.record(chat, "m", "p1", three)
            log.record(other, "m", "p2", three)
            log.observeRecent(10).test {
                assertThat(awaitItem()).hasSize(2)
                assertThat(log.clear()).isEqualTo(2)
                assertThat(awaitItem()).isEmpty()
            }
        }
}
