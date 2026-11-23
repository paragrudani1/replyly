package com.paragrudani.replyly.parser.x

import com.paragrudani.replyly.parser.generic.ChatText

/** X handles: 1 to 15 letters, digits or `_`, compared without case. */
object XHandles {
    private val handle = Regex("""^[A-Za-z0-9_]{1,15}$""")
    private val inText = Regex("""(?<![A-Za-z0-9_@])@([A-Za-z0-9_]{1,15})(?![A-Za-z0-9_])""")

    /**
     * The "My X handle" setting as stored: trimmed, without leading `@`. Blank gives `""`; anything
     * that is not a valid handle gives `null`.
     */
    fun normalize(raw: String?): String? {
        val s = raw?.trim()?.trimStart('@')?.trim() ?: return ""
        if (s.isEmpty()) return ""
        return s.takeIf { handle.matches(it) }
    }

    /** Same handle, any case, with or without `@`. Two `null`s or blanks never match. */
    fun same(
        a: String?,
        b: String?,
    ): Boolean {
        val x = normalize(a)?.ifEmpty { null } ?: return false
        val y = normalize(b)?.ifEmpty { null } ?: return false
        return x.equals(y, ignoreCase = true)
    }

    /** Every `@handle` in [text], without the `@`, in order. */
    fun findAll(text: String): List<String> = inText.findAll(text).map { it.groupValues[1] }.toList()
}

/** Text patterns of the X app (English UI). Pure, so the parser and the Scrambler share them. */
object XText {
    private const val HANDLE = """[A-Za-z0-9_]{1,15}"""
    private const val MONTH =
        """(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?""" +
            """|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?"""
    private const val CLOCK = """\d{1,2}[:.]\d{2}(?:\s?[ap]\.?\s?m\.?)?"""
    private const val DAY_MONTH = """(?:\d{1,2} $MONTH|$MONTH \d{1,2})(?:,? \d{2,4})?"""

    /** Relative or short post times: "now", "45s", "5m", "2h", "3d", "Nov 21", "21 Nov 25", "21/11/25". */
    private val relative =
        Regex(
            """^(?:now|just now|\d{1,2}\s?[smhd]|\d{1,2}\s?(?:sec|min|mins|hr|hrs)|$DAY_MONTH|\d{1,2}/\d{1,2}/\d{2,4})$""",
            RegexOption.IGNORE_CASE,
        )

    /** The detail view's absolute line: "6:42 PM · 21 Nov 26", optionally "· 1,204 Views". */
    private val absolute =
        Regex("""^$CLOCK\s*·\s*$DAY_MONTH(?:\s*·\s*[\d.,]+\s?[KMB]?\s+views?)?$""", RegexOption.IGNORE_CASE)

