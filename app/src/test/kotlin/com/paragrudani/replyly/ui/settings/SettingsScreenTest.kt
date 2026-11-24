package com.paragrudani.replyly.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.feedback.InMemoryFeedbackLog
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.current
import com.paragrudani.replyly.testing.MutableClock
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val settings = InMemorySettingsRepository()
    private val clock = MutableClock.atLocal(LocalDateTime.of(2026, 11, 21, 10, 0))
    private val vm =
        SettingsViewModel(settings, InMemoryCostGuard(), InMemoryFeedbackLog(clock), clock)

    @Test
    fun `long-pressing the title opens the debug screen and the wordmark reads as Replyly`() {
        var debugOpened = 0
        compose.setContent {
            ReplylyTheme(dynamicColor = false) {
                SettingsScreen(
                    vm,
                    onOpenOnboarding = {},
                    onOpenDebug = { debugOpened++ },
                    onOpenFeedbackLog = {},
                    aiProvider = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Replyly", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SETTINGS_TITLE_TAG).performTouchInput { longClick() }
        assertThat(debugOpened).isEqualTo(1)
    }

    @Test
    fun `the Material You switch saves at once`() {
        compose.setContent {
            ReplylyTheme(dynamicColor = false) {
                SettingsScreen(vm, onOpenOnboarding = {}, onOpenDebug = {}, onOpenFeedbackLog = {}, aiProvider = {})
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Material You colours"))
        compose.onNodeWithText("Material You colours").performClick()
        compose.waitForIdle()
        assertThat(runBlocking { settings.current().useDynamicColor }).isTrue()
    }

    // Bubble (#55) --------------------------------------------------------------------------------

    private var resets = 0
    private val bubbleVm =
        SettingsViewModel(settings, InMemoryCostGuard(), InMemoryFeedbackLog(clock), clock, resetButtonPositions = {
            resets++
        })

    private fun showBubbleSection() {
        compose.setContent {
            ReplylyTheme(dynamicColor = false) {
                SettingsScreen(
                    bubbleVm,
                    onOpenOnboarding = {},
                    onOpenDebug = {},
                    onOpenFeedbackLog = {},
                    aiProvider = {},
                )
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset button positions"))
    }

    @Test
    fun `the Bubble section has a heading, the switches and two 4-step sliders that read their value`() {
        showBubbleSection()

        compose.onNode(hasText("Bubble") and isHeading()).assertExists()
        compose.onNodeWithText("Show bubble").assertExists()
        compose.onNodeWithText("Shrink when idle").assertExists()
        for ((tag, label, state) in listOf(
            Triple(BUBBLE_SIZE_SLIDER_TAG, "Size", "1 times"),
            Triple(BUBBLE_OPACITY_SLIDER_TAG, "Opacity", "100 percent"),
        )) {
            val config = compose.onNodeWithTag(tag).fetchSemanticsNode().config
            val range = config[SemanticsProperties.ProgressBarRangeInfo]
            assertThat(range.range).isEqualTo(0f..3f)
            assertThat(range.steps).isEqualTo(2) // two between the ends: 4 positions
            assertThat(config[SemanticsProperties.ContentDescription]).containsExactly(label)
            assertThat(config[SemanticsProperties.StateDescription]).isEqualTo(state)
        }
    }

    @Test
    fun `switches and sliders save at once, with no Save bar`() {
        showBubbleSection()

        compose.onNodeWithText("Show bubble").performClick()
        compose.onNodeWithText("Shrink when idle").performClick()
        compose.onNodeWithTag(BUBBLE_SIZE_SLIDER_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.onNodeWithTag(BUBBLE_OPACITY_SLIDER_TAG).performSemanticsAction(SemanticsActions.SetProgress) {
            it(1f)
        }
        compose.waitForIdle()

        val stored = runBlocking { settings.current() }
        assertThat(stored.showBubble).isFalse()
        assertThat(stored.shrinkWhenIdle).isTrue()
        assertThat(stored.bubbleSize).isEqualTo(1.3f)
        assertThat(stored.bubbleOpacity).isEqualTo(60)
        compose.onNodeWithText("Unsaved changes").assertDoesNotExist()
        compose
            .onNodeWithTag(BUBBLE_SIZE_SLIDER_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1.3 times"))
        compose
            .onNodeWithTag(BUBBLE_OPACITY_SLIDER_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "60 percent"))
    }

    @Test
    fun `reset button positions asks first - Cancel does nothing, Reset resets once`() {
        showBubbleSection()

        compose.onNodeWithText("Reset button positions").performClick()
        compose.onNodeWithText("Reset button positions?").assertExists()
        compose.onNodeWithText("The bubble goes back to its default spot in every app.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Reset button positions?").assertDoesNotExist()
        assertThat(resets).isEqualTo(0)

        compose.onNodeWithText("Reset button positions").performClick()
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Reset button positions?").assertDoesNotExist()
        assertThat(resets).isEqualTo(1)
    }
}
