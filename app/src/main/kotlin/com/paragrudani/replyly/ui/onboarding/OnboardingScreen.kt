package com.paragrudani.replyly.ui.onboarding

import android.content.Context
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.paragrudani.replyly.appGraph
import com.paragrudani.replyly.onboarding.OnboardingState
import com.paragrudani.replyly.onboarding.OnboardingStep
import com.paragrudani.replyly.onboarding.OnboardingStepId
import com.paragrudani.replyly.onboarding.SetupIntents
import com.paragrudani.replyly.onboarding.StepStatus
import com.paragrudani.replyly.onboarding.onboardingChecker
import com.paragrudani.replyly.ui.settings.SettingsRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext

// Owned by ticket #7.

@Serializable
object OnboardingRoute

/**
 * [onFinished]: Done and "Skip for now" leave onboarding for Settings. [onOpenApiKeySettings]:
 * step 5 opens Settings on top, so Back returns to this list.
 */
fun NavGraphBuilder.onboardingDestination(
    onFinished: () -> Unit,
    onOpenApiKeySettings: () -> Unit,
) {
    composable<OnboardingRoute> {
        OnboardingScreen(onFinished = onFinished, onOpenApiKeySettings = onOpenApiKeySettings)
    }
}

/**
 * Where the app opens: onboarding until every step is done, settings after that. Returns null
 * until the check has finished; the caller builds its NavHost only after that, so the start
 * destination never changes under a live NavHost. The check runs on IO, never on the main thread,
 * and the result is saved, so rotation neither re-checks nor flips the start screen.
 */
@Composable
fun rememberOnboardingStartDestination(): Any? {
    val app = LocalContext.current.applicationContext
    var allDone by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        if (allDone == null) {
            allDone = isOnboardingDone { onboardingChecker(app).check().allDone }
        }
    }
    return allDone?.let { if (it) SettingsRoute else OnboardingRoute }
}

/**
 * True only when [allDone] reports true within [timeoutMillis]. Not done, a failed check or a
 * timeout all mean onboarding, which is harmless: the user just taps "Skip for now".
 * The timeout stops waiting at suspension points only; a store that blocks still holds the IO
 * thread, never the main thread.
 */
internal suspend fun isOnboardingDone(
    timeoutMillis: Long = START_CHECK_TIMEOUT_MS,
    ioContext: CoroutineContext = Dispatchers.IO,
    allDone: suspend () -> Boolean,
): Boolean =
    withTimeoutOrNull(timeoutMillis) {
        try {
            withContext(ioContext) { allDone() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    } ?: false

internal const val START_CHECK_TIMEOUT_MS = 2_000L

/** [onFinished] leaves onboarding for Settings; [onOpenApiKeySettings] opens Settings for step 5. */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onOpenApiKeySettings: () -> Unit,
) {
    val context = LocalContext.current
    val checker = remember { onboardingChecker(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<OnboardingState?>(null) }
    val refreshJob = remember { JobSlot() }
    // A newer check always wins: an older one still running must not overwrite its result.
    val refresh: () -> Unit = {
        refreshJob.job?.cancel()
        refreshJob.job = scope.launch { state = checker.check() }
    }

    // Re-check whenever the user comes back from a system screen or from Settings.
    LifecycleResumeEffect(checker) {
        refresh()
        onPauseOrDispose {}
    }

    // The service often connects after the user is already back here: re-check when it does.
    LaunchedEffect(Unit) {
        context.appGraph.accessibilityHostHolder.host
            .drop(1)
            .collect { refresh() }
    }

    OnboardingContent(
        state = state,
        onOpen = { step -> openStep(context, step, onOpenApiKeySettings) },
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
            // The context is MainActivity: no NEW_TASK, so Back from the system screen returns here.
            context.startActivity(intent)
        }
    if (!launched) {
        val message = "Could not open that screen. Use the path in the instructions."
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

private class JobSlot {
    var job: Job? = null
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
