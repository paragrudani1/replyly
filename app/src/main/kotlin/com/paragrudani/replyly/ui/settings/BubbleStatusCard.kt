package com.paragrudani.replyly.ui.settings

import android.text.format.DateFormat
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paragrudani.replyly.di.AppGraph
import com.paragrudani.replyly.snooze.SnoozeEndReason
import com.paragrudani.replyly.snooze.SnoozeText
import com.paragrudani.replyly.ui.theme.Spacing
import kotlinx.coroutines.delay
import java.time.Clock

/** What the status card at the top of Settings shows (#56); `null` from [bubbleStatus]: no card. */
sealed interface BubbleStatus {
    /** "Show bubble" is off (#55's switch). */
    data object Off : BubbleStatus

    /** Snoozed until [untilMillis] ([Long.MAX_VALUE]: until turned back on). */
    data class Snoozed(
        val untilMillis: Long,
    ) : BubbleStatus
}

/** Off wins over a snooze; an ended snooze ([activeUntil] `null`) shows nothing. */
fun bubbleStatus(
    show: Boolean,
    activeUntil: Long?,
): BubbleStatus? =
    when {
        !show -> BubbleStatus.Off
        activeUntil != null -> BubbleStatus.Snoozed(activeUntil)
        else -> null
    }

/**
 * The snooze as Settings sees it (#56): the stored until-time, "End snooze now", and the clock
 * and 12/24-hour format for the end time. The default ([NONE]) is never snoozed.
 */
class BubbleSnoozeInput(
    val until: Long?,
    val onEnd: () -> Unit,
    val clock: Clock = Clock.systemDefaultZone(),
    val is24Hour: Boolean = false,
) {
    companion object {
        val NONE = BubbleSnoozeInput(until = null, onEnd = {})
    }
}

/**
 * [until] while it is still in the future, else `null`; turns `null` by itself when the time
 * comes, so the card goes away without a store change (the service may not be running to end it).
 */
@Composable
fun rememberActiveSnooze(
    until: Long?,
    clock: Clock,
): Long? {
    var active by remember(until) { mutableStateOf(until?.takeIf { clock.millis() < it }) }
    LaunchedEffect(until) {
        val end = until ?: return@LaunchedEffect
        if (end == Long.MAX_VALUE) return@LaunchedEffect
        while (true) {
            val left = end - clock.millis()
            if (left <= 0) break
            delay(left)
        }
        active = null
    }
    return active
}

/**
 * The first item of Settings when the bubble is off or snoozed (#56): one line saying so, read
 * out as a polite live region, and one Material button that brings it back ("Turn back on" saves
 * the show-bubble setting; "End snooze now" ends the snooze). Replyly's own screen: nothing here
 * touches another app or calls the LLM.
 */
@Composable
fun BubbleStatusCard(
    status: BubbleStatus,
    snooze: BubbleSnoozeInput,
    onTurnBackOn: () -> Unit,
) {
    val (title, body, action, onAction) =
        when (status) {
            BubbleStatus.Off -> {
                StatusContent(
                    "Bubble is off",
                    "The Replyly button is hidden in every app.",
                    "Turn back on",
                    onTurnBackOn,
                )
            }

            is BubbleStatus.Snoozed -> {
                StatusContent(
                    SnoozeText.status(status.untilMillis, snooze.clock.zone, snooze.is24Hour),
                    "You can also shake your phone twice to bring it back.",
                    "End snooze now",
                    snooze.onEnd,
                )
            }
        }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(Spacing.L),
            verticalArrangement = Arrangement.spacedBy(Spacing.XS),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onAction,
                modifier = Modifier.align(Alignment.End).padding(top = Spacing.S),
            ) { Text(action) }
        }
    }
}

private data class StatusContent(
    val title: String,
    val body: String,
    val action: String,
    val onAction: () -> Unit,
)

/** The app's snooze store as [BubbleSnoozeInput] (#56); "End snooze now" logs the reason only. */
@Composable
fun rememberBubbleSnooze(graph: AppGraph): BubbleSnoozeInput {
    val until by graph.snoozeStore.until.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    return remember(until, is24Hour) {
        BubbleSnoozeInput(
            until = until,
            onEnd = {
                Log.d(TAG, "snooze end reason=${SnoozeEndReason.APP.name}")
                graph.snoozeStore.end()
            },
            clock = graph.clock,
            is24Hour = is24Hour,
        )
    }
}

private const val TAG = "ReplylySnooze"
