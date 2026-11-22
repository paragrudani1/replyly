package com.paragrudani.replyly.parser.generic

import com.paragrudani.replyly.parser.ChatMessage
import java.time.LocalDate

/** One bubble as a parser read it, before senders are filled in. */
data class RawBubble(
    val side: Side,
    /** Sender name label shown with this bubble (groups only), if any. */
    val label: String?,
    /** `null` for a placeholder such as "This message was deleted": no message, but it keeps the sender run. */
    val text: String?,
    val timeShown: String?,
    /** See [ChatMessage.date]. */
    val date: LocalDate? = null,
    /** See [ChatMessage.repliesToMe]. */
    val repliesToMe: Boolean = false,
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
            out +=
                ChatMessage(
                    sender = sender,
                    text = text,
                    timeShown = bubble.timeShown,
                    isMe = isMe,
                    date = bubble.date,
                    repliesToMe = bubble.repliesToMe && !isMe,
                )
        }
        return out
    }

    /**
     * A [com.paragrudani.replyly.parser.ParseReport] note about dates, for the debug screen: the
     * separators read (their resolved dates, `?` for one that could not be read), how many
     * messages got a date and how many reply to me. Dates and counts only, never chat text.
     */
    fun dateNote(
        separators: List<LocalDate?>,
        messages: List<ChatMessage>,
    ): String {
        val shown = if (separators.isEmpty()) "" else " (${separators.joinToString { it?.toString() ?: "?" }})"
        return "date separators: ${separators.size}$shown, ${messages.count { it.date != null }} of " +
            "${messages.size} messages dated, ${messages.count { it.repliesToMe }} replies to me"
    }

    /**
     * The date for each item at [tops] (screen y, in reading order): the date of the last
     * separator at or above it, `null` above the first one. A separator with a `null` date (text
     * that could not be read) ends the date above it.
     */
    fun datesBelow(
        separators: List<Pair<Int, LocalDate?>>,
        tops: List<Int>,
    ): List<LocalDate?> {
        val sorted = separators.sortedBy { it.first }
        var k = 0
        var current: LocalDate? = null
        return tops.map { top ->
            while (k < sorted.size && sorted[k].first <= top) current = sorted[k++].second
            current
        }
    }
}
