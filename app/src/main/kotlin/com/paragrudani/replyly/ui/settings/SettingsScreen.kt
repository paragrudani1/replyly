package com.paragrudani.replyly.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.paragrudani.replyly.R
import com.paragrudani.replyly.appGraph
import com.paragrudani.replyly.ui.settings.profiles.ProviderProfilesSection
import com.paragrudani.replyly.ui.theme.Elevation
import com.paragrudani.replyly.ui.theme.HapticEvent
import com.paragrudani.replyly.ui.theme.MinTouchTarget
import com.paragrudani.replyly.ui.theme.ReplylyWordmark
import com.paragrudani.replyly.ui.theme.Spacing
import com.paragrudani.replyly.ui.theme.rememberReplylyHaptics
import kotlinx.serialization.Serializable

// Owned by ticket #6; restyled by the polish pass (#17, item 7).

@Serializable
object SettingsRoute

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
    val context = LocalContext.current
    val graph = context.appGraph
    val vm: SettingsViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            settingsRepository = graph.settingsRepository,
                            costGuard = graph.costGuard,
                            feedbackLog = graph.feedbackLog,
                            clock = graph.clock,
                        )
                    }
                },
        )
    val notes = stringArrayResource(R.array.whats_new_notes).toList()
    var showWhatsNew by rememberSaveable { mutableStateOf(false) }
    // Once per update: consume() records the version, so this is false on every later visit.
    LaunchedEffect(Unit) { if (whatsNewFor(context, hasNotes = notes.isNotEmpty()).consume()) showWhatsNew = true }

    // The feedback log is an inner screen of this destination, so ReplylyNavHost stays untouched.
    var showFeedbackLog by rememberSaveable { mutableStateOf(false) }
    if (showFeedbackLog) {
        val closeFeedbackLog = {
            vm.dismissStorageError() // already shown on the log screen; do not repeat it as a snackbar
            showFeedbackLog = false
        }
        BackHandler(onBack = closeFeedbackLog)
        // Bumping retry drops the old collection and subscribes again, so "Try again" re-reads the DB.
        var retry by rememberSaveable { mutableIntStateOf(0) }
        val entries by key(retry) { vm.feedbackEntries.collectAsStateWithLifecycle(initialValue = emptyList()) }
        val stats by key(retry) { vm.feedbackStats.collectAsStateWithLifecycle(initialValue = emptyList()) }
        val state by vm.state.collectAsStateWithLifecycle()
        val today by rememberToday(graph.clock)
        FeedbackLogScreen(
            entries = entries,
            error = state.storageError,
            onClear = vm::clearFeedbackLog,
            onBack = closeFeedbackLog,
            stats = stats,
            onRetry = {
                vm.dismissStorageError()
                retry++
            },
            zone = graph.clock.zone,
            today = today,
        )
    } else {
        SettingsScreen(
            vm,
            onOpenOnboarding,
            onOpenDebug,
            onOpenFeedbackLog = { showFeedbackLog = true },
            // Issue #18: endpoint, key and model live in provider profiles (ui/settings/profiles/).
            aiProvider = { SettingsGroupPadding { ProviderProfilesSection() } },
            onOpenWhatsNew = if (notes.isEmpty()) null else ({ showWhatsNew = true }),
        )
    }
    if (showWhatsNew && notes.isNotEmpty()) {
        WhatsNewSheet(
            title = stringResource(R.string.whats_new_title),
            notes = notes,
            onDismiss = { showWhatsNew = false },
        )
    }
}

