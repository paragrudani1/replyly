package com.paragrudani.replyly.ui.debug

import com.paragrudani.replyly.parser.generic.ChatText
import com.paragrudani.replyly.screen.ScreenNode
import com.paragrudani.replyly.screen.ScreenSnapshot
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Makes a dump shareable without its private text, while keeping what the parser looks at.
 *
 * - Every letter becomes a random letter of the same case (other scripts become lowercase Latin),
 *   one per code point, so lengths and line breaks stay. Digits, emoji, punctuation and spaces stay.
 *   Digits include phone numbers: crop or check those before sharing.
 * - The letters come from HMAC-SHA256 of the whole string, with a random key thrown away after the
 *   dump: the same string scrambles the same way within one dump (so "ok", "ok" stays a pair), and
 *   the output cannot be reversed or compared across dumps.
 * - Names are scrambled on their own, so the same name gives the same scramble wherever it appears:
 *   the first line of a Telegram cell, the name in "Asha is typing…", each name in "Asha, Ravi, You".
 *   Fixtures made from a scrambled dump keep the "same sender" signal.
 * - Only whole strings that match a strict pattern stay readable: [isKeptWhole] (times, English
 *   date separators such as "TODAY" or "21 November 2026", "online", "last seen…", "N members…", "typing…", the exact encryption notices,
 *   unread banners, deleted placeholders, bubble labels), and the last line of a Telegram cell when
 *   it is exactly a status line ("Sent today at 6:42 PM, Read"). Words inside a message are never
 *   kept, whatever they are.
 * - View ids, class names, bounds, flags and the package name are never touched.
 */
