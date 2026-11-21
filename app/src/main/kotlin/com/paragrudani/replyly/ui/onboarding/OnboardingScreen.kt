package com.paragrudani.replyly.ui.onboarding

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.paragrudani.replyly.onboarding.OnboardingState
import com.paragrudani.replyly.onboarding.OnboardingStep
import com.paragrudani.replyly.onboarding.OnboardingStepId
import com.paragrudani.replyly.onboarding.SetupIntents
import com.paragrudani.replyly.onboarding.StepStatus
import com.paragrudani.replyly.onboarding.onboardingChecker
import com.paragrudani.replyly.ui.settings.SettingsRoute
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

// Owned by ticket #7.

@Serializable
object OnboardingRoute

fun NavGraphBuilder.onboardingDestination(onFinished: () -> Unit) {
    composable<OnboardingRoute> { OnboardingScreen(onFinished = onFinished) }
}

/**
 * Where the app opens: onboarding until every step is done, settings after that. Computed once
 * per NavHost (the start destination must not change under a live NavHost). The check is a few
 * system lookups plus two small store reads, so it runs before the first frame; if the stores
 * are slow it gives up after [START_CHECK_TIMEOUT_MS] and opens onboarding, which is harmless.
 */
@Composable
fun onboardingStartDestination(): Any {
    val context = LocalContext.current
    return remember {
        val allDone =
            runBlocking { withTimeoutOrNull(START_CHECK_TIMEOUT_MS) { onboardingChecker(context).check().allDone } }
        if (allDone == true) SettingsRoute else OnboardingRoute
    }
}

private const val START_CHECK_TIMEOUT_MS = 500L

/** [onFinished] goes to the Settings route; step 5 uses it too, to enter the API key. */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val checker = remember { onboardingChecker(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<OnboardingState?>(null) }
    val refresh: () -> Unit = { scope.launch { state = checker.check() } }

    // Re-check whenever the user comes back from a system screen or from Settings.
    LifecycleResumeEffect(checker) {
        refresh()
        onPauseOrDispose {}
    }

    OnboardingContent(
        state = state,
        onOpen = { step -> openStep(context, step, onFinished) },
        onConfirmSleepingApps = { confirmed ->
            checker.setSleepingAppsConfirmed(confirmed)
            refresh()
        },
        onFinished = onFinished,
    )
}

private fun openStep(
    context: Context,
    step: OnboardingStepId,
    openSettings: () -> Unit,
) {
    if (step == OnboardingStepId.ApiKeyAndModel) {
        openSettings()
        return
    }
    val launched =
        SetupIntents.launchFirst(SetupIntents.forStep(step, context.packageName)) { intent ->
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    if (!launched) {
        val message = "Could not open that screen. Use the path in the instructions."
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun OnboardingContent(
    state: OnboardingState?,
    onOpen: (OnboardingStepId) -> Unit,
    onConfirmSleepingApps: (Boolean) -> Unit,
    onFinished: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Set up Replyly", style = MaterialTheme.typography.headlineMedium)
            Text("Do these steps in order. Each one turns green once Android reports it as done.")
            state?.steps?.forEachIndexed { index, step ->
                StepCard(
                    number = index + 1,
                    step = step,
                    onOpen = { onOpen(step.id) },
                    onConfirm = onConfirmSleepingApps,
                )
            }
            if (state?.allDone == true) {
                Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) { Text("Done: go to settings") }
            } else {
                TextButton(onClick = onFinished, modifier = Modifier.align(Alignment.End)) { Text("Skip for now") }
            }
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    step: OnboardingStep,
    onOpen: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    val copy = stepCopy(step.id)
    val colors =
        if (step.done) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$number. ${copy.title}", style = MaterialTheme.typography.titleMedium)
            Text(statusLabel(step), style = MaterialTheme.typography.labelLarge)
            if (!step.done || !step.verifiable) Text(copy.instruction, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!step.done) Button(onClick = onOpen) { Text(copy.button) }
                if (step.id == OnboardingStepId.SleepingApps) {
                    if (step.done) {
                        TextButton(onClick = { onConfirm(false) }) { Text("Undo") }
                    } else {
                        OutlinedButton(onClick = { onConfirm(true) }) { Text("I've done this") }
                    }
                }
            }
        }
    }
}

private fun statusLabel(step: OnboardingStep): String =
    when (step.status) {
        StepStatus.Done -> if (step.verifiable) "✓ Done" else "✓ Done (you confirmed it)"
        StepStatus.NotDone -> "Not done"
        StepStatus.EnabledNotRunning -> "Turned on but not running: turn it off and on again"
        StepStatus.NotConfirmed -> "Not confirmed (Replyly cannot check this one)"
    }

private class StepCopy(
    val title: String,
    val instruction: String,
    val button: String,
)

private fun stepCopy(id: OnboardingStepId): StepCopy =
    when (id) {
        OnboardingStepId.RestrictedSettings -> {
            StepCopy(
                title = "Allow restricted settings",
                instruction =
                    "Android blocks accessibility for apps installed from an APK. First try step 2 once " +
                        "(the switch is blocked), then come back: in App info tap ⋮ (top right) → " +
                        "\"Allow restricted settings\" and confirm.",
                button = "Open App info",
            )
        }

        OnboardingStepId.AccessibilityService -> {
            StepCopy(
                title = "Turn on the accessibility service",
                instruction = "Accessibility → Installed apps → Replyly → turn it on.",
                button = "Open Accessibility",
            )
        }

        OnboardingStepId.BatteryUnrestricted -> {
            StepCopy(
                title = "Battery: Unrestricted",
                instruction =
                    "Allow Replyly to run in the background, or in App info → Battery choose \"Unrestricted\".",
                button = "Allow",
            )
        }

        OnboardingStepId.SleepingApps -> {
            StepCopy(
                title = "Not in Sleeping apps",
                instruction =
                    "Battery → Background usage limits: make sure Replyly is not in \"Sleeping apps\" or " +
                        "\"Deep sleeping apps\" (add it to \"Never sleeping apps\"). Then tap \"I've done this\".",
                button = "Open battery settings",
            )
        }

        OnboardingStepId.ApiKeyAndModel -> {
            StepCopy(
                title = "API key and model",
                instruction = "Enter your OpenRouter API key and pick a model in Replyly settings.",
                button = "Open settings",
            )
        }
    }
