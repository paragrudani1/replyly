package com.paragrudani.replyly.ui.settings.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paragrudani.replyly.llm.ConnectionTestResult
import com.paragrudani.replyly.llm.ConnectionTester
import com.paragrudani.replyly.panel.toPanelError
import com.paragrudani.replyly.settings.ProfileRepository
import com.paragrudani.replyly.settings.ProfileSecretStore
import com.paragrudani.replyly.settings.ProviderProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Result of the last "Test connection" of one profile. */
sealed interface TestState {
    data object Running : TestState

    data class Ok(
        val millis: Long,
    ) : TestState {
        val text: String get() = "OK · $millis ms"
    }

    /** Fixed wording from the panel's error mapping; never provider text. */
    data class Failed(
        val text: String,
    ) : TestState
}

data class ProfileRow(
    val profile: ProviderProfile,
    val active: Boolean,
    val hasKey: Boolean,
)

data class ProfilesUiState(
    val loaded: Boolean = false,
    val rows: List<ProfileRow> = emptyList(),
    val tests: Map<String, TestState> = emptyMap(),
    /** Non-null while the editor is open. */
    val editor: ProfileDraft? = null,
    val errors: Map<ProfileField, String> = emptyMap(),
    /** A write or Keystore failure. Shown once, then dismissed. */
    val message: String? = null,
) {
    val canDelete: Boolean get() = rows.size > 1
}

/**
 * Profiles section of Settings: list, quick switch, editor and "Test connection". Saving writes the
 * key and header values to the encrypted [secrets] first, then the profile.
 */
class ProfilesViewModel(
    private val repository: ProfileRepository,
    private val secrets: ProfileSecretStore,
    private val tester: ConnectionTester,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val mutable = MutableStateFlow(ProfilesUiState())
    val state: StateFlow<ProfilesUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            repository.state
                .flatMapLatest { s ->
                    if (s.profiles.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(s.profiles.map { secrets.hasApiKey(it.id) }) { keys ->
                            s.profiles.mapIndexed { i, p -> ProfileRow(p, p.id == s.active?.id, keys[i]) }
                        }
                    }
                }.collect { rows ->
                    mutable.update { state ->
                        // Keep the editor's "key saved" flag in step with storage.
                        val editor = state.editor
                        val editorKey = editor?.id?.let { id -> rows.firstOrNull { it.profile.id == id }?.hasKey }
                        state.copy(
                            loaded = true,
                            rows = rows,
                            editor =
                                if (editor != null &&
                                    editorKey != null
                                ) {
                                    editor.copy(keySaved = editorKey)
                                } else {
                                    editor
                                },
                        )
                    }
                }
        }
    }

    fun select(id: String) = launchGuarded { repository.setActive(id) }

    fun startNew() =
        mutable.update {
            it.copy(editor = ProfileDraft(id = null).withPreset(ProfilePreset.OpenRouter), errors = emptyMap())
        }

    fun edit(id: String) {
        val row = mutable.value.rows.firstOrNull { it.profile.id == id } ?: return
        mutable.update { it.copy(editor = ProfileDraft.from(row.profile, row.hasKey), errors = emptyMap()) }
    }

    fun editDraft(transform: (ProfileDraft) -> ProfileDraft) =
        mutable.update { state -> state.editor?.let { state.copy(editor = transform(it)) } ?: state }

    fun applyPreset(preset: ProfilePreset) = editDraft { it.withPreset(preset) }

    fun cancelEdit() = mutable.update { it.copy(editor = null, errors = emptyMap()) }

    fun save() {
        val draft = mutable.value.editor ?: return
        when (val result = ProfileValidator.validate(draft, newId)) {
            is ProfileValidation.Invalid -> {
                mutable.update { it.copy(errors = result.errors) }
            }

            is ProfileValidation.Valid -> {
                launchGuarded {
                    val id = result.profile.id
                    result.apiKey?.let { secrets.setApiKey(id, it) }
                    // Rewrite the header set every time: kept saved values plus the new ones. The
                    // names stored on the profile are the ones that really have a value.
                    val saved = if (draft.id == null) emptyMap() else secrets.extraHeaders(id)
                    val merged = saved.filterKeys { it in result.keptHeaderNames } + result.newHeaders
                    secrets.setExtraHeaders(id, merged)
                    val profile = result.profile.copy(extraHeaderNames = merged.keys.toList())
                    repository.upsert(profile)
                    mutable.update { it.copy(editor = null, errors = emptyMap()) }
                }
            }
        }
    }

    fun removeKey() {
        val id = mutable.value.editor?.id ?: return
        launchGuarded { secrets.setApiKey(id, "") }
    }

    fun delete(id: String) =
        launchGuarded {
            repository.delete(id)
            mutable.update { it.copy(editor = null, errors = emptyMap(), tests = it.tests - id) }
        }

    /** One tiny paid request. Ignored while a test of this profile is running. */
    fun test(id: String) {
        if (mutable.value.tests[id] == TestState.Running) return
        mutable.update { it.copy(tests = it.tests + (id to TestState.Running)) }
        viewModelScope.launch {
            val result =
                try {
                    when (val r = tester.testConnection(id)) {
                        is ConnectionTestResult.Ok -> {
                            TestState.Ok(r.latency.inWholeMilliseconds)
                        }

                        is ConnectionTestResult.Failed -> {
                            val error = r.failure.toPanelError()
                            TestState.Failed(listOfNotNull(error.title, error.detail).joinToString(". "))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    TestState.Failed(TEST_ERROR)
                }
            mutable.update { it.copy(tests = it.tests + (id to result)) }
        }
    }

    fun dismissMessage() = mutable.update { it.copy(message = null) }

    /** Storage or Keystore failures show fixed wording; the exception text is not shown or logged. */
    private fun launchGuarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update { it.copy(message = SAVE_ERROR) }
            }
        }
    }

    companion object {
        const val SAVE_ERROR = "Could not save the profile securely. Try again."
        const val TEST_ERROR = "Something went wrong. Try again."
    }
}
