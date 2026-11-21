package com.paragrudani.replyly.llm

import com.paragrudani.replyly.parser.ChatMessage
import kotlin.time.Duration

/** Everything the prompt is built from (spec 6). Built by [SuggestionRequestAssembler]. */
data class SuggestionRequest(
    val packageName: String,
    /** Human app name for the prompt, for example "WhatsApp". */
    val appLabel: String,
    val chatTitle: String,
    val isGroup: Boolean,
    /** Last N messages, oldest first. For generic apps: the visible messages only. */
    val history: List<ChatMessage>,
    val aboutMe: String,
    val contactNote: String?,
    /** The user's own messages: this chat first, then 10-15 from other chats of the same app. */
    val styleExamples: List<String>,
    /** Optional one-off instruction, for example "say I'll call at 6". */
    val instruction: String?,
)

/** One reply option. The 3 suggestions of a result each have a different [intent]. */
data class Suggestion(
    val intent: String,
    val text: String,
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

sealed interface SuggestionResult {
    data class Success(
        /** Exactly 3, validated by the generator. */
        val suggestions: List<Suggestion>,
        val usage: TokenUsage,
        val latency: Duration,
        val model: String,
        /** The exact prompt sent, for the feedback log. Never log it to logcat. */
        val promptSent: String,
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

        /** The reply was not the expected JSON with 3 suggestions. */
        data class BadOutput(
            val reason: String,
            /** True when the reply stopped at the token limit before it was complete. */
            val cutOff: Boolean = false,
        ) : Failure
    }
}
