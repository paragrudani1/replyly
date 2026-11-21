package com.paragrudani.replyly.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paragrudani.replyly.cost.CostGuard
import com.paragrudani.replyly.cost.CostStatus
import com.paragrudani.replyly.feedback.FeedbackEntry
import com.paragrudani.replyly.feedback.FeedbackLog
import com.paragrudani.replyly.settings.ApiKeyStore
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.settings.SettingsRepository
import com.paragrudani.replyly.settings.current
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration

data class SettingsUiState(
    /** Null until the stored settings have loaded. */
    val draft: SettingsDraft? = null,
    val saved: ReplylySettings? = null,
    val errors: Map<SettingsField, String> = emptyMap(),
    val apiKeySaved: Boolean = false,
    /** What is typed in the key field right now. Cleared once saved; never filled from storage. */
    val apiKeyInput: String = "",
    val apiKeyError: String? = null,
    val usage: CostStatus = CostStatus(0, ReplylySettings().dailyRequestLimit, 0, 0),
) {
    val dirty: Boolean get() = draft != null && saved != null && draft != SettingsDraft.from(saved)

    // Keeps a half-typed key out of any log or crash report that prints the state.
    override fun toString(): String =
        "SettingsUiState(draft=$draft, errors=$errors, apiKeySaved=$apiKeySaved, " +
            "apiKeyInput=<${apiKeyInput.length} chars>, apiKeyError=$apiKeyError, usage=$usage)"
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    costGuard: CostGuard,
    private val feedbackLog: FeedbackLog,
    private val clock: Clock,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    val feedbackEntries: Flow<List<FeedbackEntry>> = feedbackLog.observeRecent(FEEDBACK_VIEWER_LIMIT)

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { stored ->
                mutableState.update { state ->
                    val draft =
                        when {
                            state.draft == null || !state.dirty -> {
                                SettingsDraft.from(stored)
                            }

                            // Unsaved edits stay; blocklists are saved at once, so take the stored ones.
                            else -> {
                                state.draft.copy(
                                    blockedPackages = stored.blockedPackages,
                                    blockedChatKeys = stored.blockedChatKeys,
                                )
                            }
                        }
                    state.copy(draft = draft, saved = stored)
                }
            }
        }
        viewModelScope.launch {
            apiKeyStore.hasKey.collect { has ->
                mutableState.update { it.copy(apiKeySaved = has) }
            }
        }
        viewModelScope.launch { costGuard.status.collect { usage -> mutableState.update { it.copy(usage = usage) } } }
        viewModelScope.launch {
            val retention = settingsRepository.current().retentionDays.toLong()
            feedbackLog.purgeOlderThan(clock.instant().minus(Duration.ofDays(retention)))
        }
    }

    fun editDraft(transform: (SettingsDraft) -> SettingsDraft) {
        mutableState.update { state -> state.draft?.let { state.copy(draft = transform(it)) } ?: state }
    }

    fun save() {
        val draft = mutableState.value.draft ?: return
        when (val result = SettingsValidator.validate(draft)) {
            is DraftValidation.Invalid -> {
                mutableState.update { it.copy(errors = result.errors) }
            }

            is DraftValidation.Valid -> {
                viewModelScope.launch {
                    settingsRepository.update(result.apply)
                    val stored = settingsRepository.current()
                    mutableState.update {
                        it.copy(
                            draft = SettingsDraft.from(stored),
                            saved = stored,
                            errors = emptyMap(),
                        )
                    }
                }
            }
        }
    }

    fun discardChanges() {
        mutableState.update { state ->
            state.saved?.let { state.copy(draft = SettingsDraft.from(it), errors = emptyMap()) }
                ?: state
        }
    }

    fun setApiKeyInput(value: String) = mutableState.update { it.copy(apiKeyInput = value, apiKeyError = null) }

    fun saveApiKey() {
        val input = mutableState.value.apiKeyInput
        SettingsValidator.apiKeyError(input)?.let { error ->
            mutableState.update { it.copy(apiKeyError = error) }
            return
        }
        viewModelScope.launch {
            val stored = runCatching { apiKeyStore.set(input) }.isSuccess
            mutableState.update {
                if (stored) {
                    it.copy(
                        apiKeyInput = "",
                        apiKeyError = null,
                    )
                } else {
                    it.copy(apiKeyError = "Could not store the key securely. Try again.")
                }
            }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch { apiKeyStore.set("") }
    }

    /** @return an error message, or null once the package is blocked. */
    fun addBlockedPackage(input: String): String? {
        val pkg = SettingsValidator.normalizePackage(input) ?: return "Enter a package name, e.g. com.whatsapp"
        viewModelScope.launch { settingsRepository.update { it.copy(blockedPackages = it.blockedPackages + pkg) } }
        return null
    }

    fun removeBlockedPackage(pkg: String) {
        viewModelScope.launch { settingsRepository.update { it.copy(blockedPackages = it.blockedPackages - pkg) } }
    }

    /** @return an error message, or null once the chat is blocked. */
    fun addBlockedChat(input: String): String? {
        val key = SettingsValidator.normalizeChatKey(input) ?: return "Use package:chat name, e.g. com.whatsapp:Mom"
        viewModelScope.launch { settingsRepository.update { it.copy(blockedChatKeys = it.blockedChatKeys + key) } }
        return null
    }

    fun removeBlockedChat(key: String) {
        viewModelScope.launch { settingsRepository.update { it.copy(blockedChatKeys = it.blockedChatKeys - key) } }
    }

    fun clearFeedbackLog() {
        viewModelScope.launch { feedbackLog.clear() }
    }

    companion object {
        const val FEEDBACK_VIEWER_LIMIT = 200
    }
}
