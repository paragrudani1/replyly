package com.paragrudani.replyly.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.paragrudani.replyly.appGraph
import com.paragrudani.replyly.cost.CostStatus
import com.paragrudani.replyly.ui.settings.profiles.ProviderProfilesSection
import com.paragrudani.replyly.ui.theme.ReplylyWordmark
import com.paragrudani.replyly.ui.theme.Spacing
import kotlinx.serialization.Serializable
import java.text.NumberFormat

// Owned by ticket #6.

@Serializable
object SettingsRoute

/** Quick picks for the model field (spec 6 shortlist); any other id can be typed. */
val MODEL_SHORTLIST = listOf("anthropic/claude-haiku-5.5", "google/gemini-3.8-flash", "~openai/gpt-mini-latest")

fun NavGraphBuilder.settingsDestination(
    onOpenOnboarding: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    composable<SettingsRoute> { SettingsRouteContent(onOpenOnboarding = onOpenOnboarding, onOpenDebug = onOpenDebug) }
}

@Composable
private fun SettingsRouteContent(
    onOpenOnboarding: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val graph = LocalContext.current.appGraph
    val vm: SettingsViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            settingsRepository = graph.settingsRepository,
                            apiKeyStore = graph.apiKeyStore,
                            costGuard = graph.costGuard,
                            feedbackLog = graph.feedbackLog,
                            clock = graph.clock,
                        )
                    }
                },
        )
    // The feedback log is an inner screen of this destination, so ReplylyNavHost stays untouched.
    var showFeedbackLog by rememberSaveable { mutableStateOf(false) }
    if (showFeedbackLog) {
        val closeFeedbackLog = {
            vm.dismissStorageError() // already shown on the log screen; do not repeat it as a snackbar
            showFeedbackLog = false
        }
        BackHandler(onBack = closeFeedbackLog)
        val entries by vm.feedbackEntries.collectAsStateWithLifecycle(initialValue = emptyList())
        val state by vm.state.collectAsStateWithLifecycle()
        FeedbackLogScreen(
            entries = entries,
            error = state.storageError,
            onClear = vm::clearFeedbackLog,
            onBack = closeFeedbackLog,
        )
    } else {
        SettingsScreen(vm, onOpenOnboarding, onOpenDebug, onOpenFeedbackLog = { showFeedbackLog = true })
    }
}

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onOpenOnboarding: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenFeedbackLog: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshUsage() }
    val snackbarHostState = remember { SnackbarHostState() }
    state.saveError?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            vm.dismissSaveError()
        }
    }
    state.storageError?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            vm.dismissStorageError()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.dirty) {
                SaveBar(onDiscard = vm::discardChanges, onSave = vm::save, hasErrors = state.errors.isNotEmpty())
            }
        },
    ) { padding ->
        val draft = state.draft
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Title(onOpenDebug) }
            item { UsageCard(state.usage) }
            if (draft == null) {
                item { Text("Loading…") }
                return@LazyColumn
            }
            item { SectionHeader("LLM") }
            // Issue #18: endpoint, key and model now live in provider profiles (ui/settings/profiles/).
            item { ProviderProfilesSection() }
            item {
                SwitchRow(
                    label = "3 parallel calls (faster on slow models)",
                    supporting = "One call per reply instead of one for all 3. Still counts as 1 request.",
                    checked = draft.useParallelCalls,
                    onCheckedChange = { v -> vm.editDraft { it.copy(useParallelCalls = v) } },
                )
            }
            item { SectionHeader("Appearance") }
            item {
                // Polish #0: a plain switch; the settings redesign restyles it.
                SwitchRow(
                    label = "Material You colours",
                    supporting = "Use colours from your wallpaper instead of Replyly blue.",
                    checked = state.useDynamicColor,
                    onCheckedChange = vm::setDynamicColor,
                )
            }
            item { SectionHeader("Prompt") }
            item {
                ValidatedField(
                    label = "History messages in the prompt (N)",
                    value = draft.historyMessageCount,
                    error = state.errors[SettingsField.HistoryMessageCount],
                    onValueChange = { v -> vm.editDraft { it.copy(historyMessageCount = v) } },
                    keyboardType = KeyboardType.Number,
                )
            }
            item {
                ValidatedField(
                    label = "About me",
                    value = draft.aboutMe,
                    error = state.errors[SettingsField.AboutMe],
                    onValueChange = { v -> vm.editDraft { it.copy(aboutMe = v) } },
                    singleLine = false,
                    supporting =
                        "${draft.aboutMe.trim().length} / ${SettingsValidator.MAX_ABOUT_ME}. " +
                            "Who you are and how you write.",
                    capitalization = KeyboardCapitalization.Sentences,
                )
            }
            item { SectionHeader("Limits and privacy") }
            item {
                ValidatedField(
                    label = "Daily request limit",
                    value = draft.dailyRequestLimit,
                    error = state.errors[SettingsField.DailyRequestLimit],
                    onValueChange = { v -> vm.editDraft { it.copy(dailyRequestLimit = v) } },
                    keyboardType = KeyboardType.Number,
                )
            }
            item {
                ValidatedField(
                    label = "Keep history and feedback log (days)",
                    value = draft.retentionDays,
                    error = state.errors[SettingsField.RetentionDays],
                    onValueChange = { v -> vm.editDraft { it.copy(retentionDays = v) } },
                    keyboardType = KeyboardType.Number,
                )
            }
            item {
                BlocklistSection(
                    title = "Blocked apps",
                    hint = "Replyly never reads these apps. Package name, e.g. com.google.android.apps.messaging",
                    placeholder = "com.example.app",
                    entries = draft.blockedPackages,
                    onAdd = vm::addBlockedPackage,
                    onRemove = vm::removeBlockedPackage,
                )
            }
            item {
                BlocklistSection(
                    title = "Blocked chats",
                    hint = "Replyly never reads these chats. Format package:chat name, e.g. com.whatsapp:Bank OTP",
                    placeholder = "com.whatsapp:Chat name",
                    entries = draft.blockedChatKeys,
                    onAdd = vm::addBlockedChat,
                    onRemove = vm::removeBlockedChat,
                )
            }
            item { HorizontalDivider() }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onOpenFeedbackLog) { Text("Feedback log") }
                    OutlinedButton(onClick = onOpenOnboarding) { Text("Setup steps") }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodySmall)
        }
        // The row handles the toggle, so the whole line is one touch target and one a11y node.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Title(onOpenDebug: () -> Unit) {
    // Hidden entry to the debug screen: long-press the title (wordmark and heading together).
    Column(
        modifier =
            Modifier
                .testTag(SETTINGS_TITLE_TAG)
                .combinedClickable(onClick = {}, onLongClick = onOpenDebug)
                .semantics(mergeDescendants = true) { heading() },
        verticalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        ReplylyWordmark(style = MaterialTheme.typography.titleLarge)
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
    }
}

