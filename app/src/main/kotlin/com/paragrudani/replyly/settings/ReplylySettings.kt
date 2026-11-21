package com.paragrudani.replyly.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** User settings (spec 3 and 8). The API key is not here: see [ApiKeyStore]. */
data class ReplylySettings(
    val endpoint: String = DEFAULT_ENDPOINT,
    val model: String = DEFAULT_MODEL,
    /** N: how many history messages go into the prompt. */
    val historyMessageCount: Int = 40,
    val aboutMe: String = "",
    /** Encoded [com.paragrudani.replyly.parser.ChatKey]s that Replyly must never read. */
    val blockedChatKeys: Set<String> = emptySet(),
    /** Packages that Replyly must never read. */
    val blockedPackages: Set<String> = emptySet(),
    val dailyRequestLimit: Int = 200,
    val retentionDays: Int = 60,
    /**
     * Spec 6 latency fallback: 3 concurrent single-suggestion calls (agree / decline / ask)
     * instead of one call for 3. Counts as 1 request against the daily limit.
     */
    val useParallelCalls: Boolean = false,
) {
    companion object {
        const val DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1"

        // Spec 6 shortlist; the model test picks the final default.
        const val DEFAULT_MODEL = "anthropic/claude-haiku-5.5"
    }
}

/** Ticket #6 implements this on DataStore. */
interface SettingsRepository {
    val settings: Flow<ReplylySettings>

    suspend fun update(transform: (ReplylySettings) -> ReplylySettings)
}

suspend fun SettingsRepository.current(): ReplylySettings = settings.first()

/**
 * The OpenRouter (or compatible) API key. Ticket #6 stores it with a Keystore key in encrypted
 * preferences. Never log it, never put it in [ReplylySettings], never send it anywhere but the
 * configured LLM endpoint.
 */
interface ApiKeyStore {
    val hasKey: Flow<Boolean>

    suspend fun get(): String?

    /** Stores [key] trimmed; a blank result clears it. */
    suspend fun set(key: String)
}

/** Stub until ticket #6. Not persisted. */
class InMemorySettingsRepository(
    initial: ReplylySettings = ReplylySettings(),
) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<ReplylySettings> = state

    override suspend fun update(transform: (ReplylySettings) -> ReplylySettings) = state.update(transform)
}

/** Stub until ticket #6. Not persisted, not encrypted. */
class InMemoryApiKeyStore : ApiKeyStore {
    private val key = MutableStateFlow<String?>(null)
    private val present = MutableStateFlow(false)
    override val hasKey: Flow<Boolean> = present

    override suspend fun get(): String? = key.value

    override suspend fun set(key: String) {
        this.key.value = key.trim().takeIf { it.isNotEmpty() }
        present.value = this.key.value != null
    }
}
