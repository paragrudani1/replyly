package com.paragrudani.replyly.llm

import com.paragrudani.replyly.parser.x.XContext
import java.time.ZonedDateTime
import kotlin.time.Duration

/** Everything the prompt is built from (spec 6). Built by [SuggestionRequestAssembler]. */
data class SuggestionRequest(
    val packageName: String,
    /** Human app name for the prompt, for example "WhatsApp". */
    val appLabel: String,
    val chatTitle: String,
    val isGroup: Boolean,
    /** Last N messages, oldest first. For generic apps: the visible messages only. */
    val history: List<PromptMessage>,
    val aboutMe: String,
    val contactNote: String?,
    /** The user's own messages: this chat first, then 10-15 from other chats of the same app. */
    val styleExamples: List<String>,
    /** Optional one-off instruction, for example "say I'll call at 6". */
    val instruction: String?,
    /** The phone's clock and zone when the request was built: the prompt's "Now" header. */
    val now: ZonedDateTime,
    /** The phone's 12/24-hour setting, for the "Now" header. */
    val is24Hour: Boolean = false,
    /** "Names people call me" from settings: only for ranking group mentions. */
    val myNames: List<String> = emptyList(),
    /** The user tapped "Suggest follow-ups anyway" (see [ReplyTarget.decide]). */
    val followUpAnyway: Boolean = false,
    /**
     * The X reply or quote screen (issue #30, additive). Not null: [PromptBuilder] builds the X
     * prompt ([XPromptBuilder]) and the output is held to [xPostLimit].
     */
    val x: XContext? = null,
    /** "My X voice" from settings (topics, tone, emoji and hashtag habits); only in the X prompt. */
    val myXVoice: String = "",
    /** "X post length": the weighted limit ([XLength]) of each X suggestion, 280 or 500. */
    val xPostLimit: Int = XLength.STANDARD_LIMIT,
)

/** One reply option. The suggestions of a result each have a different [intent]. */
data class Suggestion(
    val intent: String,
    val text: String,
    /** X only (#30 PR 2, additive): the model's text was over the post length and was cut ([XLength.trim]). */
    val trimmed: Boolean = false,
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    /**
     * Input tokens read from the provider's prompt cache (Anthropic `cache_read_input_tokens`,
     * already part of [promptTokens]); null when the reply does not report it. A count only.
     */
    val cacheReadTokens: Int? = null,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

sealed interface SuggestionResult {
    data class Success(
        /**
         * Exactly 3, validated by the generator. On X ([lengthLimit] set) 1 to 3: a card that
         * could not be trimmed to the limit is dropped ([droppedOverLimit]).
         */
        val suggestions: List<Suggestion>,
        val usage: TokenUsage,
        val latency: Duration,
        val model: String,
        /** The exact prompt sent, for the feedback log. Never log it to logcat. */
        val promptSent: String,
        /**
         * The model's short reasoning (prompt v2), for the feedback log only: never shown in the
         * panel and never logged. Empty when the model left it out.
         */
        val analysis: String = "",
        /** [PromptBuilder.VERSION] of the prompt that produced this result. */
        val promptVersion: Int = PromptBuilder.VERSION,
        /** X only (#30 PR 2, additive): the weighted post length each suggestion was held to. */
        val lengthLimit: Int? = null,
        /** X only: suggestions dropped because no sentence or word cut fit [lengthLimit]. */
        val droppedOverLimit: Int = 0,
    ) : SuggestionResult

    sealed interface Failure : SuggestionResult {
        data object NoApiKey : Failure

        /** The stored key has characters outside visible ASCII (e.g. a zero-width space from pasting). */
        data object InvalidApiKey : Failure {
            /** Fixed wording for the error card; never includes the key. */
            const val MESSAGE: String = "The API key has invalid characters. Paste it again in Settings."
        }

        data class LimitReached(
            val dailyLimit: Int,
        ) : Failure

        data object Timeout : Failure

        /** No connection, DNS, TLS... [message] must not contain chat text or the key. */
        data class Network(
            val message: String,
        ) : Failure

        /**
         * Non-2xx reply, or an error object inside a 200. [detail] is fixed wording per status
         * (null for statuses without one); the provider's error body is never shown.
         */
        data class Http(
            val code: Int,
            val detail: String?,
        ) : Failure

        /** The endpoint is not https. The generator refuses it before any request or cost check. */
        data object InsecureEndpoint : Failure {
            /** Fixed wording for the error card. */
            const val MESSAGE: String = "The API endpoint must start with https://. Fix it in Settings."
        }

        /**
         * The key of Replyly's encrypted databases could not be read right now (a transient Android
         * Keystore failure). Nothing was deleted; trying again later usually works.
         */
        data object StorageLocked : Failure {
            /** Fixed wording for the error card. */
            const val MESSAGE: String = "Replyly's encrypted storage is locked right now. Try again in a moment."
        }

        /** The reply was not the expected JSON with 3 suggestions. */
        data class BadOutput(
            val reason: String,
            /** True when the reply stopped at the token limit before it was complete. */
            val cutOff: Boolean = false,
        ) : Failure
    }
}

/**
 * Thrown by [SuggestionRequestAssembler.assemble] when the request cannot be built for a known
 * reason the panel shows as [failure]. The message is the failure's class name only.
 */
class SuggestionFailureException(
    val failure: SuggestionResult.Failure,
) : Exception(failure::class.simpleName)
