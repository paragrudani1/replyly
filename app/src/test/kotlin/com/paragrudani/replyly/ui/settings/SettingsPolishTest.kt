package com.paragrudani.replyly.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.feedback.FeedbackEntry
import com.paragrudani.replyly.feedback.InMemoryFeedbackLog
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.settings.InMemoryApiKeyStore
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.current
import com.paragrudani.replyly.testing.MutableClock
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class SettingsPolishTest {
    @get:Rule val compose = createComposeRule()

    private val settings = InMemorySettingsRepository()
    private val clock = MutableClock.atLocal(LocalDateTime.of(2026, 11, 21, 10, 0))
    private val vm =
        SettingsViewModel(settings, InMemoryApiKeyStore(), InMemoryCostGuard(), InMemoryFeedbackLog(clock), clock)

    private class RecordingHaptics : HapticFeedback {
        val performed = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            performed += hapticFeedbackType
        }
    }

    private fun setScreen(
        haptics: HapticFeedback = RecordingHaptics(),
        onOpenWhatsNew: () -> Unit = {},
        onOpenFeedbackLog: () -> Unit = {},
        aiProvider: (@Composable ColumnScope.() -> Unit)? = null,
    ) = compose.setContent {
        ReplylyTheme(dynamicColor = false) {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                SettingsScreen(
                    vm,
                    onOpenOnboarding = {},
                    onOpenDebug = {},
                    onOpenFeedbackLog = onOpenFeedbackLog,
                    onOpenWhatsNew = onOpenWhatsNew,
                    appVersion = "1.0",
                    aiProvider = aiProvider,
                )
            }
        }
    }

    private fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))

    @Test
    fun `sections have headings in the agreed order`() {
        setScreen()
        listOf("AI provider", "Your style", "Privacy", "Limits and usage", "Appearance", "About").forEach { title ->
            scrollTo(title)
            compose.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        }
    }

    @Test
    fun `toggling Material You ticks a haptic and saves`() {
        val haptics = RecordingHaptics()
        setScreen(haptics)
        scrollTo("Material You colours")
        compose.onNodeWithText("Material You colours").performClick()
        compose.waitForIdle()
        assertThat(haptics.performed).containsExactly(HapticFeedbackType.ToggleOn)
        assertThat(runBlocking { settings.current().useDynamicColor }).isTrue()
    }

    @Test
    fun `save gives a confirm haptic`() {
        val haptics = RecordingHaptics()
        setScreen(haptics)
        vm.editDraft { it.copy(dailyRequestLimit = "50") }
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertThat(haptics.performed).containsExactly(HapticFeedbackType.Confirm)
        assertThat(runBlocking { settings.current().dailyRequestLimit }).isEqualTo(50)
    }

    @Test
    fun `about rows open what's new and the feedback log`() {
        var whatsNew = 0
        var log = 0
        setScreen(onOpenWhatsNew = { whatsNew++ }, onOpenFeedbackLog = { log++ })
        scrollTo("Feedback log")
        compose.onNodeWithText("Feedback log").performClick()
        scrollTo("What's new")
        compose.onNodeWithText("What's new").performClick()
        assertThat(log).isEqualTo(1)
        assertThat(whatsNew).isEqualTo(1)
    }

    @Test
    fun `the AI provider slot replaces the legacy fields`() {
        setScreen(aiProvider = { Text("Profiles go here") })
        compose.onNodeWithText("Profiles go here").assertExists()
        compose.onNodeWithText("API endpoint").assertDoesNotExist()
        compose.onNodeWithText("3 parallel calls").assertExists()
    }

    @Test
    fun `empty blocklists show their empty states`() {
        setScreen()
        scrollTo("No apps blocked yet")
        compose.onNodeWithText("No apps blocked yet").assertExists()
        scrollTo("No chats blocked yet")
        compose.onNodeWithText("No chats blocked yet").assertExists()
    }

    @Test
    fun `usage ring reads today's requests to TalkBack`() {
        setScreen()
        scrollTo("Limits and usage")
        compose.onNodeWithContentDescription("Requests today, 0 of 200").assertExists()
    }

    @Test
    fun `the model picker selects one id`() {
        var selected by mutableStateOf<String?>("google/gemini-3.8-flash")
        compose.setContent {
            ReplylyTheme(dynamicColor = false) {
                ModelPickerList(models = MODEL_SHORTLIST + MODEL_SHORTLIST, selected = selected, onSelect = {
                    selected =
                        it
                })
            }
        }
        compose.onNodeWithText("google/gemini-3.8-flash").assertIsSelected()
        compose.onNodeWithText("anthropic/claude-haiku-5.5").performClick()
        assertThat(selected).isEqualTo("anthropic/claude-haiku-5.5")
        compose.onNodeWithText("anthropic/claude-haiku-5.5").assertIsSelected()
        compose.onNodeWithText("google/gemini-3.8-flash").assertIsNotSelected()
    }

    @Test
    fun `what's new closes with Got it`() {
        var done = 0
        compose.setContent {
            ReplylyTheme(
                dynamicColor = false,
            ) { WhatsNewContent("What's new", listOf("One", "Two"), onDone = { done++ }) }
        }
        compose.onNodeWithText("Two").assertExists()
        compose.onNodeWithText("Got it").performClick()
        assertThat(done).isEqualTo(1)
    }

    @Test
    fun `the timeline marks the inserted reply and shows the sent message`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val entry =
            FeedbackEntry(
                id = 1,
                chatKey = ChatKey("com.whatsapp", "Riya"),
                createdAt = LocalDateTime.of(2026, 11, 21, 9, 0).atZone(zone).toInstant(),
                model = "m",
                prompt = "p",
                suggestions =
                    listOf(
                        Suggestion("agree", "Yes!"),
                        Suggestion("decline", "No."),
                        Suggestion("ask", "When?"),
                    ),
                insertedIndex = 0,
                finalSentText = "Yes, see you",
            )
        compose.setContent {
            ReplylyTheme(dynamicColor = false) {
                FeedbackLogScreen(listOf(entry), null, {}, {}, zone = zone, today = LocalDate.of(2026, 11, 21))
            }
        }
        compose.onNodeWithText("Today").assertExists()
        compose
            .onNode(hasText("Yes!") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Inserted"))
            .assertExists()
        compose
            .onNode(
                hasText("No.") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not inserted"),
            ).assertExists()
        compose.onNodeWithText("Yes, see you").assertExists()
    }
}
