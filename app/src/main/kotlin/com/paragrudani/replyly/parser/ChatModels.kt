package com.paragrudani.replyly.parser

import com.paragrudani.replyly.parser.x.XContext
import java.time.LocalDate

/**
 * Identifies one chat: app package + chat title (spec 5: a renamed contact is a new chat).
 * [encoded] is the stable string form used in settings blocklists and as a DB column.
 */
data class ChatKey(
    val packageName: String,
    val title: String,
) {
    /** `"<package>:<title>"`. Package names never contain ':', so the first ':' splits it. */
    val encoded: String get() = "$packageName:$title"

    companion object {
        fun decode(encoded: String): ChatKey {
            val split = encoded.indexOf(':')
            require(split > 0) { "Not a chat key" }
            return ChatKey(encoded.substring(0, split), encoded.substring(split + 1))
        }
    }
}

/** One message bubble as shown on screen. */
data class ChatMessage(
    /**
     * Display name of the sender. `null` when [isMe]. In a 1:1 chat it is the chat title. In a
     * group it is the name label above the bubble (carried forward to the following bubbles).
     */
    val sender: String?,
    val text: String,
    /** Time exactly as shown in the bubble, for example "6:42 pm". `null` if not visible. */
    val timeShown: String?,
    val isMe: Boolean,
    /**
     * The local date from the date separator visible above this message on screen ("TODAY",
     * "21 November 2026"...), or from the message's own accessibility text (Telegram). `null` when
     * no separator is visible above it. Never a guess: history marks its own fallback as approx.
     */
    val date: LocalDate? = null,
    /**
     * Best effort: someone else's bubble that quotes one of my messages (the quote's author is
     * "You"). False when unknown.
     */
    val repliesToMe: Boolean = false,
)

/** What a parser reads from one chat screen. Messages are oldest first, as on screen. */
data class ParsedChat(
    val packageName: String,
    val title: String,
    val isGroup: Boolean,
    val messages: List<ChatMessage>,
    /** True only for apps with a full parser. Generic apps send visible text only, no history. */
    val historyApplies: Boolean,
    /**
     * The X reply or quote screen (issue #30), `null` for every other app and for X DMs. Until the X
     * prompt mode lands, [messages] also carries its thread so the chat prompt works.
     */
    val x: XContext? = null,
) {
    val chatKey: ChatKey get() = ChatKey(packageName, title)
}
