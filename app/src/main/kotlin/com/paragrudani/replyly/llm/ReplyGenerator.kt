package com.paragrudani.replyly.llm

import com.paragrudani.replyly.parser.ParsedChat
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Gets 3 suggestions from the LLM (ticket #4). The implementation checks the API key store and
 * [com.paragrudani.replyly.cost.CostGuard] before the call and records token usage after it.
 * Only a user tap on the button or Regenerate may lead to a call; never screen events.
 */
interface ReplyGenerator {
    suspend fun generate(request: SuggestionRequest): SuggestionResult
}

/**
 * Gathers history, About me, contact note and style examples into a [SuggestionRequest]
 * (ticket #4). Merges [chat] into history first, so the request includes what is on screen now.
 */
interface SuggestionRequestAssembler {
    suspend fun assemble(
        chat: ParsedChat,
        instruction: String?,
    ): SuggestionRequest
}

/** Stub until ticket #4: canned suggestions, no network. Lets the panel (#5) be built against it. */
class StubReplyGenerator : ReplyGenerator {
    override suspend fun generate(request: SuggestionRequest): SuggestionResult {
        delay(300.milliseconds)
        return SuggestionResult.Success(
            suggestions =
                listOf(
                    Suggestion("agree", "Sounds good!"),
                    Suggestion("decline", "Sorry, can't make it today."),
                    Suggestion("ask", "What time works for you?"),
                ),
            usage = TokenUsage(promptTokens = 0, completionTokens = 0),
            latency = 300.milliseconds,
            model = "stub",
            promptSent = "",
        )
    }
}

/** Stub until ticket #4: uses only what is on screen. */
class StubSuggestionRequestAssembler : SuggestionRequestAssembler {
    override suspend fun assemble(
        chat: ParsedChat,
        instruction: String?,
    ): SuggestionRequest =
        SuggestionRequest(
            packageName = chat.packageName,
            appLabel = chat.packageName,
            chatTitle = chat.title,
            isGroup = chat.isGroup,
            history = chat.messages,
            aboutMe = "",
            contactNote = null,
            styleExamples = chat.messages.filter { it.isMe }.map { it.text },
            instruction = instruction,
        )
}
