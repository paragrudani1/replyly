package com.paragrudani.replyly.panel

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.MainActivity
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * The suggestions panel (ticket #5), built to run inside ticket #1's accessibility overlay
 * window, where there is no Activity.
 *
 * ## What the host (#1) must provide
 *
 * - A `ComposeView` added with `WindowManager.addView`, using the accessibility service as context.
 * - On that view (or its root), before it is attached: `setViewTreeLifecycleOwner`,
 *   `setViewTreeSavedStateRegistryOwner` and `setViewTreeViewModelStoreOwner`. One small owner object
 *   implementing all three is enough (`SavedStateRegistryController.create(owner).performRestore(null)`).
 *   Move its lifecycle to RESUMED while the panel is shown, and to DESTROYED (plus
 *   `disposeComposition()`) when the panel is removed. Compose throws without the lifecycle and
 *   saved-state owners; the panel itself never calls `viewModel()`.
 * - Window type `TYPE_ACCESSIBILITY_OVERLAY` with `FLAG_NOT_TOUCH_MODAL` (touches outside the
 *   panel still reach the chat app). While the panel is shown the window must **not** have
 *   `FLAG_NOT_FOCUSABLE` or `FLAG_ALT_FOCUSABLE_IM`, otherwise the instruction field and the note
 *   editor can never get the keyboard. Suggested `softInputMode`: `SOFT_INPUT_ADJUST_PAN`
 *   (confirm on the S23, see `docs/device-checklist/ticket-5.md`).
 * - When the user types into the panel, the chat app's input field loses input focus. The
 *   inserter must therefore write into the input node remembered when the button was tapped,
 *   and the overlay must not hide the panel just because the chat field lost focus.
 * - One composition per panel open. Opening the panel = composing [Content]; that composition
 *   makes the LLM call. Disposing and composing it again (for example on a re-layout) makes a
 *   new call, so keep the same `ComposeView` and composition while the panel stays open.
 * - The panel has no back handling (an overlay has no `OnBackPressedDispatcherOwner`); the host
 *   may call the same `onClose` on KEYCODE_BACK. It applies its own theme and caps its height at
 *   [maxHeight]; the host only places it above the keyboard.
 *
 * The panel uses no Dialog, Popup, `LocalActivity`, Activity ViewModelStore or activity result
 * launcher: those need an Activity window token. Its state lives in [ReplyPanelState], created
 * with `remember` and running its coroutines in the composition's scope, so closing the panel
 * cancels any call still in flight.
 */
class ComposeReplyPanel(
    private val deps: ReplyPanelDependencies,
    private val openSettings: (Context) -> Unit = ::openReplylySettings,
    private val maxHeight: Dp = DEFAULT_MAX_HEIGHT,
) : ReplyPanel {
    @Composable
    override fun Content(
        request: PanelRequest,
        onClose: () -> Unit,
    ) {
        val currentOnClose by rememberUpdatedState(onClose)
        val scope = rememberCoroutineScope()
        val state = remember(request) { ReplyPanelState(request, deps, scope, onClose = { currentOnClose() }) }
        LaunchedEffect(state) { state.start() }
        val ui by state.state.collectAsState()
        val context = LocalContext.current
        ReplylyTheme {
            PanelLayout(
                ui = ui,
                chatTitle = request.chat.title,
                maxHeight = maxHeight,
                actions =
                    PanelActions(
                        onClose = { currentOnClose() },
                        onInstructionChange = state::onInstructionChange,
                        onRegenerate = state::regenerate,
                        onInsert = state::insert,
                        onOpenSettings = {
                            openSettings(context)
                            currentOnClose()
                        },
                        onOpenNote = state::openNote,
                        onNoteChange = state::onNoteChange,
                        onSaveNote = state::saveNote,
                        onCancelNote = state::cancelNote,
                        onRequestDelete = state::requestDelete,
                        onConfirmDelete = state::confirmDelete,
                        onCancelDelete = state::cancelDelete,
                    ),
            )
        }
    }

    companion object {
        /** Leaves room for the chat's input field between the panel and Gboard on the S23. */
        val DEFAULT_MAX_HEIGHT: Dp = 340.dp
    }
}

/** Opens Replyly's own settings from the overlay (no Activity here, so a new task). */
fun openReplylySettings(context: Context) {
    context.startActivity(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

internal class PanelActions(
    val onClose: () -> Unit = {},
    val onInstructionChange: (String) -> Unit = {},
    val onRegenerate: () -> Unit = {},
    val onInsert: (Int) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenNote: () -> Unit = {},
    val onNoteChange: (String) -> Unit = {},
    val onSaveNote: () -> Unit = {},
    val onCancelNote: () -> Unit = {},
    val onRequestDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
)

@Composable
internal fun PanelLayout(
    ui: PanelUiState,
    chatTitle: String,
    maxHeight: Dp,
    actions: PanelActions,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.heightIn(max = maxHeight).padding(horizontal = 12.dp, vertical = 6.dp)) {
            TopBar(ui, chatTitle, actions)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val note = ui.note
                if (note != null) {
                    NoteEditor(note, chatTitle, actions)
                } else {
                    SuggestionsBody(ui, actions)
                    ui.insertError?.let { ErrorLine(it) }
                    ui.notice?.let { Notice(it) }
                    if (ui.confirmingDelete) {
                        DeleteConfirmation(chatTitle, actions)
                    } else if (ui.showChatActions) {
                        TextButton(onClick = actions.onRequestDelete) {
                            Text("Delete this chat's history", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (ui.note == null) InstructionRow(ui, actions)
        }
    }
}

@Composable
private fun TopBar(
    ui: PanelUiState,
    chatTitle: String,
    actions: PanelActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            chatTitle,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (ui.showChatActions && ui.note == null) {
            TextButton(onClick = actions.onOpenNote) { Text("Contact note") }
        }
        TextButton(onClick = actions.onClose) { Text("Close") }
    }
}

@Composable
private fun SuggestionsBody(
    ui: PanelUiState,
    actions: PanelActions,
) {
    when (val suggestions = ui.suggestions) {
        SuggestionsUi.Loading -> {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            Text(
                "Writing 3 replies…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is SuggestionsUi.Ready -> {
            suggestions.suggestions.forEachIndexed { index, suggestion ->
                SuggestionCard(suggestion, enabled = !ui.inserting, onClick = { actions.onInsert(index) })
            }
            Text(
                "${suggestions.model} · ${formatSeconds(suggestions.latency.inWholeMilliseconds)} · tap to insert",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        is SuggestionsUi.Error -> {
            ErrorCard(suggestions.error, actions)
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: Suggestion,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                suggestion.intent.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            // No maxLines: long Hinglish replies wrap in full, the body scrolls.
            Text(suggestion.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorCard(
    error: PanelError,
    actions: PanelActions,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(error.title, style = MaterialTheme.typography.titleSmall)
            error.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            when (error.action) {
                ErrorAction.OpenSettings -> Button(onClick = actions.onOpenSettings) { Text("Open settings") }
                ErrorAction.Retry -> Button(onClick = actions.onRegenerate) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DeleteConfirmation(
    chatTitle: String,
    actions: PanelActions,
) {
    // Inline, not an AlertDialog: a Dialog needs an Activity window token.
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Delete the saved messages of $chatTitle?", style = MaterialTheme.typography.titleSmall)
            Text(
                "The contact note stays. Messages still on screen are saved again " +
                    "the next time Replyly reads this chat.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = actions.onCancelDelete) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = actions.onConfirmDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun NoteEditor(
    note: NoteEditorUi,
    chatTitle: String,
    actions: PanelActions,
) {
    Text("Contact note for $chatTitle", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(
        value = note.text,
        onValueChange = actions.onNoteChange,
        enabled = !note.saving,
        minLines = 3,
        maxLines = 6,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text("Who they are, how you talk with them…") },
        supportingText = { Text("${note.text.length}/$MAX_NOTE_CHARS") },
        modifier = Modifier.fillMaxWidth(),
    )
    note.error?.let { ErrorLine(it) }
    Row {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = actions.onCancelNote, enabled = !note.saving) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = actions.onSaveNote, enabled = !note.saving) { Text("Save") }
    }
}

@Composable
private fun InstructionRow(
    ui: PanelUiState,
    actions: PanelActions,
) {
    val focusManager = LocalFocusManager.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        OutlinedTextField(
            value = ui.instruction,
            onValueChange = actions.onInstructionChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = {
                Text("Instruction, e.g. say I'll call at 6", maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            // Done only hides the keyboard: a model call needs a tap on Regenerate (AGENTS.md rule 4).
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.weight(1f),
        )
        val loading = ui.suggestions == SuggestionsUi.Loading
        if (loading) {
            OutlinedButton(onClick = {}, enabled = false) { Text("Regenerate") }
        } else {
            FilledTonalButton(onClick = {
                focusManager.clearFocus()
                actions.onRegenerate()
            }) { Text("Regenerate") }
        }
    }
}

private fun formatSeconds(millis: Long): String = String.format(Locale.ROOT, "%.1f s", millis / 1000.0)

private val previewReady =
    PanelUiState(
        suggestions =
            SuggestionsUi.Ready(
                listOf(
                    Suggestion(
                        "agree",
                        "Haan pakka, kal 6 baje milte hain. Main seedha office se aa jaunga, thoda late ho sakta hai",
                    ),
                    Suggestion("decline", "Kal nahi ho payega yaar, weekend pe plan karte hain?"),
                    Suggestion("ask", "Kitne baje aur kahan?"),
                ),
                model = "anthropic/claude-haiku-5.5",
                latency = 1_400.milliseconds,
                feedbackEntryId = 1,
            ),
    )

@Preview(widthDp = 384)
@Composable
private fun PanelReadyPreview() {
    ReplylyTheme { PanelLayout(previewReady, "Riya", ComposeReplyPanel.DEFAULT_MAX_HEIGHT, PanelActions()) }
}

@Preview(widthDp = 384)
@Composable
private fun PanelErrorPreview() {
    val ui =
        PanelUiState(
            suggestions =
                SuggestionsUi.Error(
                    PanelError(
                        "Daily limit reached",
                        "You have used all 50 requests for today.",
                        ErrorAction.OpenSettings,
                    ),
                ),
        )
    ReplylyTheme { PanelLayout(ui, "Riya", ComposeReplyPanel.DEFAULT_MAX_HEIGHT, PanelActions()) }
}
