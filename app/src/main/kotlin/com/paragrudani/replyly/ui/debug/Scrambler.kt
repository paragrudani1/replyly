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
 * - The same string scrambles the same way within one dump (so "ok", "ok" stays a pair), using
 *   HMAC-SHA256 with a random key that is thrown away: the output cannot be reversed or compared
 *   across dumps.
 * - Screen chrome with no private content stays readable (times, date separators, the encryption
 *   notice, unread banners, "Edited", "online"), and so do a few status words inside other text
 *   ("Sent today at 6:42 PM, Read"), because the parsers depend on them.
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
        if (ChatText.isNonPrivateChrome(text)) return text
        val letters = LetterStream(text)
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (!isWordChar(text.codePointAt(i))) {
                val cp = text.codePointAt(i)
                out.appendCodePoint(cp)
                i += Character.charCount(cp)
                continue
            }
            var end = i
            while (end < text.length &&
                isWordChar(text.codePointAt(end))
            ) {
                end += Character.charCount(text.codePointAt(end))
            }
            val word = text.substring(i, end)
            if (word.lowercase() in keptWords) {
                out.append(word)
            } else {
                word.codePoints().forEach { cp ->
                    val n = letters.next()
                    out.append(if (Character.isUpperCase(cp)) 'A' + n else 'a' + n)
                }
            }
            i = end
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

    /** Letter indexes 0..25 derived from HMAC(key, text), so equal texts give equal output. */
    private inner class LetterStream(
        private val text: String,
    ) {
        private var block = ByteArray(0)
        private var used = 0
        private var counter = 0

        fun next(): Int {
            if (used == block.size) {
                val mac = Mac.getInstance(HMAC)
                mac.init(SecretKeySpec(key, HMAC))
                mac.update(text.toByteArray(Charsets.UTF_8))
                mac.update(byteArrayOf(0, (counter shr 8).toByte(), counter.toByte()))
                block = mac.doFinal()
                used = 0
                counter++
            }
            return (block[used++].toInt() and 0xFF) % 26
        }
    }

    private companion object {
        const val HMAC = "HmacSHA256"

        /** Status words the parsers read inside longer text (Telegram's "Sent today at 6:42 PM, Read"). */
        val keptWords =
            setOf(
                "am",
                "pm",
                "sent",
                "received",
                "read",
                "unread",
                "seen",
                "delivered",
                "edited",
                "today",
                "yesterday",
                "at",
                "forwarded",
                "from",
                "you",
            )

        fun randomKey(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
    }
}
