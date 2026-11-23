package com.paragrudani.replyly.ui.debug

import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.parser.generic.ChatText
import com.paragrudani.replyly.parser.x.XText
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
 * - X dumps only (issue #30; `forX`, from the snapshot's package): post times ("2h", "Nov 19",
 *   "6:42 PM · 21 Nov 26 · 1,204 Views"), counts ("1.2K", "48 Likes"), the composer hints ("Post your
 *   reply", "Add a comment"...) and X's fixed UI words ("Reply", "Follow", "Show more"...; a post that
 *   is exactly such a word stays too) and the description of an image without alt text ("Image",
 *   "Photo") stay whole.
 *   A header line "Name @handle · 2h" keeps its "·" and time, and a "Replying to @a and @b" line its
 *   words, with each name and handle scrambled on its own, so a handle scrambles the same in a
 *   header, a "Replying to" line, the "@handle" node and any other text that mentions it (the
 *   rest of that text is scrambled as one string). A card's "From " stays before its domain.
 *   Post text, alt text and link titles are scrambled like any text.
 * - An X text field's hint stays readable whatever its wording or language ("Reply to @asha_dev",
 *   a hint in Hindi), with only its handles scrambled, and so does the field's text while it equals
 *   the hint (an empty field): the overlay's gate reads the hint, and DEVICE_CHECKLIST 15.4 needs it
 *   when the button does not show. See [xFieldHint].
 * - WhatsApp, Telegram and every other app scramble exactly as before issue #30.
 * - View ids, class names, bounds, flags and the package name are never touched.
 */
class Scrambler(
    private val key: ByteArray = randomKey(),
) {
    fun scramble(snapshot: ScreenSnapshot): ScreenSnapshot =
        snapshot.copy(root = scramble(snapshot.root, forX = snapshot.packageName in TargetApps.X_PACKAGES))

    private fun scramble(
        node: ScreenNode,
        forX: Boolean,
    ): ScreenNode {
        val fieldHint = if (forX) xFieldHint(node) else null
        return node.copy(
            text = node.text?.let { if (fieldHint != null && it == node.hint) fieldHint else scrambleText(it, forX) },
            contentDescription = node.contentDescription?.let { scrambleText(it, forX) },
            hint = node.hint?.let { fieldHint ?: scrambleText(it, forX) },
            children = node.children.map { scramble(it, forX) },
        )
    }

    /**
     * The hint of an X text field, readable: only each @handle in it is scrambled (as everywhere
     * else in the dump). `null` (scramble it like any text) for a node that is not editable, or a
     * hint that is not one short line.
     *
     * A hint is the app's own UI string (`setHint` from X's resources: "Post your reply", "Reply to
     * @asha_dev", the same in the user's UI language), never what someone wrote: post text is a
     * node's text, not its hint. The only user-derived part X puts in one is the handle being
     * answered, which is scrambled. The length and line limits keep anything else out.
     */
    private fun xFieldHint(node: ScreenNode): String? {
        val hint = node.hint ?: return null
        if (!node.editable || '\n' in hint || hint.length > MAX_HINT) return null
        return handleToken.replace(hint) { m -> scrambleWith(m.value) }
    }

    /** Scrambles one string; [forX] adds the X rules (an X dump). */
    fun scrambleText(
        text: String,
        forX: Boolean = false,
    ): String {
        if (isKeptWhole(text, forX)) return text
        telegramCell(text)?.let { return it }
        if (forX) xLine(text)?.let { return it }
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

    /** X lines with structure the parser reads: "Replying to @a and @b", "Name @handle · 2h", "From x.com". */
    private fun xLine(text: String): String? {
        if (XText.isReplyingTo(text)) {
            // Only handles and the words "Replying to", "and", "others" (XText checks the form).
            return handleToken.replace(text) { m -> scrambleWith(m.value) }
        }
        XText.header(text)?.takeIf { it.time != null }?.let { h ->
            val name = h.name?.let { scrambleWith(it) + " " }.orEmpty()
            return "$name${scrambleWith("@" + h.handle)} · ${h.time}"
        }
        fromDomain.matchEntire(text)?.let { m -> return m.groupValues[1] + scrambleWith(m.groupValues[2]) }
        timePart.matchEntire(text)?.takeIf { XText.isPostTime(it.groupValues[1]) }?.let { return text }
        if (handleToken.containsMatchIn(text)) {
            // "Account @kiran_test", "Thanks @ravi_k": each handle on its own, the rest piece by piece.
            val out = StringBuilder()
            var at = 0
            handleToken.findAll(text).forEachIndexed { i, m ->
                out.append(scrambleWith(text.substring(at, m.range.first), seed = "$text\u0000$i"))
                out.append(scrambleWith(m.value))
                at = m.range.last + 1
            }
            out.append(scrambleWith(text.substring(at), seed = "$text\u0000end"))
            return out.toString()
        }
        return null
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
        private const val HANDLE = """@[A-Za-z0-9_]{1,15}"""
        private val handleToken = Regex("""(?<![A-Za-z0-9_@])$HANDLE(?![A-Za-z0-9_])""")
        private val fromDomain = Regex("""^(From )([a-z0-9-]+(?:\.[a-z0-9-]+)+)$""")
        private val timePart = Regex("""^·\s?(.+)$""")

        private const val MAX_HINT = 80

        /**
         * The X composer hints, exactly (lower case), kept in any text of an X dump. A field's own
         * hint is kept whatever it says ([xFieldHint]).
         */
        private val xHints =
            setOf(
                "post your reply",
                "tweet your reply",
                "add a comment",
                "add a comment…",
                "add a comment...",
                "what’s happening?",
                "what's happening?",
                "what is happening?!",
                "start a message",
                "start a new message",
                "search",
                "search x",
            )

        private val activeName = Regex("""^(.+?)( (?:is|are) (?:typing|recording audio)(?:…|\.\.\.)?)$""")

        /**
         * Whole strings that hold no private content and that the parsers need to read. [forX] adds
         * the X ones (times, counts, hints, UI words, "Image"), for X dumps only.
         */
        fun isKeptWhole(
            text: String,
            forX: Boolean = false,
        ): Boolean {
            val s = ChatText.squash(text).lowercase()
            val common =
                ChatText.isTime(text) || separators.any { it.matches(s) } || ChatText.isUnreadBanner(text) ||
                    ChatText.isBubbleLabel(text) || s in deleted || s in encryptionNotices || online.matches(s) ||
                    lastSeen.matches(s) || members.matches(s) || typing.matches(s)
            return common || (forX && isKeptWholeOnX(text, s))
        }

        private fun isKeptWholeOnX(
            text: String,
            squashed: String,
        ): Boolean =
            squashed in xHints || XText.isPostTime(text) || XText.isAbsoluteTime(text) || XText.isCount(text) ||
                XText.isChromeWord(text) || XText.isGenericMedia(text)

        private fun randomKey(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
    }
}
