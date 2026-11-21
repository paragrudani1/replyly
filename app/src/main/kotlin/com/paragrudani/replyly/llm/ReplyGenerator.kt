package com.paragrudani.replyly.llm

import com.paragrudani.replyly.parser.ParsedChat

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
 * Throws [SuggestionFailureException] for failures the panel can explain (for example
 * [SuggestionResult.Failure.StorageLocked]); any other exception is shown as a generic read error.
 */
interface SuggestionRequestAssembler {
    suspend fun assemble(
        chat: ParsedChat,
        instruction: String?,
    ): SuggestionRequest
}
