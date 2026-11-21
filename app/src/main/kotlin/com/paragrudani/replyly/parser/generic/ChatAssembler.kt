package com.paragrudani.replyly.parser.generic

import com.paragrudani.replyly.parser.ChatMessage

/** One bubble as a parser read it, before senders are filled in. */
data class RawBubble(
    val side: Side,
    /** Sender name label shown with this bubble (groups only), if any. */
    val label: String?,
    /** `null` for a placeholder such as "This message was deleted": no message, but it keeps the sender run. */
    val text: String?,
    val timeShown: String?,
)

/** Turns bubbles into messages: right = me; senders from labels, carried down a run. */
object ChatAssembler {
    /**
     * In a group, a name label starts a run of bubbles from that sender; my own bubble ends the run.
     * A bubble before any label (its label scrolled off the top) has an unknown sender: `null`.
     * In a 1:1 chat the sender of every incoming bubble is the chat title. Generic apps pass
     * [oneToOneSender] = `null`, since they cannot tell a group from a 1:1 chat.
     */
    fun messages(
        bubbles: List<RawBubble>,
        isGroup: Boolean,
        oneToOneSender: String?,
    ): List<ChatMessage> {
        var runSender: String? = null
        val out = mutableListOf<ChatMessage>()
        for (bubble in bubbles) {
            val isMe = bubble.side == Side.RIGHT
            val sender =
                when {
                    isMe -> null
                    isGroup -> bubble.label ?: runSender
                    else -> oneToOneSender
                }
            runSender = if (isMe) null else sender
            val text = bubble.text ?: continue
            out += ChatMessage(sender = sender, text = text, timeShown = bubble.timeShown, isMe = isMe)
        }
        return out
    }
}
