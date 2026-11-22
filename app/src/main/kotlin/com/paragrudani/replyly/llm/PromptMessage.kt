package com.paragrudani.replyly.llm

import com.paragrudani.replyly.parser.ChatMessage
import java.time.Instant
import java.time.LocalDate

/**
 * One chat message as the prompt sees it: the parsed [message] plus what history knows about it.
 * Built by [SuggestionRequestAssembler].
 */
data class PromptMessage(
    val message: ChatMessage,
    /** Local date of the message, or null when unknown (an on-screen message with no history). */
    val date: LocalDate? = null,
    /** True when [date] is a guess (from [firstSeenAt]) rather than a visible date separator. */
    val dateIsApprox: Boolean = false,
    /** The message quotes or replies to one of mine (group reply ranking). */
    val repliesToMe: Boolean = false,
    /** When Replyly first saw the message; null for on-screen messages that are not in history. */
    val firstSeenAt: Instant? = null,
)
