package com.paragrudani.replyly.ui.settings.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paragrudani.replyly.appGraph
import com.paragrudani.replyly.ui.theme.MinTouchTarget
import com.paragrudani.replyly.ui.theme.Spacing

/** Settings entry point: the provider profiles with their own view model. */
@Composable
fun ProviderProfilesSection(modifier: Modifier = Modifier) {
    val graph = LocalContext.current.appGraph
    val vm: ProfilesViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer {
                        ProfilesViewModel(graph.profileRepository, graph.profileSecretStore, graph.connectionTester)
                    }
                },
        )
    val state by vm.state.collectAsStateWithLifecycle()
    ProviderProfilesContent(
        state = state,
        onSelect = vm::select,
        onEdit = vm::edit,
        onTest = vm::test,
        onAdd = vm::startNew,
        modifier = modifier,
    )
    state.editor?.let { draft ->
        ProfileEditorDialog(
            draft = draft,
            errors = state.errors,
            canDelete = draft.id != null && state.canDelete,
            message = state.message,
            actions =
                ProfileEditorActions(
                    onChange = vm::editDraft,
                    onPreset = vm::applyPreset,
                    onSave = vm::save,
                    onCancel = vm::cancelEdit,
                    onRemoveKey = vm::stageRemoveKey,
                    onDelete = { draft.id?.let(vm::delete) },
                    onDismissMessage = vm::dismissMessage,
                ),
        )
    }
}

/**
 * Stateless list: tap a row to make it active (the quick switch). It sits inside Settings' "AI
 * provider" section, which gives it its heading, so it has none of its own. Rows are at least
 * 48 dp tall.
 */
@Composable
fun ProviderProfilesContent(
    state: ProfilesUiState,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onTest: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Text(
            "The selected profile is used for suggestions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.loaded) Text("Loading…")
        state.message?.takeIf { state.editor == null }?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        state.rows.forEach { row ->
            ProfileRowItem(
                row = row,
                test = state.tests[row.profile.id],
                onSelect = { onSelect(row.profile.id) },
                onEdit = { onEdit(row.profile.id) },
                onTest = { onTest(row.profile.id) },
            )
        }
        OutlinedButton(onClick = onAdd) { Text("Add profile") }
    }
}

@Composable
private fun ProfileRowItem(
    row: ProfileRow,
    test: TestState?,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onTest: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .selectable(selected = row.active, role = Role.RadioButton, onClick = onSelect),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            // The row handles the click, so the whole line is one touch target.
            RadioButton(selected = row.active, onClick = null)
            Column(Modifier.weight(1f)) {
                Text(row.profile.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${row.profile.format.label()} · ${row.profile.model}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!row.hasKey) {
                    Text(
                        "No API key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onTest, enabled = test != TestState.Running) { Text("Test connection") }
            when (test) {
                null -> {
                    Unit
                }

                TestState.Running -> {
                    Text("Testing…", style = MaterialTheme.typography.bodySmall)
                }

                is TestState.Ok -> {
                    Text(
                        test.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                is TestState.Failed -> {
                    Unit
                }
            }
        }
        if (test is TestState.Failed) {
            Text(test.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
