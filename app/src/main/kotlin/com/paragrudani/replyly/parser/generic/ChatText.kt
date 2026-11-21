package com.paragrudani.replyly.parser.generic

/**
 * Text rules shared by every parser: cleaning, and recognising the screen "chrome" that is not a
 * message (timestamps, date separators, system notices, unread banners, status words).
 *
 * All English: the S23 runs in English. Every phrase here is an assumption listed in
 * docs/device-checklist/ticket-2.md.
 */
object ChatText {
    private val spaces = Regex(" +")

    // Zero-width spaces/joiners, LRM/RLM, bidi embeddings and isolates, word joiner, BOM.
    private fun isInvisible(c: Char): Boolean =
        c.code in 0x200B..0x200F || c.code in 0x202A..0x202E || c.code in 0x2066..0x2069 || c.code == 0x2060 ||
            c.code == 0xFEFF

    private fun isPadding(c: Char): Boolean = c.isWhitespace() || Character.isSpaceChar(c) || isInvisible(c)

    /**
     * Trims whitespace, no-break spaces (WhatsApp pads bubbles with them) and invisible direction
     * marks from both ends. Returns `null` for null or blank text. Inner characters are kept as is.
     */
    fun clean(raw: String?): String? = raw?.trim(::isPadding)?.ifEmpty { null }

    /** Single ASCII spaces, no invisible marks: the form every pattern below is matched against. */
    fun squash(text: String): String =
        buildString(text.length) {
            for (c in text) {
                when {
                    isInvisible(c) -> Unit
                    c.isWhitespace() || Character.isSpaceChar(c) -> append(' ')
                    else -> append(c)
                }
            }
        }.replace(spaces, " ").trim()

    // 6:42, 18:42, 6:42 pm, 6:42 PM, 6:42 p.m. (any space kind before am/pm).
    private val time = Regex("""^\d{1,2}:\d{2}(?: ?[ap]\.? ?m\.?)?$""", RegexOption.IGNORE_CASE)
    private val timeInside = Regex("""\d{1,2}:\d{2}(?:[ \u00A0\u202F]?[AaPp]\.?[ \u00A0\u202F]?[Mm]\.?)?""")

    /** The whole text is a clock time, as shown in a bubble. */
    fun isTime(text: String): Boolean = time.matches(squash(text))

    /** The first clock time inside [text], exactly as written there. */
    fun findTime(text: String): String? = timeInside.find(text)?.value

    private const val MONTH =
        """(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?""" +
            """|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?"""
    private const val WEEKDAY = """(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)"""
    private val datePatterns =
        listOf(
            Regex("""^(?:today|yesterday)$"""),
            Regex("""^$WEEKDAY$"""),
            Regex("""^(?:$WEEKDAY,? )?\d{1,2} $MONTH(?:,? \d{4})?$"""),
            Regex("""^(?:$WEEKDAY,? )?$MONTH \d{1,2}(?:,? \d{4})?$"""),
            Regex("""^\d{1,2}/\d{1,2}/\d{2,4}$"""),
            Regex("""^\d{4}-\d{2}-\d{2}$"""),
        )

    /** "Today", "YESTERDAY", "Monday", "21 November 2026", "November 21", "21/11/2026"... */
    fun isDateSeparator(text: String): Boolean {
        val s = squash(text).lowercase()
        return datePatterns.any { it.matches(s) }
    }

    private val unread = Regex("""^(?:\d+ )?unread messages?$""")

    /** "2 UNREAD MESSAGES", "1 unread message", "Unread messages". */
    fun isUnreadBanner(text: String): Boolean = unread.matches(squash(text).lowercase())

    /** WhatsApp's "Messages and calls are end-to-end encrypted..." notice (any variant). */
    fun isEncryptionNotice(text: String): Boolean = squash(text).lowercase().contains("end-to-end encrypted")

    /** Placeholders that stand where a message used to be. Never message text. */
    fun isDeletedPlaceholder(text: String): Boolean {
        val s = squash(text).lowercase()
        return s == "this message was deleted" ||
            s == "you deleted this message" ||
            s.startsWith("waiting for this message")
    }

    /**
     * Text that is never a message, wherever it appears: encryption notice, unread banner,
     * deleted-message placeholder. (Date words are only chrome when centred: someone can send "Today".)
     */
    fun isAlwaysChrome(text: String): Boolean =
        isEncryptionNotice(text) || isUnreadBanner(text) || isDeletedPlaceholder(text)

