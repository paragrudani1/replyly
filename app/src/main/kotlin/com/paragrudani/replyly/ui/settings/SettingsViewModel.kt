package com.paragrudani.replyly.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paragrudani.replyly.cost.CostGuard
import com.paragrudani.replyly.cost.CostStatus
import com.paragrudani.replyly.feedback.FeedbackEntry
import com.paragrudani.replyly.feedback.FeedbackLog
import com.paragrudani.replyly.feedback.PromptVersionStats
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.settings.SettingsRepository
import com.paragrudani.replyly.settings.current
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Clock
import java.time.Duration

data class SettingsUiState(
    /** Null until the stored settings have loaded. */
    val draft: SettingsDraft? = null,
    val saved: ReplylySettings? = null,
    val errors: Map<SettingsField, String> = emptyMap(),
    val usage: CostStatus = CostStatus(0, ReplylySettings().dailyRequestLimit, 0, 0),
    /** A settings or blocklist write failed (disk full, I/O error). Shown once, then dismissed. */
    val saveError: String? = null,
    /** Reading, purging or clearing the encrypted feedback log failed. Shown once, then dismissed. */
    val storageError: String? = null,
    /**
     * Goes up by one each time Save writes the settings. A one-shot signal for the screen: the
     * confirm haptic plays when it changes, so a failed validation or write never buzzes.
     */
    val savedCount: Int = 0,
) {
    /** Material You colours. Saved at once (not part of the Save bar), like the blocklists. */
    val useDynamicColor: Boolean get() = saved?.useDynamicColor ?: false

    val dirty: Boolean get() = draft != null && saved != null && draft != SettingsDraft.from(saved)
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val costGuard: CostGuard,
    private val feedbackLog: FeedbackLog,
    private val clock: Clock,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    /**
     * Newest entries first. If the encrypted DB cannot be read (Keystore locked, disk error), emits
     * an empty list and shows [STORAGE_LOCKED]; collecting again (reopening the log) retries.
     */
    val feedbackEntries: Flow<List<FeedbackEntry>> =
        feedbackLog.observeRecent(FEEDBACK_VIEWER_LIMIT).catch { e ->
            if (e is CancellationException) throw e
            showStorageError()
            emit(emptyList())
        }

    /** Insert rate and edit amount per prompt version; empty if the log cannot be read (the entries show why). */
    val feedbackStats: Flow<List<PromptVersionStats>> =
        feedbackLog.observeStats().catch { e ->
            if (e is CancellationException) throw e
            emit(emptyList())
        }

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
        viewModelScope.launch { costGuard.status.collect { usage -> mutableState.update { it.copy(usage = usage) } } }
        viewModelScope.launch {
            val retention = settingsRepository.current().retentionDays.toLong()
            guardedFeedback { feedbackLog.purgeOlderThan(clock.instant().minus(Duration.ofDays(retention))) }
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
                    if (!writeSettings(result.apply)) return@launch
                    val stored = settingsRepository.current()
                    mutableState.update {
                        it.copy(
                            draft = SettingsDraft.from(stored),
                            saved = stored,
                            errors = emptyMap(),
                            savedCount = it.savedCount + 1,
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

    /** @return an error message, or null once the package is blocked. */
    fun addBlockedPackage(input: String): String? {
        val pkg = SettingsValidator.normalizePackage(input) ?: return "Enter a package name, e.g. com.whatsapp"
        viewModelScope.launch { writeSettings { it.copy(blockedPackages = it.blockedPackages + pkg) } }
        return null
    }

    fun removeBlockedPackage(pkg: String) {
        viewModelScope.launch { writeSettings { it.copy(blockedPackages = it.blockedPackages - pkg) } }
    }

    /** @return an error message, or null once the chat is blocked. */
    fun addBlockedChat(input: String): String? {
        val key = SettingsValidator.normalizeChatKey(input) ?: return "Use package:chat name, e.g. com.whatsapp:Mom"
        viewModelScope.launch { writeSettings { it.copy(blockedChatKeys = it.blockedChatKeys + key) } }
        return null
    }

    fun removeBlockedChat(key: String) {
        viewModelScope.launch { writeSettings { it.copy(blockedChatKeys = it.blockedChatKeys - key) } }
    }

    /** Applies at once: the theme follows the stored setting, so a Save bar would only delay it. */
    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { writeSettings { it.copy(useDynamicColor = enabled) } }
    }

    fun dismissSaveError() = mutableState.update { it.copy(saveError = null) }

    /** Re-reads today's usage; the screen calls it on resume because deep sleep stalls the midnight tick. */
    fun refreshUsage() = costGuard.refresh()

    /** @return false (and shows [SAVE_ERROR]) if the write failed. The exception text is not shown or logged. */
    private suspend fun writeSettings(transform: (ReplylySettings) -> ReplylySettings): Boolean =
        try {
            settingsRepository.update(transform)
            true
        } catch (_: IOException) {
            mutableState.update { it.copy(saveError = SAVE_ERROR) }
            false
        }

    fun clearFeedbackLog() {
        viewModelScope.launch { guardedFeedback { feedbackLog.clear() } }
    }

    fun dismissStorageError() = mutableState.update { it.copy(storageError = null) }

    private fun showStorageError() = mutableState.update { it.copy(storageError = STORAGE_LOCKED) }

    /**
     * Runs a feedback DB call. Any failure (usually [com.paragrudani.replyly.security.DatabaseKeyUnavailableException],
     * possibly wrapped by Room) shows [STORAGE_LOCKED] instead of crashing. The exception text may
     * hold paths or chat text, so it is not shown or logged.
     */
    private suspend fun guardedFeedback(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            showStorageError()
        }
    }

    companion object {
        const val FEEDBACK_VIEWER_LIMIT = 200
        const val SAVE_ERROR = "Could not save settings"
        const val STORAGE_LOCKED = "Secure storage is locked. Try again."
    }
}
