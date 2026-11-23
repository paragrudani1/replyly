package com.paragrudani.replyly.llm

import com.paragrudani.replyly.parser.x.XContext

/**
 * Hashtags and @-mentions in an X reply (issue #46 §4, decided by the product owner). Pure.
 *
 * The X prompt already asks for none; this is the safety net in the output parser. Each kind is
 * kept only when it is my habit ([keep]): "My X voice" or one of my own posts on screen
 * (`isMine`, quoted ones too) contains one. Otherwise [strip] removes it:
 * - a run of them at the start or the end of the reply (tags separated only by whitespace) goes
 *   entirely, with its whitespace. A trailing run may be followed by a tail of whitespace, emoji
 *   and `.!?…。！？` (`agree #kotlin #dev 🚀`): the run still goes, punctuation left dangling before
 *   it (`,`, `—`, see [XLength.DANGLING]) goes too, and the tail stays if it holds an emoji or the
 *   text no longer ends a sentence (`Great point! #kotlin.` → `Great point!`);
 * - one inside the text keeps its word and loses the sigil (`love #Kotlin for this` →
 *   `love Kotlin for this`), so the sentence still reads.
 *
 * What counts follows twitter-text v3 (`Regex.VALID_HASHTAG`, `Regex.VALID_MENTION_OR_LIST` and
 * the `INVALID_*_MATCH_END` checks in https://github.com/twitter/twitter-text):
 * - **hashtag**: `#` or `＃` not after a letter, mark, digit, `_`, `&` or a hashtag special
 *   character, not followed by U+FE0F or U+20E3 (a keycap), then letters, marks, digits and the
 *   special characters (`_`, ZWNJ/ZWJ, `・`, `〜`, `゛゜`, `·`, the Hebrew geresh and gershayim…:
 *   twitter-text's `hashtagSpecialChars`) with at least one letter or mark (any plane), and not
 *   followed by `#` or `://`. So `C#`, `#1` and `&#39;` are not hashtags.
 * - **mention**: `@` or `＠` not after an ASCII letter or digit or `_!#$%&*@＠`, then 1 to 20 of
 *   `[A-Za-z0-9_]`, not followed by more of those, another `@`, a Latin accented letter or
 *   combining mark (twitter-text's `latinAccentChars`), or `://`. So an e-mail address is not a
 *   mention, while `@ravi_k然后` is one, as on X.
 * - neither inside a URL ([XLength.urlRanges]): twitter-text drops entities that overlap a URL.
 *
 * Invariants (`XTagsPropertyTest`): the result never adds text (it is the input with sigils and
 * leading/trailing runs removed); stripping twice gives the same text; a kept kind is untouched.
 */
object XTags {
    /** Which kinds stay. `Keep(true, true)` makes [strip] a no-op. */
    data class Keep(
        val hashtags: Boolean,
        val mentions: Boolean,
    )

    /** A kind stays when my X voice or any of my own posts on screen uses it. */
    fun keep(
        myXVoice: String,
        x: XContext,
    ): Keep {
        val mine =
            listOf(myXVoice) +
                x.thread
                    .flatMap { listOfNotNull(it, it.quoted) }
                    .filter { it.isMine }
                    .map { it.text }
        return Keep(
            hashtags = mine.any { hashtags(it).isNotEmpty() },
            mentions = mine.any { mentions(it).isNotEmpty() },
        )
    }

    /** [text] without the kinds [keep] does not keep, trimmed; see the class KDoc. */
    fun strip(
        text: String,
        keep: Keep,
    ): String {
        if (keep.hashtags && keep.mentions) return text
        // Removing a sigil can make another tag (`##meta`): repeat until nothing changes.
        var current = text
        while (true) {
            val next = stripOnce(current, keep)
            if (next == current) return current
            current = next
        }
    }

    private fun stripOnce(
        input: String,
        keep: Keep,
    ): String {
        val text = input.trim()
        val tags =
            buildList {
                if (!keep.hashtags) addAll(hashtags(text))
                if (!keep.mentions) addAll(mentions(text))
            }.sortedBy { it.first }
        if (tags.isEmpty()) return text
        var start = 0
        var first = 0
        while (first < tags.size && tags[first].first == start && endsWord(text, tags[first])) {
            start = skipSpace(text, tags[first].last + 1)
            first++
        }
        // A trailing run may sit before a tail of whitespace, emoji and sentence punctuation.
        val tailStart = tailStart(text, start)
        var end = tailStart
        var last = tags.lastIndex
        var gap: Char? = null
        while (true) {
            var moved = false
            while (last >= first && tags[last].last + 1 == end && startsWord(text, tags[last])) {
                gap = text[tags[last].first - 1]
                end = text.substring(0, tags[last].first).trimEnd().length
                last--
                moved = true
            }
            if (!moved) break
            // Punctuation the run leaves dangling (`point, #a`, `agree — #a`) goes with it.
            while (end > start && (text[end - 1] in XLength.DANGLING || text[end - 1].isWhitespace())) end--
        }
        if (gap == null) end = text.length
        val sigils = (first..last).map { tags[it].first }.toSet()
        val body =
            buildString {
                for (i in start until end) if (i !in sigils) append(text[i])
            }.trim()
        if (gap == null || body.isEmpty()) return body
        val tail = text.substring(tailStart)
        val bare = tail.trimStart()
        return when {
            bare.isEmpty() -> body
            hasEmoji(bare) -> body + (tail.firstOrNull()?.takeIf { it.isWhitespace() } ?: gap) + bare
            body.last() in XLength.SENTENCE_END || body.last() == '…' -> body
            else -> body + bare
        }
    }

