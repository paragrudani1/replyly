package com.paragrudani.replyly.parser

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
)

/** What a parser reads from one chat screen. Messages are oldest first, as on screen. */
data class ParsedChat(
    val packageName: String,
    val title: String,
    val isGroup: Boolean,
    val messages: List<ChatMessage>,
    /** True only for apps with a full parser. Generic apps send visible text only, no history. */
    val historyApplies: Boolean,
) {
    val chatKey: ChatKey get() = ChatKey(packageName, title)
}
