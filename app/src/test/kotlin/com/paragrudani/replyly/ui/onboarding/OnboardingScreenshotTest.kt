package com.paragrudani.replyly.ui.onboarding

import com.paragrudani.replyly.onboarding.OnboardingState
import com.paragrudani.replyly.onboarding.OnboardingStep
import com.paragrudani.replyly.onboarding.OnboardingStepId
import com.paragrudani.replyly.onboarding.StepStatus
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

    @Test
    fun halfway() {
        val state =
            OnboardingState(
                listOf(
                    OnboardingStep(OnboardingStepId.RestrictedSettings, StepStatus.Done),
                    OnboardingStep(OnboardingStepId.AccessibilityService, StepStatus.Done),
                    OnboardingStep(OnboardingStepId.BatteryUnrestricted, StepStatus.NotDone),
                    OnboardingStep(OnboardingStepId.SleepingApps, StepStatus.NotConfirmed),
                    OnboardingStep(OnboardingStepId.ApiKeyAndModel, StepStatus.NotDone),
                ),
            )
        shots.screen("onboarding/halfway") {
            OnboardingContent(state = state, onOpen = {}, onConfirmSleepingApps = {}, onFinished = {})
        }
    }
}
