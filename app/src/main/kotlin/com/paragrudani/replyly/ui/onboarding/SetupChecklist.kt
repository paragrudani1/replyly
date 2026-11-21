package com.paragrudani.replyly.ui.onboarding

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.R
import com.paragrudani.replyly.onboarding.OnboardingState
import com.paragrudani.replyly.onboarding.OnboardingStep
import com.paragrudani.replyly.onboarding.OnboardingStepId
import com.paragrudani.replyly.onboarding.StepStatus
import com.paragrudani.replyly.ui.theme.MotionDurations
import com.paragrudani.replyly.ui.theme.MotionEasing
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import com.paragrudani.replyly.ui.theme.Spacing

/** The last page: the five S23 steps, each ticking as Android reports it done. */
@Composable
internal fun SetupChecklistPage(
    state: OnboardingState?,
    onOpen: (OnboardingStepId) -> Unit,
    onConfirmSleepingApps: (Boolean) -> Unit,
    modifier: Modifier,
) {
    PageColumn(title = "Set up", modifier = modifier, horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.L)) {
            PageIllustration(R.drawable.ic_onboarding_setup, size = 64.dp)
            Column(Modifier.weight(1f)) {
                PageTitle("Set up your phone", textAlign = TextAlign.Start)
                if (state != null) {
                    val done = state.steps.count { it.done }
                    Text(
                        "$done of ${state.steps.size} done",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        Text(
            "Do these in order. Each step ticks once Android reports it as done.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state?.steps?.forEachIndexed { index, step ->
            StepCard(
                number = index + 1,
                step = step,
                onOpen = { onOpen(step.id) },
                onConfirm = onConfirmSleepingApps,
            )
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
    val motion = ReplylyTheme.motion
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.L).animateContentSize(motion.spring()),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            // One TalkBack stop for the step and its state ("Battery: done"), announced when it changes.
            Row(
                modifier =
                    Modifier.clearAndSetSemantics {
                        contentDescription = stepAnnouncement(step)
                        liveRegion = LiveRegionMode.Polite
                    },
                horizontalArrangement = Arrangement.spacedBy(Spacing.M),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepCheck(step)
                Column(Modifier.weight(1f)) {
                    Text("$number. ${copy.title}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        statusLabel(step),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            when (step.status) {
                                StepStatus.Done -> MaterialTheme.colorScheme.primary
                                StepStatus.EnabledNotRunning -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
            if (!step.done || !step.verifiable) {
                Text(
                    copy.instruction,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val hasActions = !step.done || step.id == OnboardingStepId.SleepingApps
            if (hasActions) {
                StepActions(step, copy.button, onOpen, onConfirm)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepActions(
    step: OnboardingStep,
    openLabel: String,
    onOpen: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        if (!step.done) Button(onClick = onOpen) { Text(openLabel) }
        if (step.id == OnboardingStepId.SleepingApps) {
            if (step.done) {
                TextButton(onClick = { onConfirm(false) }) { Text("Undo") }
            } else {
                OutlinedButton(onClick = { onConfirm(true) }) { Text("I've done this") }
            }
        }
    }
}

/** Progress of a step's tick, 0 (empty ring) to 1 (filled disc with the check drawn). For tests. */
internal val TickProgress = SemanticsPropertyKey<Float>("TickProgress")
private var SemanticsPropertyReceiver.tickProgress by TickProgress

internal fun tickTag(id: OnboardingStepId) = "tick_${id.name}"

/**
 * The step's check mark. A step done when the list first loads shows its tick at once; one that
 * becomes done while the screen is open (back from a system screen, the service connecting) fills
 * the disc and draws the check. "Remove animations" makes it appear at once.
 */
@Composable
private fun StepCheck(step: OnboardingStep) {
    val motion = ReplylyTheme.motion
    val target = if (step.done) 1f else 0f
    val progress = remember(step.id) { Animatable(target) }
    LaunchedEffect(target) { progress.animateTo(target, motion.tween(TICK_MILLIS, MotionEasing.Enter)) }

    val ring =
        if (step.status ==
            StepStatus.EnabledNotRunning
        ) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.outline
        }
    val fill = MaterialTheme.colorScheme.primary
    val check = MaterialTheme.colorScheme.onPrimary
    val value = progress.value
    val checkPath = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(
        Modifier
            .size(28.dp)
            .testTag(tickTag(step.id))
            .semantics { tickProgress = value },
    ) {
        val stroke = 2.dp.toPx()
        // Empty ring, fading out as the disc grows over it.
        drawCircle(ring.copy(alpha = 1f - value), radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
        // Disc: grows in the first 60 %, with a slight overshoot settled by the easing.
        val disc = (value / 0.6f).coerceIn(0f, 1f)
        if (disc > 0f) scale(0.6f + 0.4f * disc) { drawCircle(fill.copy(alpha = disc)) }
        // Check: drawn from 30 % to the end.
        val drawn = ((value - 0.3f) / 0.7f).coerceIn(0f, 1f)
        if (drawn > 0f) {
            val w = size.width
            val full =
                Path().apply {
                    moveTo(w * 0.28f, w * 0.52f)
                    lineTo(w * 0.44f, w * 0.67f)
                    lineTo(w * 0.73f, w * 0.36f)
                }
            measure.setPath(full, false)
            checkPath.reset()
            measure.getSegment(0f, measure.length * drawn, checkPath, true)
            drawPath(checkPath, check, style = Stroke(stroke * 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private const val TICK_MILLIS = MotionDurations.LONG + MotionDurations.MEDIUM

/** What TalkBack reads for a step: its short name and state, "Battery: done". */
internal fun stepAnnouncement(step: OnboardingStep): String {
    val state =
        when (step.status) {
            StepStatus.Done -> if (step.verifiable) "done" else "done, you confirmed it"
            StepStatus.NotDone -> "not done"
            StepStatus.EnabledNotRunning -> "turned on but not running"
            StepStatus.NotConfirmed -> "not confirmed"
        }
    return "${shortName(step.id)}: $state"
}

private fun shortName(id: OnboardingStepId): String =
    when (id) {
        OnboardingStepId.RestrictedSettings -> "Restricted settings"
        OnboardingStepId.AccessibilityService -> "Accessibility service"
        OnboardingStepId.BatteryUnrestricted -> "Battery"
        OnboardingStepId.SleepingApps -> "Sleeping apps"
        OnboardingStepId.ApiKeyAndModel -> "API key and model"
    }

private fun statusLabel(step: OnboardingStep): String =
    when (step.status) {
        StepStatus.Done -> if (step.verifiable) "Done" else "Done (you confirmed it)"
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