internal const val SETTINGS_TITLE_TAG = "settings_title"

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun UsageCard(usage: CostStatus) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Usage", style = MaterialTheme.typography.titleMedium)
            Text(
                "Requests today: ${numbers.format(usage.requestsToday)} / ${numbers.format(usage.dailyRequestLimit)}",
                color = if (usage.limitReached) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            if (usage.limitReached) {
                Text("Daily limit reached. It resets at midnight.", color = MaterialTheme.colorScheme.error)
            }
            Text("Tokens this month: ${numbers.format(usage.totalTokensThisMonth)}")
            Text(
                "Prompt ${numbers.format(usage.promptTokensThisMonth)} · " +
                    "completion ${numbers.format(usage.completionTokensThisMonth)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ValidatedField(
    label: String,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    supporting: String? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = keyboardType,
                capitalization = capitalization,
                autoCorrectEnabled = !singleLine,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BlocklistSection(
    title: String,
    hint: String,
    placeholder: String,
    entries: Set<String>,
    onAdd: (String) -> String?,
    onRemove: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(hint, style = MaterialTheme.typography.bodySmall)
        if (entries.isEmpty()) Text("None", style = MaterialTheme.typography.bodyMedium)
        entries.sorted().forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemove(entry) }) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                placeholder = { Text(placeholder) },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                error = onAdd(input)
                if (error == null) input = ""
            }, enabled = input.isNotBlank()) { Text("Block") }
        }
    }
}

@Composable
private fun SaveBar(
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    hasErrors: Boolean,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (hasErrors) "Fix the marked fields" else "Unsaved changes",
                color = if (hasErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDiscard) { Text("Discard") }
            Button(onClick = onSave) { Text("Save") }
        }
    }
}