    /**
     * "Replying to @a", "Replying to @a and @b", "Replying to @a, @b and 2 others": only handles
     * after "Replying to", so a post text such as "Replying to @a was a mistake" is not one.
     */
    private val replyingTo =
        Regex(
            """^replying to\s+@$HANDLE(?:(?:\s*,\s*(?:and\s+)?|\s+and\s+|\s*&\s*|\s+)@$HANDLE)*""" +
                """(?:,?\s+and\s+(?:\d+\s+)?others?)?$""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * A header line: "@asha_dev", "Asha Dev @asha_dev", "@asha_dev · 2h", "Asha Dev @asha_dev · 2h".
     * Groups: 1 name (optional), 2 handle, 3 time (optional).
     */
    private val header = Regex("""^(?:(.+?)\s+)?@([A-Za-z0-9_]{1,15})(?:\s*·\s*(.+))?$""")

    /** Counts under a post ("3", "1.2K", "12,345"), alone or with a word ("20 Likes", "1,204 Views"). */
    private val count =
        Regex(
            """^(?:\d{1,3}(?:,\d{3})+|\d+(?:\.\d)?\s?[KMB]|\d+)""" +
                """(?:\s+(?:likes?|views?|reposts?|repl(?:y|ies)|quotes?|bookmarks?))?$""",
            RegexOption.IGNORE_CASE,
        )

    private val domain =
        Regex("""^(from\s+)?((?:[a-z0-9-]+\.)+[a-z]{2,})(?:/\S*)?$""", RegexOption.IGNORE_CASE)

    /**
     * Words of the X UI around posts and composers that are never post text (English UI only).
     * Lower case, squashed.
     */
    private val chrome =
        setOf(
            "reply",
            "repost",
            "reposts",
            "quote",
            "quotes",
            "like",
            "likes",
            "share",
            "bookmark",
            "views",
            "view",
            "show this thread",
            "show more",
            "show replies",
            "show more replies",
            "show additional replies",
            "translate post",
            "translate",
            "promoted",
            "ad",
            "pinned",
            "follow",
            "following",
            "subscribe",
            "post",
            "cancel",
            "drafts",
            "everyone can reply",
            "view post engagements",
            "view quotes",
            "more",
            "more options",
            "add photos or video",
            "add gif",
            "add poll",
            "tag location",
            "gif",
            "alt",
            "post your reply",
            "add a comment",
            "add a comment…",
            "add a comment...",
            "reply to",
            "home",
            "for you",
            "see new posts",
            "relevant people",
            "discover more",
            "sources",
        )

    /** Content descriptions of media without alt text, read as "image" (lower case). */
    private val genericMedia = setOf("image", "photo", "picture", "gif", "video", "embedded video", "media")

    fun isPostTime(text: String): Boolean = relative.matches(ChatText.squash(text))

    fun isAbsoluteTime(text: String): Boolean = absolute.matches(ChatText.squash(text))

    fun isReplyingTo(text: String): Boolean = replyingTo.matches(ChatText.squash(text))

    /** Handles of a "Replying to" line (no `@`); empty when [text] is not one. */
    fun replyingToHandles(text: String): List<String> =
        if (isReplyingTo(text)) XHandles.findAll(ChatText.squash(text)) else emptyList()

    class Header(
        val name: String?,
        val handle: String,
        val time: String?,
    )

    /**
     * Reads a header line. `null` unless the line is only a name, a handle and a time: so "@ravi_k
     * agreed" (a reply that starts with a mention) is not a header. A name that itself ends with an
     * @handle, or is longer than 50 characters, is not a name either, and a header is one line.
     * "Thanks @ravi_k" still parses (name "Thanks", no time): the reader only takes a header with a
     * time, or a bare "@handle" with a name next to or above it.
     */
    fun header(text: String): Header? {
        if ('\n' in text.trim()) return null
        val s = ChatText.squash(text)
        if (isReplyingTo(s)) return null
        val m = header.matchEntire(s) ?: return null
        val name =
            m.groups[1]
                ?.value
                ?.trim()
                ?.ifEmpty { null }
        val time =
            m.groups[3]
                ?.value
                ?.trim()
                ?.ifEmpty { null }
        if (time != null && !isPostTime(time)) return null
        if (name != null && (name.length > MAX_NAME || '@' in name || name.endsWith("·"))) return null
        return Header(name, m.groupValues[2], time)
    }

    /** "·" or "· 2h" on its own: the time part of a header split over two nodes. */
    fun timePart(text: String): String? {
        val s = ChatText.squash(text).removePrefix("·").trim()
        return s.takeIf { isPostTime(it) }
    }

    fun isCount(text: String): Boolean = count.matches(ChatText.squash(text))

    fun isChrome(text: String): Boolean = isChromeWord(text) || isCount(text)

    /** One of the fixed UI words ("Reply", "Follow", "Show more"...), exactly. */
    fun isChromeWord(text: String): Boolean {
        val s = ChatText.squash(text).lowercase()
        return s in chrome || s == "·"
    }

    /** `example.com` from "example.com" or "From example.com"; `null` if not a domain line. */
    fun domainOf(text: String): String? = domain.matchEntire(ChatText.squash(text))?.groupValues?.get(2)

    /** "From example.com": a link card's domain line, never post text. */
    fun isFromDomain(text: String): Boolean = domain.matchEntire(ChatText.squash(text))?.groups?.get(1) != null

    /** The alt text of a media description, or `null` for a generic "Image" without alt text. */
    fun altText(description: String): String? {
        val s = ChatText.squash(description)
        return s.takeUnless { isGenericMedia(it) }
    }

    /** A media description with no alt text in it ("Image", "Photo."). */
    fun isGenericMedia(description: String): Boolean =
        ChatText.squash(description).lowercase().trimEnd('.') in genericMedia

    private const val MAX_NAME = 50
}
