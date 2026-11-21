package com.paragrudani.replyly.history

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.ReplylySettings
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration

class HistoryRetentionTest {
    private val clock = MutableClock()
    private val history = InMemoryHistoryRepository(clock)
    private val settings = InMemorySettingsRepository(ReplylySettings(retentionDays = 60))
    private val retention = HistoryRetention(history, settings, clock)

    private suspend fun saveMessage(text: String) =
        history.merge(
            ParsedChat(
                packageName = "com.whatsapp",
                title = "Asha",
                isGroup = false,
                messages = listOf(ChatMessage("Asha", text, "6:40 pm", isMe = false)),
                historyApplies = true,
            ),
        )

    @Test
    fun `purgeNow deletes messages first seen more than retentionDays ago`() =
        runTest {
            saveMessage("61 days old")
            clock.advance(Duration.ofDays(1))
            saveMessage("60 days old")
            clock.advance(Duration.ofDays(60))

            assertThat(retention.purgeNow()).isEqualTo(1)
        }

    @Test
    fun `the retention period comes from settings`() =
        runTest {
            saveMessage("8 days old")
            clock.advance(Duration.ofDays(8))
            settings.update { it.copy(retentionDays = 7) }

            assertThat(retention.purgeNow()).isEqualTo(1)
        }

    @Test
    fun `purgeIfDue runs at most once a day`() =
        runTest {
            settings.update { it.copy(retentionDays = 1) }
            saveMessage("a")
            clock.advance(Duration.ofHours(12))
            assertThat(retention.purgeNow()).isEqualTo(0) // "a" is 12 h old: kept.

            clock.advance(Duration.ofHours(13)) // "a" is now 25 h old, but the last purge was 13 h ago.
            assertThat(retention.purgeIfDue()).isEqualTo(0)

            clock.advance(Duration.ofHours(11)) // 24 h since the last purge.
            assertThat(retention.purgeIfDue()).isEqualTo(1)
            saveMessage("b")
            clock.advance(Duration.ofDays(2))
            retention.purgeNow()
            assertThat(retention.purgeIfDue()).isEqualTo(0)
        }

    @Test
    fun `purgeIfDue runs when nothing was purged yet`() =
        runTest {
            saveMessage("old")
            clock.advance(Duration.ofDays(61))

            assertThat(retention.purgeIfDue()).isEqualTo(1)
        }
}