class Scrambler(
    private val key: ByteArray = randomKey(),
) {
    fun scramble(snapshot: ScreenSnapshot): ScreenSnapshot = snapshot.copy(root = scramble(snapshot.root))

    private fun scramble(node: ScreenNode): ScreenNode =
        node.copy(
            text = node.text?.let(::scrambleText),
            contentDescription = node.contentDescription?.let(::scrambleText),
            hint = node.hint?.let(::scrambleText),
            children = node.children.map(::scramble),
        )

    fun scrambleText(text: String): String {
        if (isKeptWhole(text)) return text
        telegramCell(text)?.let { return it }
        activeName.matchEntire(text)?.let { m -> return scrambleWith(m.groupValues[1]) + m.groupValues[2] }
        memberList(text)?.let { return it }
        return scrambleWith(text)
    }

    /**
     * A Telegram cell ends with a status line: keep it, scramble the first line as a name (same name,
     * same scramble), keep a "Forwarded from " prefix on a line that is not the message's last, and
     * scramble the rest as one string.
     */
    private fun telegramCell(text: String): String? {
        val lines = text.split('\n')
        if (lines.size < 2 || !cellStatus.matches(ChatText.squash(lines.last()).lowercase())) return null
        val body = lines.dropLast(1)
        val out = mutableListOf<String>()
        var rest = body
        if (body.size >= 2) {
            out += scrambleLine(body.first())
            rest = body.drop(1)
        }
        while (rest.size >= 2 && forwarded.matches(rest.first())) {
            out += scrambleLine(rest.first())
            rest = rest.drop(1)
        }
        out += scrambleWith(rest.joinToString("\n"), seed = text)
        return (out + lines.last()).joinToString("\n")
    }

    /** A name line, or "Forwarded from <name>" with the name scrambled on its own. */
    private fun scrambleLine(line: String): String {
        val m = forwarded.matchEntire(line) ?: return scrambleWith(line)
        return m.groupValues[1] + scrambleWith(m.groupValues[2])
    }

    /** WhatsApp's group subtitle "Asha, Ravi, +91 98765 43210, You": each name on its own, "You" kept. */
    private fun memberList(text: String): String? {
        val parts = text.split(", ")
        if (parts.size < 2 || parts.none { it == "You" }) return null
        return parts.joinToString(", ") { if (it == "You") it else scrambleWith(it) }
    }

    private fun scrambleWith(
        text: String,
        seed: String = text,
    ): String {
        val letters = LetterStream(seed)
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isWordChar(cp)) {
                val n = letters.next()
                out.append(if (Character.isUpperCase(cp)) 'A' + n else 'a' + n)
            } else {
                out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun isWordChar(cp: Int): Boolean =
        Character.isLetter(cp) ||
            Character.getType(cp).let {
                it == Character.NON_SPACING_MARK.toInt() ||
                    it == Character.COMBINING_SPACING_MARK.toInt() ||
                    it == Character.ENCLOSING_MARK.toInt()
            }

    /** Letter indexes 0..25 derived from HMAC(key, seed), so equal seeds give equal output. */
    private inner class LetterStream(
        private val seed: String,
    ) {
        private var block = ByteArray(0)
        private var used = 0
        private var counter = 0

        fun next(): Int {
            if (used == block.size) {
                val mac = Mac.getInstance(HMAC)
                mac.init(SecretKeySpec(key, HMAC))
                mac.update(seed.toByteArray(Charsets.UTF_8))
                mac.update(byteArrayOf(0, (counter shr 8).toByte(), counter.toByte()))
                block = mac.doFinal()
                used = 0
                counter++
            }
            return (block[used++].toInt() and 0xFF) % 26
        }
    }

    companion object {
        private const val HMAC = "HmacSHA256"
        private const val TIME = """\d{1,2}:\d{2}(?: ?[ap]\.? ?m\.?)?"""

        private val online = Regex("""^(?:online|active now)$""")
        private val lastSeen =
            Regex(
                """^last seen(?: recently| within a (?:week|month)| a long time ago| (?:today|yesterday)(?: at $TIME)?| at $TIME)?$""",
            )
        private val members = Regex("""^\d[\d,.]* (?:members?|participants?|subscribers?)(?:, \d[\d,.]* online)?$""")
        private val typing = Regex("""^(?:typing|recording audio)(?:…|\.\.\.)?$""")
        private val encryptionNotices =
            setOf(
                "messages and calls are end-to-end encrypted. tap to learn more.",
                "messages and calls are end-to-end encrypted. no one outside of this chat, not even whatsapp, " +
                    "can read or listen to them. tap to learn more.",
                "messages and calls are end-to-end encrypted. only people in this chat can read, listen to, " +
                    "or share them. learn more.",
            )
        private val deleted = setOf("this message was deleted", "you deleted this message")

        /** The last line of a Telegram cell, exactly: "Sent today at 6:42 PM, Read", "Received yesterday at 18:02". */
        private val cellStatus =
            Regex(
                """^(?:sent|received)""" +
                    """(?: (?:today|yesterday|on [a-z]{3,9} \d{1,2}(?:, \d{4})?|on \d{1,2} [a-z]{3,9}(?: \d{4})?))?""" +
                    """ at $TIME(?:, (?:read|unread|seen|delivered|edited|sent|pending))*$""",
            )
        private val forwarded = Regex("""^(Forwarded from )(.+)$""")

        private const val MONTH =
            """(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?""" +
                """|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?"""
        private const val WEEKDAY = """(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)"""

        /**
         * Date separators kept whole: the narrow English list from before issue #26, not every form
         * [ChatText.isDateSeparator] reads. That one also takes "कल", "21.11.26" or "10-10-10",
         * which are ordinary bubble texts too, and would leave them readable in a shared dump.
         */
        private val separators =
            listOf(
                Regex("""^(?:today|yesterday)$"""),
                Regex("""^$WEEKDAY$"""),
                Regex("""^(?:$WEEKDAY,? )?\d{1,2} $MONTH(?:,? \d{4})?$"""),
                Regex("""^(?:$WEEKDAY,? )?$MONTH \d{1,2}(?:,? \d{4})?$"""),
                Regex("""^\d{1,2}/\d{1,2}/\d{2,4}$"""),
                Regex("""^\d{4}-\d{2}-\d{2}$"""),
            )
        private val activeName = Regex("""^(.+?)( (?:is|are) (?:typing|recording audio)(?:…|\.\.\.)?)$""")

        /** Whole strings that hold no private content and that the parsers need to read. */
        fun isKeptWhole(text: String): Boolean {
            val s = ChatText.squash(text).lowercase()
            return ChatText.isTime(text) || separators.any { it.matches(s) } || ChatText.isUnreadBanner(text) ||
                ChatText.isBubbleLabel(text) || s in deleted || s in encryptionNotices || online.matches(s) ||
                lastSeen.matches(s) || members.matches(s) || typing.matches(s)
        }

        private fun randomKey(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
    }
}
