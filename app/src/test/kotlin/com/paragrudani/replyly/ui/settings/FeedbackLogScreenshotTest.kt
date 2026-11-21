package com.paragrudani.replyly.ui.settings

import com.paragrudani.replyly.feedback.FeedbackEntry
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.testing.ScreenshotRule
import com.paragrudani.replyly.testing.ScreenshotTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotTest.DEVICE)
class FeedbackLogScreenshotTest {
    @get:Rule val shots = ScreenshotRule()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 11, 21)

    private fun entry(
        id: Long,
        at: LocalDateTime,
        chat: String,
        suggestions: List<Suggestion>,
        inserted: Int?,
        sent: String?,
    ) = FeedbackEntry(
        id = id,
        chatKey = ChatKey("com.whatsapp", chat),
        createdAt = at.atZone(zone).toInstant(),
        model = "anthropic/claude-haiku-5.5",
        prompt = "(prompt)",
        suggestions = suggestions,
        insertedIndex = inserted,
        finalSentText = sent,
    )

    private val entries =
        listOf(
            entry(
                3,
                LocalDateTime.of(2026, 11, 21, 9, 42),
                "Riya",
                listOf(
                    Suggestion("agree", "Sure, 7 works for me. See you there!"),
                    Suggestion("decline", "Can't make it tonight, sorry. Tomorrow?"),
                    Suggestion("ask", "Which place were you thinking?"),
                ),
                inserted = 0,
                sent = "Sure, 7 works. See you there!",
            ),
            entry(
                2,
                LocalDateTime.of(2026, 11, 21, 8, 15),
                "Family",
                listOf(
                    Suggestion("agree", "Yes, I'll pick up the cake."),
                    Suggestion("decline", "I can't today, can someone else?"),
                    Suggestion("ask", "What time should I be there?"),
                ),
                inserted = 2,
                sent = null,
            ),
            entry(
                1,
                LocalDateTime.of(2026, 11, 20, 21, 3),
                "Arjun (work)",
                listOf(
                    Suggestion("agree", "Done, I'll send the deck tonight."),
                    Suggestion("decline", "Not today, I'll send it Monday."),
                    Suggestion("ask", "Which version do you need?"),
                ),
                inserted = null,
                sent = null,
            ),
        )

    /** Fixed clock style and app names, so the shots do not depend on the host. */
    @androidx.compose.runtime.Composable
    private fun Log(
        entries: List<FeedbackEntry>,
        error: String? = null,
        today: LocalDate = this.today,
    ) = FeedbackLogScreen(
        entries,
        error,
        onClear = {},
        onBack = {},
        zone = zone,
        today = today,
        use24Hour = false,
        appLabel = { if (it == "com.whatsapp") "WhatsApp" else it },
    )

    @Test
    fun timeline() = shots.screen("feedback/timeline") { Log(entries) }

    /** Seen a day later: the 21st reads "Yesterday", the 20th gets a dated header. */
    @Test
    fun timelineOlderDays() =
        shots.screen("feedback/timeline_older") { Log(entries.drop(1), today = today.plusDays(1)) }

    @Test
    fun empty() = shots.screen("feedback/empty") { Log(emptyList()) }

    @Test
    fun storageLocked() =
        shots.screen("feedback/storage_locked", variants = com.paragrudani.replyly.testing.ShotVariant.LIGHT_DARK) {
            Log(emptyList(), SettingsViewModel.STORAGE_LOCKED)
        }
}
