package com.paragrudani.replyly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.paragrudani.replyly.ui.theme.MinTouchTarget
import com.paragrudani.replyly.ui.theme.Spacing

/** Quick picks for the model field (spec 6 shortlist); any other id can be typed. */
val MODEL_SHORTLIST = listOf("anthropic/claude-haiku-5.5", "google/gemini-3.8-flash", "~openai/gpt-mini-latest")

/**
 * The "AI provider" slot's current content: endpoint, API key and model, unchanged in behaviour.
 * [SettingsScreen] takes the slot as a parameter, so provider profiles (#18) can replace this
 * whole block without touching the rest of the screen.
 */
@Composable
fun ColumnScope.LegacyAiProviderFields(
    state: SettingsUiState,
    vm: SettingsViewModel,
) {
    val draft = state.draft ?: return
    SettingsGroupPadding {
        ValidatedField(
            label = "API endpoint",
            value = draft.endpoint,
            error = state.errors[SettingsField.Endpoint],
            onValueChange = { v -> vm.editDraft { it.copy(endpoint = v) } },
            keyboardType = KeyboardType.Uri,
        )
        ApiKeyField(state, vm)
        ModelField(
            model = draft.model,
            error = state.errors[SettingsField.Model],
            onModel = { v -> vm.editDraft { it.copy(model = v) } },
        )
    }
}

@Composable
private fun ApiKeyField(
    state: SettingsUiState,
    vm: SettingsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        if (!state.showApiKeyField) {
            // The stored key is never read back into the UI, not even partly.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("API key", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Saved ••••••••",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = vm::startReplacingApiKey) { Text("Replace") }
                TextButton(onClick = vm::clearApiKey) { Text("Remove") }
            }
        } else {
            OutlinedTextField(
                value = state.apiKeyInput,
                onValueChange = vm::setApiKeyInput,
                label = { Text(if (state.apiKeySaved) "New API key" else "API key") },
                placeholder = { Text("sk-or-…") },
                isError = state.apiKeyError != null,
                supportingText = { Text(state.apiKeyError ?: "Stored encrypted on this phone only.") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                Button(onClick = vm::saveApiKey, enabled = state.apiKeyInput.isNotBlank()) { Text("Save key") }
                if (state.replacingApiKey) TextButton(onClick = vm::cancelReplacingApiKey) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun ModelField(
    model: String,
    error: String?,
    onModel: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
        ValidatedField(
            label = "Model",
            value = model,
            error = error,
            onValueChange = onModel,
            supporting = "Any model id from your endpoint, or pick one:",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            MODEL_SHORTLIST.forEach { id ->
                FilterChip(selected = model.trim() == id, onClick = { onModel(id) }, label = { Text(id) })
            }
        }
    }
}

/**
 * A single-choice list of model ids, one 48 dp radio row each. Stateless and reusable: the
 * provider profile editor (#18) passes its profile's models and the selected id.
 *
 * TalkBack: the list is a selectable group; each row reads "<id>, radio button, selected".
 *
 * @param models ids in display order; duplicates are shown once.
 * @param selected the current id, or null for none. An id not in [models] selects nothing.
 * @param label optional human name per id (default: the id itself).
 */
@Composable
fun ModelPickerList(
    models: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (String) -> String = { it },
) {
    Column(modifier.fillMaxWidth().selectableGroup()) {
        models.distinct().forEach { id ->
            val isSelected = id == selected
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(id) })
                        .padding(horizontal = Spacing.L, vertical = Spacing.XS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.M),
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Text(
                    label(id),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
