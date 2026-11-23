package com.paragrudani.replyly.ui.settings

import android.content.pm.PackageManager
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.paragrudani.replyly.R
import com.paragrudani.replyly.feedback.FeedbackEntry
import com.paragrudani.replyly.feedback.PromptVersionStats
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import com.paragrudani.replyly.ui.theme.Spacing
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Read-only timeline of the local feedback log, grouped by day, newest first. Each entry shows
 * the chat, the prompt version, the 3 suggestions (the inserted one highlighted) and the message
 * the user sent; "Show details" reveals the prompt-cache read count (Anthropic), the model's
 * analysis (prompt v2) and the prompt. Above the
 * timeline, [stats] shows the insert rate and edit amount per prompt version.
 * [error] is a fixed message shown when the encrypted log could not be read (for example
 * [SettingsViewModel.STORAGE_LOCKED]), with a "Try again" button that calls [onRetry].
 *
 * @param today names "Today" and "Yesterday"; the route passes [rememberToday] so it rolls over
 *   at midnight while the log is open.
 * @param use24Hour the phone's 24-hour clock setting.
 * @param appLabel the app name for a package id (falls back to the id).
 */
@Composable
fun FeedbackLogScreen(
    entries: List<FeedbackEntry>,
    error: String?,
    onClear: () -> Unit,
    onBack: () -> Unit,
    stats: List<PromptVersionStats> = emptyList(),
    onRetry: () -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
    use24Hour: Boolean = DateFormat.is24HourFormat(LocalContext.current),
    appLabel: (String) -> String = rememberAppLabel(),
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val days = remember(entries, zone, today) { groupFeedbackByDay(entries, zone, today) }
    val time = remember(use24Hour) { feedbackTimeFormatter(use24Hour) }
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.L, end = Spacing.L, bottom = Spacing.XL),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.S),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                    Text(
                        "Feedback log",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) { Text("Clear log") }
                }
            }
            if (error != null) {
                item {
                    EmptyState(
                        illustration = R.drawable.ill_empty_log,
                        // The snackbar text ends in "Try again."; here the button says it.
                        title = if (error == SettingsViewModel.STORAGE_LOCKED) "Secure storage is locked" else error,
                        body = "Unlock your phone, then tap Try again.",
                        action = { Button(onClick = onRetry) { Text("Try again") } },
                        modifier =
                            Modifier
                                .padding(top = Spacing.XXXL, start = Spacing.L, end = Spacing.L)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            } else if (stats.isNotEmpty() && entries.isNotEmpty()) {
                item(key = "stats") { QualityStatsCard(stats) }
            } else if (entries.isEmpty()) {
                item {
                    EmptyState(
                        illustration = R.drawable.ill_empty_log,
                        title = "No entries yet",
                        body = "Each set of suggestions you ask for is logged here, on this phone only.",
                        sparkle = true,
                        modifier = Modifier.padding(top = Spacing.XXXL, start = Spacing.L, end = Spacing.L),
                    )
                }
            }
            days.forEach { day ->
                item(key = "day-${day.date}") {
                    Text(
                        day.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .padding(
                                    top = Spacing.L,
                                    bottom = Spacing.S,
                                    start = Spacing.XS,
                                ).semantics { heading() },
                    )
                }
                items(day.entries, key = { it.id }) { entry ->
                    TimelineItem(
                        entry,
                        time.format(entry.createdAt.atZone(zone)),
                        appLabel(entry.chatKey.packageName) + entry.xModeSuffix(),
                        last = entry == day.entries.last(),
                    )
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the feedback log?") },
            text = { Text("Deletes every entry. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onClear()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** One entry: a dot on the day's rail, and the entry card next to it. */
@Composable
private fun TimelineItem(
    entry: FeedbackEntry,
    time: String,
    app: String,
    last: Boolean,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
        Column(Modifier.width(12.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(Spacing.L + Spacing.XS))
            Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            if (!last) {
                Box(
                    Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Box(Modifier.weight(1f).padding(bottom = Spacing.M)) { FeedbackEntryCard(entry, time, app) }
    }
}

@Composable
private fun FeedbackEntryCard(
    entry: FeedbackEntry,
    time: String,
    app: String,
) {
    var showPrompt by rememberSaveable(entry.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    Text(
                        entry.chatKey.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        time,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "$app · ${entry.model} · prompt v${entry.promptVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.suggestions.forEachIndexed { index, suggestion ->
                SuggestionLine(suggestion, inserted = entry.insertedIndex == index)
            }
            SentLine(entry)
            TextButton(
                onClick = { showPrompt = !showPrompt },
            ) { Text(if (showPrompt) "Hide details" else "Show details") }
            if (showPrompt) {
                cacheReadText(entry)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entry.analysis?.let { analysis ->
                    Text(
                        "Analysis (from the model, never shown in the panel)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        analysis.ifBlank { "(the model sent none)" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Prompt",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    entry.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Insert rate (panels with an inserted card / panels with suggestions) and the average edit amount
 * (how much of an inserted reply was changed before sending), one row per prompt version.
 */
@Composable
private fun QualityStatsCard(stats: List<PromptVersionStats>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.S),
    ) {
        Column(Modifier.padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            Text(
                "Suggestion quality",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            stats.forEach { version ->
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(versionLabel(version), style = MaterialTheme.typography.labelLarge)
                    Text(
                        insertRateText(version) + " · " + editAmountText(version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "Prompt v2", or "Prompt v1 (estimated)" when its panels were estimated (entries without a panel id). */
fun versionLabel(stats: PromptVersionStats): String =
    "Prompt v${stats.promptVersion}" + if (stats.panelsEstimated) " (estimated)" else ""

/** "Cache read: 1,024 tokens" for the entry details, or null when the provider did not report it. */
fun cacheReadText(entry: FeedbackEntry): String? =
    entry.cacheReadTokens?.let { "Cache read: ${String.format(Locale.ENGLISH, "%,d", it)} tokens" }

/** "Inserted in 7 of 10 panels (70%)". */
fun insertRateText(stats: PromptVersionStats): String =
    "Inserted in ${stats.panelsWithInsert} of ${stats.panels} panels" +
        (stats.insertRate?.let { " (${percent(it)})" } ?: "")

/** "edited 12% on average (5 sent)", or that no sent message is known yet. */
fun editAmountText(stats: PromptVersionStats): String =
    stats.averageEdit?.let { "edited ${percent(it)} on average (${stats.editsMeasured} sent)" }
        ?: "no sent messages seen yet"

private fun percent(fraction: Double): String = "${Math.round(fraction * 100)}%"

@Composable
private fun SuggestionLine(
    suggestion: Suggestion,
    inserted: Boolean,
) {
    val intent = ReplylyTheme.colors.forIntent(suggestion.intent)
    val scheme = MaterialTheme.colorScheme
    val container = if (inserted) scheme.primaryContainer else scheme.surfaceContainerLow
    val content = if (inserted) scheme.onPrimaryContainer else scheme.onSurface
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        modifier =
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                // Only the inserted one is announced; "Not inserted" on every other line is noise.
                if (inserted) stateDescription = "Inserted"
            },
    ) {
        Column(
            Modifier.padding(
                horizontal = Spacing.M,
                vertical = if (inserted) Spacing.S else Spacing.XXS,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.XS),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = intent.container,
                    contentColor = intent.content,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        suggestion.intent.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = Spacing.S, vertical = Spacing.XXS),
                    )
                }
                if (inserted) {
                    // Read once, through the stateDescription above.
                    Text(
                        "Inserted",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
            Text(suggestion.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SentLine(entry: FeedbackEntry) {
    val sent = entry.finalSentText
    if (sent == null) {
        Text(
            if (entry.insertedIndex == null) "Nothing inserted" else "Inserted; sent message not seen yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        },
        verticalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        Text(
            "You sent",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                sent,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Spacing.M, vertical = Spacing.S),
            )
        }
    }
}

/** "14:05" or "2:05 PM", following the phone's 24-hour setting rather than the locale default. */
fun feedbackTimeFormatter(
    use24Hour: Boolean,
    locale: Locale = Locale.getDefault(),
): DateTimeFormatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)

/** Time from [now] until just after the next local midnight in [zone] (DST-safe), at least 1 ms. */
fun durationUntilNextDay(
    now: Instant,
    zone: ZoneId,
): Duration {
    val nextDay =
        now
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
    return Duration.between(now, nextDay).plusMillis(1).coerceAtLeast(Duration.ofMillis(1))
}

/**
 * Today's date in [clock]'s zone, updated just after each midnight and on every resume (a
 * sleeping phone pauses the timer), so "Today"/"Yesterday" stay right while the log is open.
 */
@Composable
fun rememberToday(clock: Clock): State<LocalDate> {
    val today = remember(clock) { mutableStateOf(LocalDate.now(clock)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today.value = LocalDate.now(clock) }
    LaunchedEffect(clock) {
        while (true) {
            delay(durationUntilNextDay(clock.instant(), clock.zone).toMillis())
            today.value = LocalDate.now(clock)
        }
    }
    return today
}

/** The app's name for [packageName] as the launcher shows it, or the package id if it is not visible. */
fun appLabel(
    packageManager: PackageManager,
    packageName: String,
): String =
    try {
        packageManager
            .getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
            .toString()
            .ifBlank { packageName }
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

/** " reply" or " quote" after the app label for an X entry (#30 PR 2): "X reply". */
internal fun FeedbackEntry.xModeSuffix(): String = xMode?.let { " ${it.name.lowercase()}" }.orEmpty()

/** [appLabel] bound to this context's PackageManager, cached per package for the screen's life. */
@Composable
fun rememberAppLabel(): (String) -> String {
    val packageManager = LocalContext.current.packageManager
    return remember(packageManager) {
        val cache = HashMap<String, String>()
        val lookup: (String) -> String = { pkg -> cache.getOrPut(pkg) { appLabel(packageManager, pkg) } }
        lookup
    }
}
