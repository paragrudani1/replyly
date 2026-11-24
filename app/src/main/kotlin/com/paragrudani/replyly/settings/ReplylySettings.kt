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
     * "Names people call me", comma-separated (issue #26). Only used to rank group messages that
     * mention me; may be empty. See [myNameList].
     */
    val myNames: String = "",
    /**
     * "My X handle" (issue #30): marks my own posts on X. Stored without `@` (see
     * `XHandles.normalize`), compared without case; empty when not set.
     */
    val myXHandle: String = "",
    /**
     * "My X voice" (#30 PR 2): free text on topics, tone, emoji and hashtag habits, for the X prompt
     * only; empty when not set.
     */
    val myXVoice: String = "",
    /**
     * "X post length" (#30 PR 2): the weighted limit of an X suggestion, 280 (standard) or 500
     * (medium, for X Premium). See [xPostLimit].
     */
    val xPostLength: Int = X_POST_STANDARD,
    /** Material You: colours from the wallpaper instead of the Replyly brand colours. */
    val useDynamicColor: Boolean = false,
    /** "Show bubble" (#55): off hides the floating button in every app; capture continues. */
    val showBubble: Boolean = true,
    /** "Size" (#55): one of [BUBBLE_SIZES], times the 44 dp circle. See [bubbleSizeStep]. */
    val bubbleSize: Float = BUBBLE_SIZE_DEFAULT,
    /** "Opacity" (#55): percent, one of [BUBBLE_OPACITIES]. See [bubbleOpacityStep]. */
    val bubbleOpacity: Int = BUBBLE_OPACITY_DEFAULT,
    /** "Shrink when idle" (#55): after 5 s untouched the button becomes a small dot. */
    val shrinkWhenIdle: Boolean = false,
) {
    companion object {
        const val DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1"

        // Spec 6 shortlist; the model test picks the final default.
        const val DEFAULT_MODEL = "anthropic/claude-haiku-5.5"

        /** "X post length" values; the same numbers as `XLength.STANDARD_LIMIT` / `MEDIUM_LIMIT`. */
        const val X_POST_STANDARD = 280
        const val X_POST_MEDIUM = 500

        /** The bubble size steps (#55), smallest first. */
        val BUBBLE_SIZES = listOf(0.85f, 1.0f, 1.15f, 1.3f)
        const val BUBBLE_SIZE_DEFAULT = 1.0f

        /** The bubble opacity steps in percent (#55): never below 40, so the button stays findable. */
        val BUBBLE_OPACITIES = listOf(40, 60, 80, 100)
        const val BUBBLE_OPACITY_DEFAULT = 100

        /** A stored size that is one of [BUBBLE_SIZES] (as a float, within rounding), else the default 1.0. */
        fun bubbleSizeStep(stored: Float): Float =
            BUBBLE_SIZES.firstOrNull { kotlin.math.abs(it - stored) < 0.001f } ?: BUBBLE_SIZE_DEFAULT

        /** A stored opacity clamped to 40..100 and rounded to the nearest step of 20 (half up). */
        fun bubbleOpacityStep(stored: Int): Int {
            val clamped = stored.coerceIn(BUBBLE_OPACITIES.first(), BUBBLE_OPACITIES.last())
            return (clamped + 10) / 20 * 20
        }
    }
}

/** [ReplylySettings.xPostLength], with any stored value other than medium read as standard. */
fun ReplylySettings.xPostLimit(): Int =
    if (xPostLength == ReplylySettings.X_POST_MEDIUM) ReplylySettings.X_POST_MEDIUM else ReplylySettings.X_POST_STANDARD

/** Ticket #6 implements this on DataStore. */
interface SettingsRepository {
    val settings: Flow<ReplylySettings>

    suspend fun update(transform: (ReplylySettings) -> ReplylySettings)
}

suspend fun SettingsRepository.current(): ReplylySettings = settings.first()

/** [ReplylySettings.myNames] split on commas, trimmed, without blanks or repeats (any case). */
fun ReplylySettings.myNameList(): List<String> =
    myNames
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

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

/** In-memory fake for tests (AppGraph binds the real one). Not persisted. */
class InMemorySettingsRepository(
    initial: ReplylySettings = ReplylySettings(),
) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<ReplylySettings> = state

    override suspend fun update(transform: (ReplylySettings) -> ReplylySettings) = state.update(transform)
}

/** In-memory fake for tests (AppGraph binds the real one). Not persisted, not encrypted. */
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
