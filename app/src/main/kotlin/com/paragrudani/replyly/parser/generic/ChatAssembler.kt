package com.paragrudani.replyly.parser.generic

import com.paragrudani.replyly.parser.ChatMessage
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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
     * messages got a date, how many reply to me, and why [checkDates] dropped the screen's dates.
     * Dates and counts only, never chat text.
     */
    fun dateNote(
        separators: List<LocalDate?>,
        messages: List<ChatMessage>,
        dropped: String? = null,
    ): String {
        val shown = if (separators.isEmpty()) "" else " (${separators.joinToString { it?.toString() ?: "?" }})"
        return "date separators: ${separators.size}$shown, ${messages.count { it.date != null }} of " +
            "${messages.size} messages dated, ${messages.count { it.repliesToMe }} replies to me" +
            if (dropped != null) ", dates dropped: $dropped" else ""
    }

    /** The bubbles after [checkDates], and why it dropped their dates (`null`: kept). */
    data class DateCheck(
        val bubbles: List<RawBubble>,
        val dropped: String?,
    )

    /**
     * The per-screen plausibility pass every parser runs on its dated bubbles (issue #26). A wrong
     * exact date sticks in history for good, while no date costs only precision (history marks the
     * rows approx and a later read can date them), so when the screen's dates do not add up,
     * every date on it goes:
     * - **order**: the readable [separators] (top to bottom) must strictly increase, and the
     *   bubbles' dates must not decrease down the screen (two "TODAY"s, one of them stale);
     * - **stale "Today"**: a bubble dated the capture day whose time shown is more than
     *   [STALE_TODAY_MINUTES] after the capture time. The screen was drawn before midnight and
     *   read after it, so "Today" means yesterday;
     * - **future**: a date after the capture day (a separator never is). [ChatDates] already
     *   tries the other numeric order and gives `null` otherwise; this is the safety net.
     */
    fun checkDates(
        bubbles: List<RawBubble>,
        separators: List<LocalDate?>,
        capturedAt: LocalDateTime,
    ): DateCheck {
        val today = capturedAt.toLocalDate()
        val readable = separators.filterNotNull()
        val dates = bubbles.mapNotNull { it.date }
        val dropped =
            when {
                (readable + dates).any { it.isAfter(today) } -> {
                    "future date"
                }

                readable.zipWithNext().any { (a, b) -> !b.isAfter(a) } ||
                    dates.zipWithNext().any { (a, b) -> b.isBefore(a) } -> {
                    "separators out of order"
                }

                bubbles.any { it.date == today && shownAfter(it.timeShown, capturedAt) } -> {
                    "stale today"
                }

                else -> {
                    null
                }
            }
        return DateCheck(if (dropped == null) bubbles else bubbles.map { it.copy(date = null) }, dropped)
    }

    /** A time shown ("6:42 pm", "18:42") more than [STALE_TODAY_MINUTES] later than [capturedAt]'s. */
    private fun shownAfter(
        timeShown: String?,
        capturedAt: LocalDateTime,
    ): Boolean {
        val shown = timeShown?.let(::clockTime) ?: return false
        val limit = capturedAt.toLocalTime().plusMinutes(STALE_TODAY_MINUTES)
        // Within the last minutes of the day the limit wraps past midnight: nothing is later.
        if (limit.isBefore(capturedAt.toLocalTime())) return false
        return shown.isAfter(limit)
    }

    private val clock = Regex("""^(\d{1,2}):(\d{2})(?: ?([ap])\.? ?m\.?)?$""", RegexOption.IGNORE_CASE)

    /** "6:42 pm", "6:42 PM", "6:42 p.m.", "18:42" as a time of day; `null` otherwise. */
    fun clockTime(text: String): LocalTime? {
        val m = clock.matchEntire(ChatText.squash(text)) ?: return null
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        val half = m.groupValues[3].lowercase()
        val h24 =
            when {
                half.isEmpty() -> hour.takeIf { it in 0..23 }
                hour !in 1..12 -> null
                half == "a" -> hour % 12
                else -> hour % 12 + 12
            } ?: return null
        return if (minute in 0..59) LocalTime.of(h24, minute) else null
    }

    /** How far a bubble's time may be ahead of the capture time (clock drift, a slow capture). */
    const val STALE_TODAY_MINUTES = 5L

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
