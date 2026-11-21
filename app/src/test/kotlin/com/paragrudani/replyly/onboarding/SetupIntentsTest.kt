package com.paragrudani.replyly.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SetupIntentsTest {
    private val pkg = "com.paragrudani.replyly"

    @Test
    fun `restricted settings opens this app's App info`() {
        val intents = SetupIntents.forStep(OnboardingStepId.RestrictedSettings, pkg)

        assertThat(intents.first().action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(intents.first().data.toString()).isEqualTo("package:$pkg")
    }

    @Test
    fun `accessibility opens accessibility settings`() {
        val intents = SetupIntents.forStep(OnboardingStepId.AccessibilityService, pkg)

        assertThat(intents.first().action).isEqualTo(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    @Test
    fun `battery asks to ignore optimizations, then falls back to App info`() {
        val intents = SetupIntents.forStep(OnboardingStepId.BatteryUnrestricted, pkg)

        assertThat(intents.map { it.action })
            .containsExactly(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ).inOrder()
        assertThat(intents.first().data.toString()).isEqualTo("package:$pkg")
    }

    @Test
    fun `sleeping apps tries Samsung Device care first, then general battery settings`() {
        val intents = SetupIntents.forStep(OnboardingStepId.SleepingApps, pkg)

        assertThat(intents.first().component?.packageName).isEqualTo("com.samsung.android.lool")
        assertThat(intents.map { it.action }).contains(Intent.ACTION_POWER_USAGE_SUMMARY)
        assertThat(intents.last().action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }

    @Test
    fun `api key step has no system screen`() {
        assertThat(SetupIntents.forStep(OnboardingStepId.ApiKeyAndModel, pkg)).isEmpty()
    }

    @Test
    fun `launchFirst skips intents that do not resolve`() {
        val intents = SetupIntents.forStep(OnboardingStepId.SleepingApps, pkg)
        val started = mutableListOf<Intent>()

        val launched =
            SetupIntents.launchFirst(intents) { intent ->
                if (intent.component != null) throw ActivityNotFoundException()
                started += intent
            }

        assertThat(launched).isTrue()
        assertThat(started.single().action).isEqualTo(Intent.ACTION_POWER_USAGE_SUMMARY)
    }

    @Test
    fun `launchFirst reports failure when nothing resolves`() {
        val launched =
            SetupIntents.launchFirst(SetupIntents.forStep(OnboardingStepId.BatteryUnrestricted, pkg)) {
                throw ActivityNotFoundException()
            }

        assertThat(launched).isFalse()
    }

    @Test
    fun `launchFirst skips an intent that is not allowed`() {
        val intents = SetupIntents.forStep(OnboardingStepId.SleepingApps, pkg)
        val started = mutableListOf<Intent>()

        val launched =
            SetupIntents.launchFirst(intents) { intent ->
                if (intent.component != null) throw SecurityException("not exported")
                started += intent
            }

        assertThat(launched).isTrue()
        assertThat(started.single().action).isEqualTo(Intent.ACTION_POWER_USAGE_SUMMARY)
    }

    @Test
    fun `launchFirst reports failure when every intent is refused`() {
        val launched =
            SetupIntents.launchFirst(SetupIntents.forStep(OnboardingStepId.SleepingApps, pkg)) {
                throw SecurityException("not exported")
            }

        assertThat(launched).isFalse()
    }
}
