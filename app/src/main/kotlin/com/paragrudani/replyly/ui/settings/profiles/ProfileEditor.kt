package com.paragrudani.replyly.ui.settings.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.paragrudani.replyly.llm.LlmReplyGenerator
import com.paragrudani.replyly.llm.ProviderUrls
import com.paragrudani.replyly.settings.ApiFormat
import com.paragrudani.replyly.ui.settings.ModelPickerList
import com.paragrudani.replyly.ui.theme.Spacing

class ProfileEditorActions(
    val onChange: ((ProfileDraft) -> ProfileDraft) -> Unit,
    val onPreset: (ProfilePreset) -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
    val onRemoveKey: () -> Unit,
    val onDelete: () -> Unit,
    val onDismissMessage: () -> Unit,
)

/** Full-screen editor for one profile. Saved secrets are never read back into it. */
@Composable
fun ProfileEditorDialog(
    draft: ProfileDraft,
    errors: Map<ProfileField, String>,
    canDelete: Boolean,
    message: String?,
    actions: ProfileEditorActions,
) {
    Dialog(
        onDismissRequest = actions.onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ProfileEditor(draft, errors, canDelete, message, actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditor(
    draft: ProfileDraft,
    errors: Map<ProfileField, String>,
    canDelete: Boolean,
    message: String?,
    actions: ProfileEditorActions,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val change = actions.onChange
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (draft.id == null) "New profile" else "Edit profile") },
                navigationIcon = { TextButton(onClick = actions.onCancel) { Text("Cancel") } },
                actions = { TextButton(onClick = actions.onSave) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.onDismissMessage) { Text("OK") }
                }
            }
            Text("Start from a preset", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfilePreset.entries.forEach { preset ->
                    FilterChip(selected = false, onClick = { actions.onPreset(preset) }, label = { Text(preset.label) })
                }
            }
            Field("Name", draft.name, errors[ProfileField.Name]) { v -> change { it.copy(name = v) } }
            Text("API format", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ApiFormat.entries.forEach { format ->
                    FilterChip(
                        selected = draft.format == format,
                        onClick = { change { it.copy(format = format) } },
                        label = { Text(format.label()) },
                    )
                }
            }
            val preview = ProviderUrls.preview(draft.format, draft.baseUrl.trim())
            Field(
                label = "Base URL",
                value = draft.baseUrl,
                error = errors[ProfileField.BaseUrl],
                supporting = baseUrlHint(draft.format) + (preview?.let { "\nRequests go to $it" } ?: ""),
                keyboardType = KeyboardType.Uri,
            ) { v -> change { it.copy(baseUrl = v) } }
            ApiKeyField(draft, errors[ProfileField.ApiKey], actions)
            ModelField(draft, errors[ProfileField.Model], change)
            Field(
                label = "Max tokens (optional)",
                value = draft.maxTokens,
                error = errors[ProfileField.MaxTokens],
                supporting = "Empty: ${LlmReplyGenerator.MAX_TOKENS}, enough for the analysis and 3 replies.",
                keyboardType = KeyboardType.Number,
            ) { v -> change { it.copy(maxTokens = v) } }
            if (draft.format == ApiFormat.OpenAi) {
                Field(
                    label = "Temperature (optional)",
                    value = draft.temperature,
                    error = errors[ProfileField.Temperature],
                    supporting = "Empty: not sent. Some reasoning models reject it.",
                    keyboardType = KeyboardType.Decimal,
                ) { v -> change { it.copy(temperature = v) } }
            }
            HeadersEditor(draft, errors[ProfileField.Headers], change)
            if (draft.id != null) {
                OutlinedButton(onClick = { confirmDelete = true }, enabled = canDelete) { Text("Delete profile") }
                if (!canDelete) Text("The last profile cannot be deleted.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${draft.name.trim()}?") },
            text = { Text("Its API key and header values are deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: String?,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The model as a radio list (spec item 7): the provider's known ids from [modelChoices] plus
 * "Other model id", which shows a text field for any id. With no known ids, only the field shows.
 */
@Composable
private fun ModelField(
    draft: ProfileDraft,
    error: String?,
    change: ((ProfileDraft) -> ProfileDraft) -> Unit,
) {
    val choices = modelChoices(draft.format, draft.baseUrl)
    // Custom stays chosen while the user types, even if the text happens to match a listed id.
    var custom by rememberSaveable(choices) { mutableStateOf(draft.model.trim() !in choices) }
    val field = @Composable {
        Field(
            label = if (choices.isEmpty()) "Model" else "Model id",
            value = draft.model,
            error = error,
            supporting = "The model id as your provider names it.",
        ) { v -> change { it.copy(model = v) } }
    }
    if (choices.isEmpty()) {
        field()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
        Text("Model", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        ModelPickerList(
            models = choices + OTHER_MODEL,
            selected = if (custom) OTHER_MODEL else draft.model.trim(),
            onSelect = { id ->
                custom = id == OTHER_MODEL
                if (!custom) change { it.copy(model = id) }
            },
            label = { if (it == OTHER_MODEL) "Other model id" else it },
        )
        if (custom) field() else error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** The picker's free-text choice; not a valid model id, so it can never collide with one. */
private const val OTHER_MODEL = " other"

@Composable
private fun ApiKeyField(
    draft: ProfileDraft,
    error: String?,
    actions: ProfileEditorActions,
) {
    if (!draft.showKeyField) {
        // The stored key is never read back into the UI, not even partly.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("API key: saved ••••••••", modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.onChange { it.copy(replacingKey = true) } }) { Text("Replace") }
            TextButton(onClick = actions.onRemoveKey) { Text("Remove") }
        }
        return
    }
    OutlinedTextField(
        value = draft.apiKeyInput,
        onValueChange = { v -> actions.onChange { it.copy(apiKeyInput = v) } },
        label = { Text(if (draft.keySaved) "New API key" else "API key") },
        placeholder = { Text(draft.keyPlaceholder) },
        isError = error != null,
        supportingText = {
            Text(
                error
                    ?: if (draft.removeKey) {
                        "The saved key is removed when you tap Save."
                    } else {
                        "Stored encrypted on this phone only. Saved with the profile."
                    },
            )
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (draft.keySaved && (draft.replacingKey || draft.removeKey)) {
        TextButton(
            onClick = { actions.onChange { it.copy(replacingKey = false, removeKey = false, apiKeyInput = "") } },
        ) {
            Text("Keep the saved key")
        }
    }
}

@Composable
private fun HeadersEditor(
    draft: ProfileDraft,
    error: String?,
    change: ((ProfileDraft) -> ProfileDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Extra headers (optional)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Sent with every request, e.g. an org id for a proxy. Values are stored encrypted and never shown again.",
            style = MaterialTheme.typography.bodySmall,
        )
        draft.savedHeaderNames.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$name: value saved", modifier = Modifier.weight(1f))
                TextButton(onClick = { change { d -> d.copy(savedHeaderNames = d.savedHeaderNames - name) } }) {
                    Text("Remove")
                }
            }
        }
        draft.newHeaders.forEachIndexed { index, header ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = header.name,
                    onValueChange = { v ->
                        change { d -> d.copy(newHeaders = d.newHeaders.replace(index, header.copy(name = v))) }
                    },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = header.value,
                    onValueChange = { v ->
                        change { d -> d.copy(newHeaders = d.newHeaders.replace(index, header.copy(value = v))) }
                    },
                    label = { Text("Value") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    change { d -> d.copy(newHeaders = d.newHeaders.filterIndexed { i, _ -> i != index }) }
                }) { Text("Remove") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            onClick = { change { d -> d.copy(newHeaders = d.newHeaders + HeaderInput()) } },
            enabled = draft.savedHeaderNames.size + draft.newHeaders.size < ProfileValidator.MAX_HEADERS,
        ) { Text("Add header") }
    }
}

private fun <T> List<T>.replace(
    index: Int,
    item: T,
): List<T> = mapIndexed { i, old -> if (i == index) item else old }
