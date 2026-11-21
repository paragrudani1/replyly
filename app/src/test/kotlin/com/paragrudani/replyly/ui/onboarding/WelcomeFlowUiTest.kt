package com.paragrudani.replyly.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.onboarding.OnboardingState
import com.paragrudani.replyly.onboarding.OnboardingStep
import com.paragrudani.replyly.onboarding.OnboardingStepId
import com.paragrudani.replyly.onboarding.StepStatus
import com.paragrudani.replyly.onboarding.WelcomePage
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WelcomeFlowUiTest {
    @get:Rule val compose = createComposeRule()

    private fun steps(battery: StepStatus = StepStatus.NotDone) =
        OnboardingState(
            listOf(
                OnboardingStep(OnboardingStepId.RestrictedSettings, StepStatus.Done),
                OnboardingStep(OnboardingStepId.AccessibilityService, StepStatus.EnabledNotRunning),
                OnboardingStep(OnboardingStepId.BatteryUnrestricted, battery),
                OnboardingStep(OnboardingStepId.SleepingApps, StepStatus.NotConfirmed),
                OnboardingStep(OnboardingStepId.ApiKeyAndModel, StepStatus.NotDone),
            ),
        )

    private fun show(
        start: WelcomePage,
        reducedMotion: Boolean = false,
        state: () -> OnboardingState? = { steps() },
    ) {
        compose.setContent {
            ReplylyTheme(darkTheme = false, dynamicColor = false, reducedMotion = reducedMotion) {
                OnboardingContent(
                    state = state(),
                    onOpen = {},
                    onConfirmSleepingApps = {},
                    onFinished = {},
                    startPage = start,
                )
            }
        }
    }

    private fun page(name: String) = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, name))

    @Test
    fun `first run opens on what Replyly does, Next walks to privacy then setup`() {
        show(WelcomePage.WhatItDoes)
        page("What Replyly does").assertIsDisplayed()

        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        page("Your privacy").assertIsDisplayed()

        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        page("Set up").assertIsDisplayed()
    }

    @Test
    fun `skip jumps to the checklist and back returns one page`() {
        show(WelcomePage.WhatItDoes)

        compose.onNodeWithText("Skip").performClick()
        compose.waitForIdle()
        page("Set up").assertIsDisplayed()

        compose.onNodeWithText("Back").performClick()
        compose.waitForIdle()
        page("Your privacy").assertIsDisplayed()
    }

    @Test
    fun `setup steps from settings opens on the checklist`() {
        show(WelcomePage.Setup)
        page("Set up").assertIsDisplayed()
    }

    @Test
    fun `page indicator says which page this is`() {
        show(WelcomePage.WhatItDoes, reducedMotion = true)
        compose.onNodeWithContentDescription("Page 1 of 3").assertExists()
    }

    @Test
    fun `talkback reads each step with its state`() {
        show(WelcomePage.Setup)
        compose.onNodeWithContentDescription("Restricted settings: done").assertExists()
        compose.onNodeWithContentDescription("Accessibility service: turned on but not running").assertExists()
        compose.onNodeWithContentDescription("Battery: not done").assertExists()
        compose.onNodeWithContentDescription("Sleeping apps: not confirmed").assertExists()
        compose.onNodeWithContentDescription("API key and model: not done").assertExists()
    }

    @Test
    fun `a step done after the screen opened ticks with an animation`() {
        var current by mutableStateOf(steps())
        show(WelcomePage.Setup) { current }
        val tick = compose.onNodeWithTag(tickTag(OnboardingStepId.BatteryUnrestricted), useUnmergedTree = true)
        assertThat(tick.progress()).isEqualTo(0f)

        compose.mainClock.autoAdvance = false
        update { current = steps(battery = StepStatus.Done) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(120)
        val midway = tick.progress()
        assertThat(midway).isGreaterThan(0f)
        assertThat(midway).isLessThan(1f)

        compose.mainClock.advanceTimeBy(1_000)
        assertThat(tick.progress()).isEqualTo(1f)
        compose.onNodeWithContentDescription("Battery: done").assertExists()
    }

    @Test
    fun `a step already done when the list first loads shows its tick without animating`() {
        compose.mainClock.autoAdvance = false
        show(WelcomePage.Setup)
        compose.mainClock.advanceTimeByFrame()
        val tick = compose.onNodeWithTag(tickTag(OnboardingStepId.RestrictedSettings), useUnmergedTree = true)
        assertThat(tick.progress()).isEqualTo(1f)
    }

    @Test
    fun `a step that loads after the screen opened does not animate either`() {
        var current by mutableStateOf<OnboardingState?>(null)
        compose.mainClock.autoAdvance = false
        show(WelcomePage.Setup) { current }
        compose.mainClock.advanceTimeByFrame()

        update { current = steps() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()

        val tick = compose.onNodeWithTag(tickTag(OnboardingStepId.RestrictedSettings), useUnmergedTree = true)
        assertThat(tick.progress()).isEqualTo(1f)
    }

    @Test
    fun `with animations removed a new tick appears at once`() {
        var current by mutableStateOf(steps())
        show(WelcomePage.Setup, reducedMotion = true) { current }
        compose.mainClock.autoAdvance = false

        update { current = steps(battery = StepStatus.Done) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()

        val tick = compose.onNodeWithTag(tickTag(OnboardingStepId.BatteryUnrestricted), useUnmergedTree = true)
        assertThat(tick.progress()).isEqualTo(1f)
    }

    /** A state write from the test thread, delivered even while the test clock is paused. */
    private fun update(write: () -> Unit) {
        write()
        Snapshot.sendApplyNotifications()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.progress(): Float =
        fetchSemanticsNode().config.getOrNull(TickProgress) ?: error("no tick progress")
}
