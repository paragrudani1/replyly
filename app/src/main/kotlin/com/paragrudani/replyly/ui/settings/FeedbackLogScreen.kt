package com.paragrudani.replyly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.feedback.FeedbackEntry
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/** Read-only view of the local feedback log, newest first. */
@Composable
fun FeedbackLogScreen(
    entries: List<FeedbackEntry>,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("Back") }
                    Text("Feedback log", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) { Text("Clear log") }
                }
            }
            if (entries.isEmpty()) {
                item { Text("No entries yet. Each set of suggestions you ask for is logged here, on this phone only.") }
            }
            items(entries, key = { it.id }) { FeedbackEntryCard(it) }
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

@Composable
private fun FeedbackEntryCard(entry: FeedbackEntry) {
    var showPrompt by rememberSaveable(entry.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                dateFormat.format(entry.createdAt.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(entry.chatKey.title, style = MaterialTheme.typography.titleMedium)
            Text("${entry.chatKey.packageName} · ${entry.model}", style = MaterialTheme.typography.bodySmall)
            entry.suggestions.forEachIndexed { index, suggestion ->
                val inserted = entry.insertedIndex == index
                Text(
                    (if (inserted) "✓ " else "${index + 1}. ") + "[${suggestion.intent}] ${suggestion.text}",
                    fontWeight = if (inserted) FontWeight.Bold else FontWeight.Normal,
                )
            }
            Text(
                when {
                    entry.insertedIndex == null -> "Nothing inserted"
                    entry.finalSentText == null -> "Inserted #${entry.insertedIndex + 1}; sent message not seen yet"
                    else -> "Sent: ${entry.finalSentText}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            TextButton(
                onClick = { showPrompt = !showPrompt },
            ) { Text(if (showPrompt) "Hide prompt" else "Show prompt") }
            if (showPrompt) Text(entry.prompt, style = MaterialTheme.typography.bodySmall)
        }
    }
}
