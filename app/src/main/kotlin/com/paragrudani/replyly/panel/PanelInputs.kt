package com.paragrudani.replyly.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.R
import com.paragrudani.replyly.ui.theme.MinTouchTarget
import com.paragrudani.replyly.ui.theme.Spacing

/**
 * The instruction field's example: a chat one, or an X one on X (#46). The X one has no
 * "Instruction," so it shows in full at 130 % font (about 23 characters fit).
 */
internal fun instructionPlaceholder(isX: Boolean): String =
    if (isX) "e.g. ask for a source" else "Instruction, e.g. say I'll call at 6"

/**
 * The instruction field with Regenerate, and the quick-chips under it.
 *
 * A chip tap toggles that chip and regenerates once; chips and the typed text combine as
 * [combineInstruction] describes. The field stops at [instructionRoom] (smaller with chips on) and
 * shows a counter near that limit, so typed text is never cut silently. While a call or an insert
 * runs, and after an insert, the chips and Regenerate are disabled (the state also ignores those
 * taps). Done on the keyboard only hides it: a model call needs a tap on Regenerate or a chip
 * (AGENTS.md rule 4).
 */
@Composable
internal fun InstructionArea(
    ui: PanelUiState,
    actions: PanelActions,
) {
    val focusManager = LocalFocusManager.current
    // A running insert counts too: it may still succeed and close the panel (no call after that).
    val busy = ui.suggestions == SuggestionsUi.Loading || ui.inserting || ui.finished
    Column(Modifier.padding(top = Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.XXS)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            CompactField(
                value = ui.instruction,
                onValueChange = actions.onInstructionChange,
                placeholder = instructionPlaceholder(ui.isX),
                singleLine = true,
                shape = CircleShape,
                keyboardOptions =
                    KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = {
                    focusManager.clearFocus()
                    actions.onRegenerate()
                },
                enabled = !busy,
            ) {
                Icon(painterResource(R.drawable.ic_panel_refresh), contentDescription = "Regenerate replies")
            }
        }
        InstructionCounter(ui)
        // Edge to edge, so chips scroll out under the sheet's edge instead of being cut at the padding.
        Row(
            Modifier
                .bleed(Spacing.L)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.L),
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            QuickInstruction.forPanel(ui.isX).forEach { chip ->
                val selected = chip in ui.chips
                FilterChip(
                    selected = selected,
                    onClick = {
                        focusManager.clearFocus()
                        actions.onToggleChip(chip)
                    },
                    enabled = !busy,
                    label = { Text(chip.label) },
                    leadingIcon =
                        if (selected) {
                            {
                                Icon(
                                    painterResource(R.drawable.ic_panel_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

/**
 * "n/limit" under the field's end, only near the limit ([INSTRUCTION_COUNTER_MARGIN]): the field
 * stops at [instructionRoom], which the selected chips make smaller. Error colour at the limit.
 */
@Composable
private fun InstructionCounter(ui: PanelUiState) {
    val room = instructionRoom(ui.chips)
    val length = ui.instruction.length
    if (length < room - INSTRUCTION_COUNTER_MARGIN) return
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(end = MinTouchTarget + Spacing.S + Spacing.L)) {
        Spacer(Modifier.weight(1f))
        Text(
            "$length/$room",
            style = MaterialTheme.typography.labelSmall,
            color = if (length >= room) colors.error else colors.onSurfaceVariant,
            modifier =
                Modifier.semantics {
                    contentDescription = "$length of $room characters"
                    liveRegion = LiveRegionMode.Polite
                },
        )
    }
}

/** Lays the content out [horizontal] wider on both sides than its parent's padding allows. */
private fun Modifier.bleed(horizontal: Dp): Modifier =
    layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val extra = horizontal.roundToPx() * 2
        val width = constraints.maxWidth + extra
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
    }

/**
 * A compact text field: a tonal pill (or rounded box for several lines) at least 48 dp tall,
 * without the outline and floating label of Material's fields.
 */
@Composable
internal fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = MaterialTheme.colorScheme
    // The String overload on purpose: the text lives in ReplyPanelState (one state holder, tested
    // without Compose), not in a TextFieldState.
    @Suppress("DEPRECATION")
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .clip(shape)
                    .background(colors.surfaceContainerHigh)
                    .padding(horizontal = Spacing.L, vertical = Spacing.M),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}

/** The contact-note editor, in place of the suggestions while it is open. */
@Composable
internal fun NoteEditor(
    note: NoteEditorUi,
    chatTitle: String,
    actions: PanelActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Text(
            "Note about $chatTitle",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        CompactField(
            value = note.text,
            onValueChange = actions.onNoteChange,
            placeholder = "Who they are, how you talk with them…",
            singleLine = false,
            shape = MaterialTheme.shapes.medium,
            enabled = !note.saving,
            minLines = 3,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        note.error?.let { ErrorLine(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${note.text.length}/$MAX_NOTE_CHARS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = actions.onCancelNote, enabled = !note.saving) { Text("Cancel") }
            Spacer(Modifier.size(Spacing.XS))
            Button(onClick = actions.onSaveNote, enabled = !note.saving) { Text("Save") }
        }
    }
}

/** Inline, not an AlertDialog: a Dialog needs an Activity window token. */
@Composable
internal fun DeleteConfirmation(
    chatTitle: String,
    actions: PanelActions,
) {
    val colors = MaterialTheme.colorScheme
    // It opens below the cards: scroll it into view so the question is not hidden under the fold.
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(requester) {
        withFrameNanos { } // after the first layout, when the scroll knows where the card is
        requester.bringIntoView()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surfaceContainerHigh)
            .padding(Spacing.M),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        Text(
            "Delete the saved messages of $chatTitle?",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "The contact note stays. Messages still on screen are saved again " +
                "the next time Replyly reads this chat.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = actions.onCancelDelete) { Text("Cancel") }
            Spacer(Modifier.size(Spacing.XS))
            Button(
                onClick = actions.onConfirmDelete,
                colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
            ) { Text("Delete") }
        }
    }
}

/** The quiet entry point to the delete confirmation. */
@Composable
internal fun DeleteHistoryButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(
            painterResource(R.drawable.ic_panel_delete),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(Spacing.S))
        Text(
            "Delete this chat's history",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ErrorLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
internal fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
