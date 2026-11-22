package com.paragrudani.replyly.ui.settings

import android.content.pm.ApplicationInfo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.feedback.FeedbackEntry
import com.paragrudani.replyly.feedback.PromptVersionStats
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.parser.ChatKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class FeedbackLogScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a storage error is shown in place of the empty-log hint, with a working Try again`() {
        var retries = 0
        compose.setContent {
            FeedbackLogScreen(
                entries = emptyList(),
                error = SettingsViewModel.STORAGE_LOCKED,
                onClear = {},
                onBack = {},
                onRetry = { retries++ },
            )
        }

        compose.onNodeWithText("Secure storage is locked", substring = true).assertExists()
        compose.onNodeWithText("No entries yet", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Clear log").assertIsNotEnabled()
        compose.onNodeWithText("Try again").performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `without an error the empty-log hint shows`() {
        compose.setContent { FeedbackLogScreen(entries = emptyList(), error = null, onClear = {}, onBack = {}) }

        compose.onNodeWithText("No entries yet", substring = true).assertExists()
        compose.onNodeWithText("Secure storage is locked", substring = true).assertDoesNotExist()
    }

    @Test
    fun `entries show the app name and follow the 24-hour setting`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val entry =
            FeedbackEntry(
                id = 1,
                chatKey = ChatKey("com.whatsapp", "Riya"),
                createdAt = LocalDateTime.of(2026, 11, 21, 14, 5).atZone(zone).toInstant(),
                model = "m",
                prompt = "p",
                suggestions = listOf(Suggestion("agree", "Yes")),
                insertedIndex = null,
                finalSentText = null,
            )
        compose.setContent {
            FeedbackLogScreen(
                listOf(entry),
                null,
                {},
                {},
                zone = zone,
                today = LocalDate.of(2026, 11, 21),
                use24Hour = true,
                appLabel = { if (it == "com.whatsapp") "WhatsApp" else it },
            )
        }
        compose.onNodeWithText("WhatsApp · m", substring = true).assertExists()
        compose.onNodeWithText("14:05", substring = true).assertExists()
        compose.onNodeWithText("com.whatsapp", substring = true).assertDoesNotExist()
    }

    @Test
    fun `time format follows the 12 or 24-hour choice`() {
        val time = LocalTime.of(14, 5)
        assertThat(feedbackTimeFormatter(true, Locale.US).format(time)).isEqualTo("14:05")
        assertThat(feedbackTimeFormatter(false, Locale.US).format(time)).isEqualTo("2:05 PM")
    }

    @Test
    fun `app label falls back to the package id when the app is not visible`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = context.packageManager
        shadowOf(pm).installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = "com.whatsapp"
                applicationInfo =
                    ApplicationInfo().apply {
                        packageName = "com.whatsapp"
                        nonLocalizedLabel = "WhatsApp"
                    }
            },
        )
        assertThat(appLabel(pm, "com.whatsapp")).isEqualTo("WhatsApp")
        assertThat(appLabel(pm, "org.example.gone")).isEqualTo("org.example.gone")
    }

    @Test
    fun `the day rolls over just after local midnight, across clock changes`() {
        val ny = ZoneId.of("America/New_York")
        val before = LocalDateTime.of(2026, 11, 21, 23, 59, 30).atZone(ny).toInstant()
        assertThat(durationUntilNextDay(before, ny)).isEqualTo(Duration.ofSeconds(30).plusMillis(1))
        // 1 Nov 2026 is 25 hours long in New York (DST ends).
        val dstStart = LocalDateTime.of(2026, 11, 1, 0, 0).atZone(ny).toInstant()
        assertThat(durationUntilNextDay(dstStart, ny)).isEqualTo(Duration.ofHours(25).plusMillis(1))
        // Exactly at midnight: wait for the next one, never 0.
        assertThat(durationUntilNextDay(dstStart, ny).isNegative).isFalse()
        val waited = dstStart.plus(durationUntilNextDay(dstStart, ny))
        assertThat(waited.atZone(ny).toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 2))
    }

    private val v2Entry =
        FeedbackEntry(
            id = 7,
            chatKey = ChatKey("com.whatsapp", "Riya"),
            createdAt = LocalDateTime.of(2026, 11, 21, 14, 5).atZone(ZoneId.of("Asia/Kolkata")).toInstant(),
            model = "m",
            prompt = "THE PROMPT",
            suggestions = listOf(Suggestion("yes", "Haan"), Suggestion("no", "Nahi"), Suggestion("ask", "Kab?")),
            insertedIndex = 0,
            finalSentText = "Haan pakka",
            promptVersion = 2,
            analysis = "Riya asks to meet kal = Sun 22 Nov.",
        )

    @Test
    fun `the analysis stays hidden until Show details, then shows with the prompt`() {
        compose.setContent {
            FeedbackLogScreen(
                listOf(v2Entry),
                null,
                {},
                {},
                zone = ZoneId.of("Asia/Kolkata"),
                today = LocalDate.of(2026, 11, 21),
                appLabel = { "WhatsApp" },
            )
        }
        compose.onNodeWithText("WhatsApp · m · prompt v2").assertExists()
        compose.onNodeWithText("Riya asks to meet kal = Sun 22 Nov.").assertDoesNotExist()

        compose.onNodeWithText("Show details").performScrollTo().performClick()

        compose.onNodeWithText("Riya asks to meet kal = Sun 22 Nov.").assertExists()
        compose.onNodeWithText("THE PROMPT").assertExists()
    }

    @Test
    fun `the details show the prompt-cache read count when the provider reported it`() {
        compose.setContent {
            FeedbackLogScreen(
                listOf(v2Entry.copy(cacheReadTokens = 1_024)),
                null,
                {},
                {},
                zone = ZoneId.of("Asia/Kolkata"),
                today = LocalDate.of(2026, 11, 21),
            )
        }
        compose.onNodeWithText("Cache read: 1,024 tokens").assertDoesNotExist()
        compose.onNodeWithText("Show details").performScrollTo().performClick()
        compose.onNodeWithText("Cache read: 1,024 tokens").assertExists()
    }

    @Test
    fun `no cache line when the provider did not report it`() {
        assertThat(cacheReadText(v2Entry)).isNull()
        assertThat(cacheReadText(v2Entry.copy(cacheReadTokens = 0))).isEqualTo("Cache read: 0 tokens")
    }

    @Test
    fun `the quality card shows insert rate and edit amount per prompt version`() {
        val stats =
            listOf(
                PromptVersionStats(
                    1,
                    panels = 4,
                    panelsWithInsert = 1,
                    editsMeasured = 0,
                    averageEdit = null,
                    panelsEstimated = true,
                ),
                PromptVersionStats(2, panels = 10, panelsWithInsert = 7, editsMeasured = 5, averageEdit = 0.123),
            )
        compose.setContent {
            FeedbackLogScreen(listOf(v2Entry), null, {}, {}, stats = stats, today = LocalDate.of(2026, 11, 21))
        }
        compose.onNodeWithText("Suggestion quality").assertExists()
        compose.onNodeWithText("Prompt v1 (estimated)", substring = true).assertExists()
        compose.onNodeWithText("Prompt v2", substring = true).assertExists()
        compose.onNodeWithText("Inserted in 1 of 4 panels (25%) · no sent messages seen yet").assertExists()
        compose.onNodeWithText("Inserted in 7 of 10 panels (70%) · edited 12% on average (5 sent)").assertExists()
    }
}
