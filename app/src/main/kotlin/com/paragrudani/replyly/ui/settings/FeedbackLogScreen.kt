package com.paragrudani.replyly.ui.settings

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.R
import com.paragrudani.replyly.feedback.FeedbackEntry
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import com.paragrudani.replyly.ui.theme.Spacing
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Read-only timeline of the local feedback log, grouped by day, newest first. Each entry shows
 * the chat, the 3 suggestions (the inserted one highlighted) and the message the user sent.
 * [error] is a fixed message shown when the encrypted log could not be read (for example
 * [SettingsViewModel.STORAGE_LOCKED]).
 */
@Composable
fun FeedbackLogScreen(
    entries: List<FeedbackEntry>,
    error: String?,
    onClear: () -> Unit,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val days = remember(entries, zone, today) { groupFeedbackByDay(entries, zone, today) }
    val time = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
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
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier =
                            Modifier
                                .padding(Spacing.S)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
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
                    TimelineItem(entry, time.format(entry.createdAt.atZone(zone)), last = entry == day.entries.last())
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
        Box(Modifier.weight(1f).padding(bottom = Spacing.M)) { FeedbackEntryCard(entry, time) }
    }
}

@Composable
private fun FeedbackEntryCard(
    entry: FeedbackEntry,
    time: String,
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
                    "${entry.chatKey.packageName} · ${entry.model}",
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
            ) { Text(if (showPrompt) "Hide prompt" else "Show prompt") }
            if (showPrompt) {
                Text(
                    entry.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

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
                stateDescription = if (inserted) "Inserted" else "Not inserted"
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
                    Text("Inserted", style = MaterialTheme.typography.labelMedium)
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