    private val bubbleLabels =
        setOf("edited", "forwarded", "forwarded many times", "read", "delivered", "sent", "seen", "pending", "starred")

    /** Small labels inside a bubble that are not the message: "Edited", "Forwarded", "Seen"... */
    fun isBubbleLabel(text: String): Boolean = squash(text).lowercase() in bubbleLabels

    private val tickWords = setOf("read", "delivered", "sent", "seen", "pending")

    /** Content descriptions of the ticks only outgoing bubbles carry. */
    fun isTickDescription(text: String): Boolean = squash(text).lowercase() in tickWords

    private val memberCount = Regex("""^\d[\d,.]* (?:members?|participants?|subscribers?)\b.*$""")

    /** What a chat's subtitle says about the chat kind. */
    fun groupHint(subtitle: String?): GroupHint {
        if (subtitle == null) return GroupHint.UNKNOWN
        val s = squash(subtitle).lowercase()
        return when {
            memberCount.matches(s) || s.contains("group info") -> GroupHint.GROUP

            // "Asha is typing…" names someone, which WhatsApp and Telegram only do in groups.
            someoneActive.containsMatchIn(s) -> GroupHint.GROUP

            // WhatsApp lists the members, me last: "Asha, Ravi, You".
            s.contains(
                ", ",
            ) && (s.endsWith(", you") || s.startsWith("you, ") || s.contains(", you, ")) -> GroupHint.GROUP

            isFixedStatus(s) || s.contains("contact info") || s == "business account" -> GroupHint.ONE_TO_ONE

            else -> GroupHint.UNKNOWN
        }
    }

    private val someoneActive = Regex(""" (?:is|are) (?:typing|recording)""")
    private val activeName = Regex("""^(.+?) (?:is|are) (?:typing|recording)""")

    /** "Asha" from the group subtitle "Asha is typing…"; `null` for any other subtitle. */
    fun activeMember(subtitle: String?): String? =
        subtitle?.let { activeName.find(squash(it))?.groupValues?.get(1) }?.takeUnless { it.contains(" and ") }

    /**
     * Subtitle phrases that carry no names: "online", "last seen ...", "typing…", "12 members".
     * "Asha is typing…" is not one of them (it names someone, and only appears in groups).
     */
    fun isFixedStatus(text: String): Boolean {
        val s = squash(text).lowercase()
        return s == "online" ||
            s.startsWith("last seen") ||
            s.startsWith("typing") ||
            s.startsWith("recording audio") ||
            s.startsWith("active ") ||
            s == "active now" ||
            s.startsWith("tap here for") ||
            s.startsWith("click here for") ||
            memberCount.matches(s)
    }

    /** "Pinned message", "Pinned Message #2": the label of a pinned bar, whose other line is message text. */
    fun isPinnedLabel(text: String): Boolean = squash(text).lowercase().startsWith("pinned message")

    private val badge = Regex("""^\d{1,4}\+?$""")

    /** A short all-digit text ("12", "99+"): an unread counter, never a title. */
    fun isBadge(text: String): Boolean = badge.matches(squash(text))

    private val labelledTime =
        Regex(
            """^(?:${bubbleLabels.joinToString("|")})[ ,·•]+(\d{1,2}:\d{2}(?: ?[ap]\.? ?m\.?)?)$""",
            RegexOption.IGNORE_CASE,
        )

    /** A bubble label fused with the time in one view: "Edited 6:42 pm". */
    fun isLabelledTime(text: String): Boolean = labelledTime.matches(squash(text))

    /** The clock time of a time leaf ("6:42 pm", or "edited 6:42 pm"), as written; `null` otherwise. */
    fun timeOf(text: String): String? =
        when {
            isTime(text) -> text
            isLabelledTime(text) -> findTime(text)
            else -> null
        }

    /** A toolbar line that is a status, not a chat title. */
    fun isStatusLine(text: String): Boolean =
        groupHint(text) != GroupHint.UNKNOWN || isFixedStatus(text) || squash(text).lowercase().contains(" is typing")
}

/** What the subtitle of a chat says about whether it is a group. */
enum class GroupHint { GROUP, ONE_TO_ONE, UNKNOWN }
