package com.paragrudani.replyly.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.paragrudani.replyly.appGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Owned by ticket #2. Hidden: reached by long-pressing the settings title.
// How Parag uses it: docs/PARSER_FIXTURES.md.

@Serializable
object DebugRoute

fun NavGraphBuilder.debugDestination(onBack: () -> Unit) {
    composable<DebugRoute> { DebugScreen(onBack = onBack) }
}

@Composable
fun DebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = viewModel { DebugViewModel(context.appGraph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    // Coming back from the chat after a delayed capture: show the new dump.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    fun withJson(
        name: String,
        action: suspend (fileName: String, json: String) -> Unit,
    ) {
        scope.launch {
            val json = runCatching { vm.exportText(name) }.getOrNull()
            if (json == null) {
                vm.report("Could not read $name.")
                return@launch
            }
            val fileName = if (ui.scramble) name.replace(".json", "-scrambled.json") else name
            action(fileName, json)
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Debug: screen dumps",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onBack) { Text("Back") }
                }
            }
            item {
                ServiceCard(
                    serviceOn = ui.serviceOn,
                    onOpenSettings = { DumpActions.openAccessibilitySettings(context) },
                )
            }
            item {
                CaptureCard(
                    serviceOn = ui.serviceOn,
                    countdown = ui.countdown,
                    status = ui.status,
                    onCapture = vm::startCapture,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Scramble text when sharing", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Letters become random letters (same case, same length). Digits, emoji, " +
                                "punctuation, view ids and times stay. Phone numbers are digits: check them.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = ui.scramble, onCheckedChange = vm::setScramble)
                }
            }
            item { Text("Saved dumps (${ui.dumps.size})", style = MaterialTheme.typography.titleMedium) }
            if (ui.dumps.isEmpty()) {
                item { Text("None yet.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(ui.dumps, key = { it.entry.name }) { item ->
                DumpCard(
                    item = item,
                    onShare = {
                        withJson(item.entry.name) { fileName, json ->
                            if (DumpActions.fitsAsText(json)) {
                                DumpActions.share(context, fileName, json)
                            } else {
                                vm.report("Too large to share as text: use Save to Downloads.")
                            }
                        }
                    },
                    onCopy = {
                        withJson(item.entry.name) { fileName, json ->
                            if (DumpActions.fitsAsText(json)) {
                                DumpActions.copy(context, fileName, json, sensitive = !ui.scramble)
                                vm.report("Copied $fileName.")
                            } else {
                                vm.report("Too large to copy: use Save to Downloads.")
                            }
                        }
                    },
                    onSave = {
                        withJson(item.entry.name) { fileName, json ->
                            val ok =
                                withContext(Dispatchers.IO) { DumpActions.saveToDownloads(context, fileName, json) }
                            vm.report(
                                if (ok) "Saved to Downloads/Replyly/$fileName." else "Could not save to Downloads.",
                            )
                        }
                    },
                    onDelete = { confirmDelete = item.entry.name },
                )
            }
        }
    }

    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this dump?") },
            text = { Text(name) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(name)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServiceCard(
    serviceOn: Boolean,
    onOpenSettings: () -> Unit,
) {
    val colors =
        if (serviceOn) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (serviceOn) {
                Text("Accessibility service is running.", style = MaterialTheme.typography.titleSmall)
            } else {
                Text("Accessibility service is NOT enabled.", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Dumps read the screen through it. Turn on Replyly in Settings > Accessibility > " +
                        "Installed apps, then come back.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onOpenSettings) { Text("Open accessibility settings") }
            }
        }
    }
}

@Composable
private fun CaptureCard(
    serviceOn: Boolean,
    countdown: Int?,
    status: String?,
    onCapture: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Tap the button, then switch to the chat (recent apps) within " +
                    "${DebugViewModel.CAPTURE_DELAY_SECONDS} seconds and leave it on screen. " +
                    "A toast says when the dump is saved.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onCapture, enabled = serviceOn && countdown == null) {
                Text(
                    if (countdown ==
                        null
                    ) {
                        "Dump in ${DebugViewModel.CAPTURE_DELAY_SECONDS} s"
                    } else {
                        "Switch to the chat now… $countdown"
                    },
                )
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("d MMM HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
private fun DumpCard(
    item: DumpItem,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable(item.entry.name) { mutableStateOf(false) }
    val lines = if (expanded) item.summary else item.summary.take(COLLAPSED_LINES)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${item.entry.packageName} · ${timeFormat.format(
                    item.entry.capturedAt,
                )} · ${item.entry.sizeBytes / 1024} KB",
                style = MaterialTheme.typography.titleSmall,
            )
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            if (item.summary.size > COLLAPSED_LINES) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show less" else "Show all ${item.summary.size} lines")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShare) { Text("Share") }
                OutlinedButton(onClick = onCopy) { Text("Copy") }
                OutlinedButton(onClick = onSave) { Text("Save") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private const val COLLAPSED_LINES = 12
