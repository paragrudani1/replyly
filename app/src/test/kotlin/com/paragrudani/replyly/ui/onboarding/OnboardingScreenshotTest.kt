package com.paragrudani.replyly.ui.onboarding

import com.paragrudani.replyly.onboarding.OnboardingState
import com.paragrudani.replyly.onboarding.OnboardingStep
import com.paragrudani.replyly.onboarding.OnboardingStepId
import com.paragrudani.replyly.onboarding.StepStatus
import com.paragrudani.replyly.onboarding.WelcomePage
import com.paragrudani.replyly.testing.ScreenshotRule
import com.paragrudani.replyly.testing.ScreenshotTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotTest.DEVICE)
class OnboardingScreenshotTest {
    @get:Rule val shots = ScreenshotRule()

    private fun shot(
        name: String,
        page: WelcomePage,
        state: OnboardingState?,
    ) = shots.screen("onboarding/$name") {
        OnboardingContent(state = state, onOpen = {}, onConfirmSleepingApps = {}, onFinished = {}, startPage = page)
    }

    @Test
    fun welcome() = shot("welcome", WelcomePage.WhatItDoes, stepsDone(0))

    @Test
    fun privacy() = shot("privacy", WelcomePage.Privacy, stepsDone(0))

    @Test
    fun checklistNoneDone() = shot("checklist_0_done", WelcomePage.Setup, stepsDone(0))

    /** Includes the "turned on but not running" state of step 2 in red. */
    @Test
    fun checklistThreeDone() =
        shot(
            "checklist_3_done",
            WelcomePage.Setup,
            OnboardingState(
                listOf(
                    OnboardingStep(OnboardingStepId.RestrictedSettings, StepStatus.Done),
                    OnboardingStep(OnboardingStepId.AccessibilityService, StepStatus.EnabledNotRunning),
                    OnboardingStep(OnboardingStepId.BatteryUnrestricted, StepStatus.Done),
                    OnboardingStep(OnboardingStepId.SleepingApps, StepStatus.Done),
                    OnboardingStep(OnboardingStepId.ApiKeyAndModel, StepStatus.NotDone),
                ),
            ),
        )

    @Test
    fun checklistAllDone() = shot("checklist_5_done", WelcomePage.Setup, stepsDone(5))

    private fun stepsDone(count: Int) =
        OnboardingState(
            OnboardingStepId.entries.mapIndexed { index, id ->
                val notDone = if (id == OnboardingStepId.SleepingApps) StepStatus.NotConfirmed else StepStatus.NotDone
                OnboardingStep(id, if (index < count) StepStatus.Done else notDone)
            },
        )
}
