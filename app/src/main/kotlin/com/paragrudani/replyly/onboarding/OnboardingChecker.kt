package com.paragrudani.replyly.onboarding

import com.paragrudani.replyly.settings.ApiKeyStore
import com.paragrudani.replyly.settings.SettingsRepository
import com.paragrudani.replyly.settings.current
import kotlinx.coroutines.flow.first

/** The five S23 setup steps of spec section 7, in the order the user does them. */
enum class OnboardingStepId {
    /** App info ⋮ → "Allow restricted settings" (sideloaded apps). No API reads it: inferred. */
    RestrictedSettings,
    AccessibilityService,
    BatteryUnrestricted,

    /** Samsung Device care "Sleeping apps". Not detectable: the user confirms it. */
    SleepingApps,
    ApiKeyAndModel,
}

enum class StepStatus {
    Done,
    NotDone,

    /** Accessibility service is switched on but is not connected (crashed or killed). */
    EnabledNotRunning,

    /** A manual step the user has not confirmed yet. */
    NotConfirmed,
}

data class OnboardingStep(
    val id: OnboardingStepId,
    val status: StepStatus,
) {
    val done: Boolean get() = status == StepStatus.Done

    /** False for steps the app cannot detect and only knows from the user's confirmation. */
    val verifiable: Boolean get() = id != OnboardingStepId.SleepingApps
}

data class OnboardingState(
    val steps: List<OnboardingStep>,
) {
    val allDone: Boolean get() = steps.all { it.done }

    fun step(id: OnboardingStepId): OnboardingStep = steps.first { it.id == id }
}

/** What the system says about this app. Android implementation: [AndroidSetupProbe]. */
interface SetupProbe {
    /** This app's service is listed in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. */
    fun isAccessibilityServiceEnabled(): Boolean

    /** The service is actually running (attached to the AccessibilityHostHolder). */
    fun isAccessibilityServiceConnected(): Boolean

    fun isIgnoringBatteryOptimizations(): Boolean
}

/** User confirmations for steps that cannot be detected. Android implementation: [PrefsManualStepStore]. */
interface ManualStepStore {
    fun isSleepingAppsConfirmed(): Boolean

    fun setSleepingAppsConfirmed(confirmed: Boolean)
}

class OnboardingChecker(
    private val probe: SetupProbe,
    private val manualSteps: ManualStepStore,
    private val apiKeyStore: ApiKeyStore,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun check(): OnboardingState {
        val enabled = probe.isAccessibilityServiceEnabled()
        val accessibility =
            when {
                enabled && probe.isAccessibilityServiceConnected() -> StepStatus.Done
                enabled -> StepStatus.EnabledNotRunning
                else -> StepStatus.NotDone
            }
        val hasKey = apiKeyStore.hasKey.first()
        val hasModel = settingsRepository.current().model.isNotBlank()
        return OnboardingState(
            listOf(
                // Android only lets a sideloaded app's service be enabled after restricted
                // settings were allowed, so an enabled service proves this step.
                OnboardingStep(OnboardingStepId.RestrictedSettings, enabled.toStatus()),
                OnboardingStep(OnboardingStepId.AccessibilityService, accessibility),
                OnboardingStep(OnboardingStepId.BatteryUnrestricted, probe.isIgnoringBatteryOptimizations().toStatus()),
                OnboardingStep(
                    OnboardingStepId.SleepingApps,
                    if (manualSteps.isSleepingAppsConfirmed()) StepStatus.Done else StepStatus.NotConfirmed,
                ),
                OnboardingStep(OnboardingStepId.ApiKeyAndModel, (hasKey && hasModel).toStatus()),
            ),
        )
    }

    fun setSleepingAppsConfirmed(confirmed: Boolean) = manualSteps.setSleepingAppsConfirmed(confirmed)

    private fun Boolean.toStatus() = if (this) StepStatus.Done else StepStatus.NotDone
}
