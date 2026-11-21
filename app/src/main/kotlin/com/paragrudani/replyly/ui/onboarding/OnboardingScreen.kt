package com.paragrudani.replyly.ui.onboarding

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.paragrudani.replyly.appGraph
import com.paragrudani.replyly.onboarding.OnboardingEntry
import com.paragrudani.replyly.onboarding.OnboardingState
import com.paragrudani.replyly.onboarding.OnboardingStepId
import com.paragrudani.replyly.onboarding.SetupIntents
import com.paragrudani.replyly.onboarding.WelcomeFlow
import com.paragrudani.replyly.onboarding.WelcomePage
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

/**
 * [fromSettings]: true when Settings opened it ("Setup steps"), so it starts on the checklist and
 * Back returns to Settings. The flag lives in the route because the graph's start destination
 * stays onboarding even after onboarding is popped; route arguments survive rotation and
 * process death.
 */
@Serializable
data class OnboardingRoute(
    val fromSettings: Boolean = false,
)

/**
 * [onFinished]: Done and "Skip for now" leave onboarding for Settings. [onOpenApiKeySettings]:
 * step 5 opens Settings on top, so Back returns to this list.
 *
 * On first run the welcome flow opens on its first page; opened from Settings ("Setup steps",
 * [OnboardingRoute.fromSettings]) it opens straight on the checklist.
 */
fun NavGraphBuilder.onboardingDestination(
    onFinished: () -> Unit,
    onOpenApiKeySettings: () -> Unit,
) {
    composable<OnboardingRoute> { entry ->
        OnboardingScreen(
            onFinished = onFinished,
            onOpenApiKeySettings = onOpenApiKeySettings,
            startPage = WelcomeFlow.startPage(entry.onboardingEntry()),
        )
    }
}

/** How this onboarding entry was reached, read from its route arguments. */
internal fun NavBackStackEntry.onboardingEntry(): OnboardingEntry =
    OnboardingEntry.of(fromSettings = toRoute<OnboardingRoute>().fromSettings)

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
    return allDone?.let { if (it) SettingsRoute else OnboardingRoute() }
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
    startPage: WelcomePage = WelcomePage.WhatItDoes,
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
        startPage = startPage,
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
