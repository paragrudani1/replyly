package com.paragrudani.replyly.ui.settings

import android.view.View
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.feedback.FeedbackEntry
import com.paragrudani.replyly.feedback.InMemoryFeedbackLog
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.settings.SettingsRepository
import com.paragrudani.replyly.settings.current
import com.paragrudani.replyly.testing.MutableClock
import com.paragrudani.replyly.ui.settings.profiles.MODEL_SHORTLIST
import com.paragrudani.replyly.ui.theme.HapticEvent
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class SettingsPolishTest {
    @get:Rule val compose = createComposeRule()

    private val settings = InMemorySettingsRepository()
    private val clock = MutableClock.atLocal(LocalDateTime.of(2026, 11, 21, 10, 0))
    private val vm =
        SettingsViewModel(settings, InMemoryCostGuard(), InMemoryFeedbackLog(clock), clock)

    /**
     * The view the haptics helper binds to (as in HapticsTest's "compose entry point binds to the
     * current view"). Robolectric's shadow records the last feedback the system was asked to play.
     */
    private lateinit var hostView: View

    private fun lastHaptic(): Int = shadowOf(hostView).lastHapticFeedbackPerformed()

    private fun setScreen(
        onOpenWhatsNew: (() -> Unit)? = {},
        onOpenFeedbackLog: () -> Unit = {},
        aiProvider: @Composable ColumnScope.() -> Unit = {},
    ) = compose.setContent {
        ReplylyTheme(dynamicColor = false) {
            run {
                hostView = LocalView.current
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
        setScreen()
        scrollTo("Material You colours")
        compose.onNodeWithText("Material You colours").performClick()
        compose.waitForIdle()
        assertThat(lastHaptic()).isEqualTo(HapticEvent.ToggleOn.feedbackConstant)
        assertThat(runBlocking { settings.current().useDynamicColor }).isTrue()
    }

    @Test
    fun `a successful save gives one confirm haptic`() {
        setScreen()
        vm.editDraft { it.copy(dailyRequestLimit = "50") }
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertThat(lastHaptic()).isEqualTo(HapticEvent.Save.feedbackConstant)
        assertThat(runBlocking { settings.current().dailyRequestLimit }).isEqualTo(50)
    }

    @Test
    fun `a save that fails validation gives no haptic`() {
        setScreen()
        vm.editDraft { it.copy(dailyRequestLimit = "0") }
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fix the marked fields").assertExists()
        assertThat(lastHaptic()).isEqualTo(noHaptic)
    }

    @Test
    fun `a save whose write fails gives no haptic`() {
        val failing = FailingSettingsRepository()
        val failingVm = SettingsViewModel(failing, InMemoryCostGuard(), InMemoryFeedbackLog(clock), clock)
        compose.setContent {
            ReplylyTheme(dynamicColor = false) {
                run {
                    hostView = LocalView.current
                    SettingsScreen(
                        failingVm,
                        onOpenOnboarding = {},
                        onOpenDebug = {},
                        onOpenFeedbackLog = {},
                        aiProvider = {},
                        appVersion = "1.0",
                    )
                }
            }
        }
        failingVm.editDraft { it.copy(dailyRequestLimit = "50") }
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertThat(failingVm.state.value.saveError).isNotNull()
        assertThat(lastHaptic()).isEqualTo(noHaptic)
    }

    /** Robolectric's value before any haptic was played. */
    private val noHaptic = -1

    /** Loads defaults, then every write fails like a full disk. */
    private class FailingSettingsRepository : SettingsRepository {
        override val settings = MutableStateFlow(ReplylySettings())

        override suspend fun update(transform: (ReplylySettings) -> ReplylySettings): Unit =
            throw IOException("disk full")
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
    fun `the AI provider section shows the slot, and parallel mode is gone`() {
        setScreen(aiProvider = { Text("Profiles go here") })
        compose.onNodeWithText("Profiles go here").assertExists()
        compose.onNodeWithText("API endpoint").assertDoesNotExist()
        compose.onNodeWithText("3 parallel calls").assertDoesNotExist()
    }

    @Test
    fun `your style has the optional names field`() {
        setScreen()
        scrollTo("Names people call me")
        compose.onNodeWithText("Names people call me").assertExists()
    }

    @Test
    fun `what's new row is hidden when there are no notes`() {
        setScreen(onOpenWhatsNew = null)
        scrollTo("Setup steps")
        compose.onNodeWithText("What's new").assertDoesNotExist()
    }

    @Test
    fun `empty blocklists show their empty states, the always-skipped line only once`() {
        setScreen()
        scrollTo("No apps blocked yet")
        compose.onNodeWithText("No apps blocked yet").assertExists()
        scrollTo("No chats blocked yet")
        compose.onNodeWithText("No chats blocked yet").assertExists()
        compose.onAllNodesWithText("Banking apps", substring = true).assertCountEquals(1)
    }

    @Test
    fun `the usage card is one TalkBack stop with the progress`() {
        setScreen()
        scrollTo("0 of 200 requests today")
        compose
            .onNode(
                hasText("0 of 200 requests today") and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            ).assertExists()
        // The ring's "0%" is not a second stop.
        compose.onNodeWithText("0%").assertDoesNotExist()
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
        // Only the inserted line is announced.
        compose
            .onNode(hasText("No.") and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
            .assertDoesNotExist()
        compose.onNodeWithText("Yes, see you").assertExists()
    }
}
