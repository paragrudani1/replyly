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
    /**
     * Local date of the message: history's stored date, or the on-screen [ChatMessage.date] for a
     * message that is not in history. Null when unknown.
     */
    val date: LocalDate? = null,
    /** True when [date] is history's guess (the day Replyly first saw it), not a date separator. */
    val dateIsApprox: Boolean = false,
    /** When Replyly first saw the message; null for on-screen messages that are not in history. */
    val firstSeenAt: Instant? = null,
) {
    /** The message quotes or replies to one of mine (group reply ranking); from [ChatMessage.repliesToMe]. */
    val repliesToMe: Boolean get() = message.repliesToMe
}
