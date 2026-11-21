package com.paragrudani.replyly.onboarding

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.settings.ApiFormat
import com.paragrudani.replyly.settings.InMemoryApiKeyStore
import com.paragrudani.replyly.settings.InMemoryProfileRepository
import com.paragrudani.replyly.settings.ProviderProfile
import com.paragrudani.replyly.settings.ReplylySettings
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OnboardingCheckerTest {
    private val probe = FakeProbe()
    private val manual = FakeManualSteps()
    private val keys = InMemoryApiKeyStore()
    private val profile =
        ProviderProfile(
            "p",
            "OpenRouter",
            ApiFormat.OpenAi,
            ReplylySettings.DEFAULT_ENDPOINT,
            ReplylySettings.DEFAULT_MODEL,
        )
    private val profiles = InMemoryProfileRepository(listOf(profile))
    private val checker = OnboardingChecker(probe, manual, keys, profiles)

    private suspend fun status(id: OnboardingStepId) = checker.check().step(id).status

    @Test
    fun `fresh install has every step not done, in spec order`() =
        runTest {
            val state = checker.check()

            assertThat(state.steps.map { it.id })
                .containsExactly(
                    OnboardingStepId.RestrictedSettings,
                    OnboardingStepId.AccessibilityService,
                    OnboardingStepId.BatteryUnrestricted,
                    OnboardingStepId.SleepingApps,
                    OnboardingStepId.ApiKeyAndModel,
                ).inOrder()
            assertThat(state.steps.map { it.done }).containsExactly(false, false, false, false, false)
            assertThat(state.allDone).isFalse()
        }

    @Test
    fun `service enabled in settings means restricted settings were allowed`() =
        runTest {
            probe.enabled = true

            assertThat(status(OnboardingStepId.RestrictedSettings)).isEqualTo(StepStatus.Done)
        }

    @Test
    fun `service enabled but not connected is reported as not running`() =
        runTest {
            probe.enabled = true

            assertThat(status(OnboardingStepId.AccessibilityService)).isEqualTo(StepStatus.EnabledNotRunning)
        }

    @Test
    fun `service enabled and connected is done`() =
        runTest {
            probe.enabled = true
            probe.connected = true

            assertThat(status(OnboardingStepId.AccessibilityService)).isEqualTo(StepStatus.Done)
        }

    @Test
    fun `battery is done when ignoring optimizations`() =
        runTest {
            probe.unrestricted = true

            assertThat(status(OnboardingStepId.BatteryUnrestricted)).isEqualTo(StepStatus.Done)
        }

    @Test
    fun `sleeping apps is a manual step done only after confirmation`() =
        runTest {
            assertThat(status(OnboardingStepId.SleepingApps)).isEqualTo(StepStatus.NotConfirmed)

            manual.confirmed = true

            assertThat(status(OnboardingStepId.SleepingApps)).isEqualTo(StepStatus.Done)
        }

    @Test
    fun `api key alone is not enough without a model`() =
        runTest {
            keys.set("sk-test")
            profiles.upsert(profile.copy(model = "  "))

            assertThat(status(OnboardingStepId.ApiKeyAndModel)).isEqualTo(StepStatus.NotDone)
        }

    @Test
    fun `the model comes from the active profile`() =
        runTest {
            keys.set("sk-test")
            profiles.upsert(ProviderProfile("q", "Empty", ApiFormat.OpenAi, ReplylySettings.DEFAULT_ENDPOINT, ""))
            profiles.setActive("q")

            assertThat(status(OnboardingStepId.ApiKeyAndModel)).isEqualTo(StepStatus.NotDone)

            profiles.setActive("p")

            assertThat(status(OnboardingStepId.ApiKeyAndModel)).isEqualTo(StepStatus.Done)
        }

    @Test
    fun `no profile at all is not done`() =
        runTest {
            keys.set("sk-test")
            val checker = OnboardingChecker(probe, manual, keys, InMemoryProfileRepository(emptyList()))

            assertThat(checker.check().step(OnboardingStepId.ApiKeyAndModel).status).isEqualTo(StepStatus.NotDone)
        }

    @Test
    fun `api key and model make the last step done`() =
        runTest {
            keys.set("sk-test")

            assertThat(status(OnboardingStepId.ApiKeyAndModel)).isEqualTo(StepStatus.Done)
        }

    @Test
    fun `all done when every step is done`() =
        runTest {
            probe.enabled = true
            probe.connected = true
            probe.unrestricted = true
            manual.confirmed = true
            keys.set("sk-test")

            assertThat(checker.check().allDone).isTrue()
        }

    private class FakeProbe : SetupProbe {
        var enabled = false
        var connected = false
        var unrestricted = false

        override fun isAccessibilityServiceEnabled() = enabled

        override fun isAccessibilityServiceConnected() = connected

        override fun isIgnoringBatteryOptimizations() = unrestricted
    }

    private class FakeManualSteps : ManualStepStore {
        var confirmed = false

        override fun isSleepingAppsConfirmed() = this.confirmed

        override fun setSleepingAppsConfirmed(confirmed: Boolean) {
            this.confirmed = confirmed
        }
    }
}