    /** Where the tail of whitespace, emoji and `.!?…。！？` at the end of [text] starts (not before [from]). */
    private fun tailStart(
        text: String,
        from: Int,
    ): Int {
        var i = text.length
        while (i > from) {
            val cp = text.codePointBefore(i)
            if (!isTail(cp)) break
            i -= Character.charCount(cp)
        }
        return i
    }

    private fun isTail(cp: Int): Boolean =
        Character.isWhitespace(cp) ||
            cp == 0x00A0 ||
            (cp < 0x10000 && cp.toChar() in TAIL_PUNCTUATION) ||
            isEmojiPart(cp)

    private fun isEmojiPart(cp: Int): Boolean =
        when (Character.getType(cp).toByte()) {
            Character.OTHER_SYMBOL, Character.MODIFIER_SYMBOL -> true
            else -> cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 || cp in 0xE0020..0xE007F
        }

    private fun hasEmoji(text: String): Boolean =
        text.codePoints().anyMatch { Character.getType(it).toByte() == Character.OTHER_SYMBOL }

    private const val TAIL_PUNCTUATION = ".!?…。！？"

    private fun endsWord(
        text: String,
        tag: IntRange,
    ) = tag.last + 1 == text.length || text[tag.last + 1].isWhitespace()

    private fun startsWord(
        text: String,
        tag: IntRange,
    ) = tag.first == 0 || text[tag.first - 1].isWhitespace()

    private fun skipSpace(
        text: String,
        from: Int,
    ): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** Hashtags in [text] (sigil included), outside URLs. */
    internal fun hashtags(text: String): List<IntRange> =
        outsideUrls(
            text,
            HASHTAG.findAll(text).filter { m ->
                m.value.drop(1).codePoints().anyMatch {
                    Character.isLetter(it) ||
                        isMark(it)
                }
            },
        )

    /** @-mentions in [text] (sigil included), outside URLs. */
    internal fun mentions(text: String): List<IntRange> = outsideUrls(text, MENTION.findAll(text))

    private fun outsideUrls(
        text: String,
        matches: Sequence<MatchResult>,
    ): List<IntRange> {
        val urls = XLength.urlRanges(text)
        return matches
            .map { it.range }
            .filter { tag -> urls.none { url -> tag.first <= url.last && url.first <= tag.last } }
            .toList()
    }

    private fun isMark(cp: Int): Boolean =
        when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }

    /** twitter-text's `hashtagSpecialChars`: `_`, ZWNJ, ZWJ, ꙾, ־, ׳, ״, ～, 〜, ゛, ゜, ゠, ・, 〃, ་, ༌, ·. */
    private const val HASHTAG_SPECIAL =
        """_\x{200C}\x{200D}\x{A67E}\x{05BE}\x{05F3}\x{05F4}\x{FF5E}\x{301C}\x{309B}\x{309C}\x{30A0}\x{30FB}\x{3003}\x{0F0B}\x{0F0C}\x{00B7}"""

    private val HASHTAG =
        Regex(
            """(?<![\p{L}\p{M}\p{Nd}&$HASHTAG_SPECIAL])[#＃](?![\x{FE0F}\x{20E3}])[\p{L}\p{M}\p{Nd}$HASHTAG_SPECIAL]++(?![#＃]|://)""",
        )

    /** twitter-text's `latinAccentChars`, roughly: a mention may not run into these. */
    private const val LATIN_ACCENTS = """\x{00C0}-\x{024F}\x{0253}-\x{02BB}\x{0300}-\x{036F}\x{1E00}-\x{1EFF}"""

    private val MENTION =
        Regex("""(?<![A-Za-z0-9_!#$%&*@＠])[@＠][A-Za-z0-9_]{1,20}+(?![A-Za-z0-9_@＠$LATIN_ACCENTS]|://)""")
}