/**
 * The settings screen, in sections: AI provider, Your style, Privacy, Limits and usage,
 * Appearance, About.
 *
 * @param aiProvider the content of the "AI provider" section.
 *   The route passes the provider profiles; tests pass a fixed-state list.
 * @param onOpenWhatsNew null hides the "What's new" row (no notes in this build).
 * @param appVersion shown under About; tests pass a fixed value so screenshots do not change
 *   with the CI build number.
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onOpenOnboarding: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenFeedbackLog: () -> Unit,
    aiProvider: @Composable ColumnScope.() -> Unit,
    onOpenWhatsNew: (() -> Unit)? = null,
    appVersion: String = rememberAppVersionName(),
    listState: LazyListState = rememberLazyListState(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshUsage() }
    val haptics = rememberReplylyHaptics()
    // Confirm only once the write succeeded (DESIGN.md > Haptics); a rotation does not replay it.
    val savesBefore = remember { state.savedCount }
    LaunchedEffect(state.savedCount) { if (state.savedCount > savesBefore) haptics.perform(HapticEvent.Save) }
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
                SaveBar(
                    onDiscard = vm::discardChanges,
                    onSave = vm::save,
                    hasErrors = state.errors.isNotEmpty(),
                )
            }
        },
    ) { padding ->
        val draft = state.draft
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            // 16, not 24: the grouped surfaces add their own 16 inset, so text sits 32 from the edge.
            contentPadding = PaddingValues(horizontal = Spacing.L, vertical = Spacing.L),
            verticalArrangement = Arrangement.spacedBy(Spacing.XL),
        ) {
            item { Title(onOpenDebug) }
            if (draft == null) {
                item { Text("Loading…") }
                return@LazyColumn
            }
            item(key = "ai") {
                SettingsSection("AI provider") {
                    aiProvider()
                }
            }
            item(key = "style") {
                SettingsSection("Your style") {
                    SettingsGroupPadding {
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
                        ValidatedField(
                            label = "Names people call me",
                            value = draft.myNames,
                            error = state.errors[SettingsField.MyNames],
                            onValueChange = { v -> vm.editDraft { it.copy(myNames = v) } },
                            supporting =
                                "Optional, comma-separated. Group messages that mention you are answered first.",
                            capitalization = KeyboardCapitalization.Words,
                        )
                        ValidatedField(
                            label = "History messages in the prompt (N)",
                            value = draft.historyMessageCount,
                            error = state.errors[SettingsField.HistoryMessageCount],
                            onValueChange = { v -> vm.editDraft { it.copy(historyMessageCount = v) } },
                            keyboardType = KeyboardType.Number,
                            supporting = "More gives better context and costs more tokens.",
                        )
                    }
                }
            }
            item(key = "x") {
                SettingsSection("On X") {
                    SettingsGroupPadding {
                        ValidatedField(
                            label = "My X handle",
                            value = draft.myXHandle,
                            error = state.errors[SettingsField.MyXHandle],
                            onValueChange = { v -> vm.editDraft { it.copy(myXHandle = v) } },
                            supporting = "Optional, e.g. @asha_dev. Marks your own posts when you reply on X.",
                        )
                        ValidatedField(
                            label = "My X voice",
                            value = draft.myXVoice,
                            error = state.errors[SettingsField.MyXVoice],
                            onValueChange = { v -> vm.editDraft { it.copy(myXVoice = v) } },
                            singleLine = false,
                            supporting =
                                "${draft.myXVoice.trim().length} / ${SettingsValidator.MAX_MY_X_VOICE}. " +
                                    "Topics, tone, emoji and hashtag habits for your X replies.",
                            capitalization = KeyboardCapitalization.Sentences,
                        )
                    }
                    SettingsSwitchRow(
                        label = "Longer X posts",
                        supporting = "Up to 500 characters instead of 280. Turn on only with X Premium.",
                        checked = draft.xPostMedium,
                        onCheckedChange = { on -> vm.editDraft { it.copy(xPostMedium = on) } },
                        haptic = true,
                    )
                }
            }
            item(key = "privacy") {
                SettingsSection("Privacy") {
                    BlocklistBlock(
                        title = "Blocked apps",
                        hint = "Replyly never reads these apps. Package name, e.g. com.google.android.apps.messaging",
                        emptyText = "No apps blocked yet",
                        emptyBody = "Banking apps, password fields and secure screens are always skipped.",
                        placeholder = "com.example.app",
                        entries = draft.blockedPackages,
                        onAdd = vm::addBlockedPackage,
                        onRemove = vm::removeBlockedPackage,
                    )
                    SettingsDivider()
                    BlocklistBlock(
                        title = "Blocked chats",
                        hint = "Replyly never reads these chats. Format package:chat name, e.g. com.whatsapp:Bank OTP",
                        emptyText = "No chats blocked yet",
                        placeholder = "com.whatsapp:Mom",
                        entries = draft.blockedChatKeys,
                        onAdd = vm::addBlockedChat,
                        onRemove = vm::removeBlockedChat,
                    )
                    SettingsDivider()
                    SettingsGroupPadding {
                        ValidatedField(
                            label = "Keep data for (days)",
                            value = draft.retentionDays,
                            error = state.errors[SettingsField.RetentionDays],
                            onValueChange = { v -> vm.editDraft { it.copy(retentionDays = v) } },
                            keyboardType = KeyboardType.Number,
                            supporting = "Chat history and the feedback log are deleted after this.",
                        )
                    }
                    SettingsDivider()
                    SettingsNavRow(
                        title = "Feedback log",
                        supporting = "Your past suggestions, on this phone only",
                        onClick = onOpenFeedbackLog,
                        onClickLabel = "Open",
                    )
                }
            }
            item(key = "limits") {
                SettingsSection("Limits and usage") {
                    UsageCard(state.usage)
                    SettingsDivider()
                    SettingsGroupPadding {
                        ValidatedField(
                            label = "Daily request limit",
                            value = draft.dailyRequestLimit,
                            error = state.errors[SettingsField.DailyRequestLimit],
                            onValueChange = { v -> vm.editDraft { it.copy(dailyRequestLimit = v) } },
                            keyboardType = KeyboardType.Number,
                        )
                    }
                }
            }
            item(key = "appearance") {
                SettingsSection("Appearance") {
                    SettingsSwitchRow(
                        label = "Material You colours",
                        supporting = "Use colours from your wallpaper instead of Replyly blue.",
                        checked = state.useDynamicColor,
                        onCheckedChange = vm::setDynamicColor,
                        haptic = true,
                    )
                }
            }
            item(key = "about") {
                SettingsSection("About") {
                    if (onOpenWhatsNew != null) {
                        SettingsNavRow(title = "What's new", onClick = onOpenWhatsNew, onClickLabel = "Show")
                        SettingsDivider()
                    }
                    SettingsNavRow(
                        title = "Setup steps",
                        supporting = "Permissions and the checklist",
                        onClick = onOpenOnboarding,
                        onClickLabel = "Open",
                    )
                    SettingsDivider()
                    SettingsInfoRow(title = "Version", value = appVersion)
                }
            }
        }
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
                .semantics(mergeDescendants = true) { heading() }
                .padding(horizontal = Spacing.XS, vertical = Spacing.S),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        ReplylyWordmark(style = MaterialTheme.typography.titleLarge)
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
    }
}

internal const val SETTINGS_TITLE_TAG = "settings_title"

@Composable
private fun BlocklistBlock(
    title: String,
    hint: String,
    emptyText: String,
    /** One line under the empty state; null for none. */
    emptyBody: String? = null,
    placeholder: String,
    entries: Set<String>,
    onAdd: (String) -> String?,
    onRemove: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsGroupPadding {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.XXS)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entries.isEmpty()) {
            EmptyState(
                illustration = R.drawable.ill_empty_blocklist,
                title = emptyText,
                body = emptyBody,
                size = 40.dp,
                compact = true,
                modifier = Modifier.padding(vertical = Spacing.S),
            )
        }
        entries.sorted().forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { onRemove(entry) },
                    modifier = Modifier.semantics { contentDescription = "Unblock $entry" },
                ) { Text("Remove") }
            }
        }
        val add = {
            error = onAdd(input)
            if (error == null) input = ""
        }
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                error = null
            },
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) add() }),
            trailingIcon = { TextButton(onClick = add, enabled = input.isNotBlank()) { Text("Block") } },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Add to $title" },
        )
    }
}

@Composable
private fun SaveBar(
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    hasErrors: Boolean,
) {
    Surface(tonalElevation = Elevation.Bar, shadowElevation = Elevation.Bar) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.XL, vertical = Spacing.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (hasErrors) "Fix the marked fields" else "Unsaved changes",
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDiscard) { Text("Discard") }
            Button(onClick = onSave) { Text("Save") }
        }
    }
}

/** versionName of the installed app, e.g. "0.1.42". */
@Composable
fun rememberAppVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
}
