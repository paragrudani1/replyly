package com.paragrudani.replyly.llm

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * Counts a post the way X's composer does, and trims a reply to a limit (issue #30 §4). Pure.
 *
 * **Weights** follow twitter-text's v3 configuration (`config/v3.json` in
 * https://github.com/twitter/twitter-text, the library X's clients count with): `scale` 100,
 * `defaultWeight` 200, `transformedURLLength` 23, `emojiParsingEnabled` true, and these ranges at
 * weight 100:
 * - U+0000-U+10FF (Latin, Greek, Cyrillic, Arabic, Hebrew, Devanagari, Gujarati and the other
 *   Indic scripts, Thai, Georgian…),
 * - U+2000-U+200D (spaces, ZWJ),
 * - U+2010-U+201F (dashes, curly quotes),
 * - U+2032-U+2037 (primes).
 *
 * Dividing by the scale: a code point in those ranges counts **1**, any other code point (CJK,
 * Hangul, the ellipsis "…", most symbols) counts **2**, and the limit is the displayed one (280, or
 * 500 for "X post length" medium). So 140 CJK characters fill a 280 post, the same as twitter-text's
 * weighted limit of 2 × 280 in units of 50.
 *
 * **Emoji**: a grapheme cluster that contains an emoji (a pictographic code point, a regional
 * indicator, or a keycap or emoji variation selector) counts 2 as a whole, so a skin tone, a ZWJ
 * family and a flag are one emoji each, as twitter-text's emoji parsing does.
 *
 * **NFC**: the text is normalised to NFC before counting, as twitter-text's `parseTweet` does
 * (`Normalizer.normalize(text, Normalizer.Form.NFC)` in `TwitterTextParser.java`), so a decomposed
 * `e` + U+0301 counts 1 like `é`. [trim] still cuts the original text (see there).
 *
 * **URLs** count 23 each, as twitter-text's `Extractor.extractURLsWithIndices` (`Regex.VALID_URL`)
 * finds them:
 * - `http://` or `https://`, then a domain that ends in a top-level domain of two or more ASCII
 *   letters (any, so an unknown one over-counts, the safe direction) or two or more Cyrillic letters
 *   (`.рф`), or one of the common non-Latin TLDs in [IDN_TLDS] (`.中国`), then an optional port and
 *   path. Another non-Latin TLD counts as its characters (an under-count; rare, links are off in
 *   the prompt). A scheme
 *   without such a domain is plain text: `http://x`, `http://localhost:8080`, an IP address
 *   (twitter-text's `VALID_DOMAIN` needs a TLD after a dot).
 * - a bare domain whose last label is a common top-level domain ([TLDS]), with an optional path.
 *
 * A TLD ends before anything but `[a-z0-9@+-]` (twitter-text's TLD lookahead), so
 * `example.com然后` is a link and then CJK. The path keeps only twitter-text's path characters
 * (`VALID_GENERAL_URL_PATH_CHARS`: ASCII, Latin accents, Cyrillic; [PATH_CHAR]): a link followed
 * directly by CJK or full-width punctuation (`…/x，然后`) ends there, and the rest counts by weight.
 * Trailing `.,:;!?)]}'"` is not part of the URL. A domain right after `@`, a letter, a digit, `.`,
 * `-` or `/` is not a URL (an e-mail address, a file path). This is a subset of twitter-text's
 * extractor (it knows every TLD and balances parentheses); the difference only matters for unusual
 * bare TLDs, which then count as their characters.
 */
object XLength {
    const val URL_WEIGHT = 23

    /** "X post length" standard: X's limit for everyone. */
    const val STANDARD_LIMIT = 280

    /** "X post length" medium, for X Premium (longer posts; ~500 keeps a reply readable). */
    const val MEDIUM_LIMIT = 500

    /** The weighted length X shows in its counter (of the NFC form of [text]). */
    fun weighted(text: String): Int = weightedNfc(Normalizer.normalize(text, Normalizer.Form.NFC))

    /** Where [text] has URLs (twitter-text rules, see the class KDoc), without their trailing punctuation. */
    internal fun urlRanges(text: String): List<IntRange> =
        URL.findAll(text).map { it.range.first until it.range.first + it.value.trimEnd(*URL_TRAILING).length }.toList()

    private fun weightedNfc(text: String): Int {
        var total = 0
        var index = 0
        for (match in URL.findAll(text)) {
            val url = match.value.trimEnd(*URL_TRAILING)
            total += plain(text, index, match.range.first)
            total += URL_WEIGHT
            index = match.range.first + url.length
        }
        return total + plain(text, index, text.length)
    }

    fun fits(
        text: String,
        limit: Int,
    ): Boolean = weighted(text) <= limit

    /** A reply after [trim]: [text] is what fits, [trimmed] says whether anything was cut. */
    data class Trim(
        val text: String,
        val trimmed: Boolean,
    )

    /**
     * Shortens [text] to at most [limit] ([weighted]) without another LLM call.
     *
     * Invariants (checked by `XLengthPropertyTest`):
     * 1. The result's weighted length is at most [limit].
     * 2. The result is a prefix of the input with trailing whitespace removed. Counting is on the
     *    NFC form, cutting is on the original: every cut point is before whitespace or after `。！？`,
     *    where NFC never joins characters, so the NFC of a cut is a prefix of the input's NFC.
     * 3. It never ends mid-word or mid-grapheme: in the input, the result is followed by the end,
     *    by whitespace (possibly after dangling `,;:` or dashes it dropped, [DANGLING]), or it ends
     *    with sentence punctuation ([SENTENCE_END]).
     * 4. An input within the limit is returned unchanged, with `trimmed = false`.
     * 5. A sentence cut is used only when it keeps at least half of [limit] (or is also the longest
     *    word cut); otherwise the longest word cut wins, so "Yes. <one long sentence>" is not cut to
     *    "Yes." and then dropped.
     *
     * Order: the longest prefix that ends a sentence (`.`, `!`, `?` before whitespace or the end;
     * `。！？` anywhere), fits and keeps half the limit; else the longest prefix that ends before
     * whitespace and fits, without a dangling `,` `;` `:` or dash at its end; else empty (the caller
     * drops the card).
     */
    fun trim(
        text: String,
        limit: Int,
    ): Trim {
        val input = text.trim()
        if (fits(input, limit)) return Trim(input, trimmed = false)
        var bestSentence: String? = null
        var bestWord: String? = null
        // Longest first: the first fitting cut of each kind is the best one.
        for (end in cutPoints(input).asReversed()) {
            if (bestSentence != null) break
            val candidate = input.substring(0, end).trimEnd()
            if (candidate.isEmpty() || !fits(candidate, limit)) continue
            if (bestWord == null) bestWord = candidate
            if (endsSentence(input, end)) bestSentence = candidate
        }
        val sentence = bestSentence?.takeIf { it == bestWord || weighted(it) * 2 >= limit }
        val word = bestWord?.let { cut -> cut.trimEnd(*DANGLING).trimEnd().ifEmpty { cut } }
        return Trim(sentence ?: word ?: "", trimmed = true)
    }

    /** Punctuation a word cut must not end on: it promises more text that was cut. */
    val DANGLING = charArrayOf(',', ';', ':', '-', '–', '—', '，', '；', '：', '、')

    /** Sentence-ending punctuation; the full-width ones end a sentence without a space after them. */
    const val SENTENCE_END = ".!?。！？"
    private const val FULL_WIDTH_END = "。！？"

    /** Every index where a cut may happen: before whitespace, and right after full-width sentence ends. */
    private fun cutPoints(text: String): List<Int> =
        buildList {
            for (i in 1 until text.length) {
                if (text[i].isWhitespace() && !text[i - 1].isWhitespace()) add(i)
                if (text[i - 1] in FULL_WIDTH_END && !text[i].isWhitespace()) add(i)
            }
        }

    /** The prefix up to [end] ends a sentence: `.`, `!`, `?` (before whitespace) or a full-width end, with closing quotes or brackets after it. */
    private fun endsSentence(
        text: String,
        end: Int,
    ): Boolean {
        var i = end - 1
        while (i >= 0 && text[i] in CLOSERS) i--
        if (i < 0) return false
        val c = text[i]
        return c in FULL_WIDTH_END || (c in ".!?" && (end == text.length || text[end].isWhitespace()))
    }

    private const val CLOSERS = "\"')]}”’»」』）】"

    /** Weight of [text] from [start] to [end], with no URL in it. */
    private fun plain(
        text: String,
        start: Int,
        end: Int,
    ): Int {
        if (start >= end) return 0
        val segment = text.substring(start, end)
        val clusters = BreakIterator.getCharacterInstance(Locale.ROOT)
        clusters.setText(segment)
        var total = 0
        var from = clusters.first()
        var to = clusters.next()
        while (to != BreakIterator.DONE) {
            total += cluster(segment, from, to)
            from = to
            to = clusters.next()
        }
        return total
    }

    private fun cluster(
        text: String,
        start: Int,
        end: Int,
    ): Int {
        var emoji = false
        var sum = 0
        var i = start
        while (i < end) {
            val cp = text.codePointAt(i)
            if (isEmojiCodePoint(cp)) emoji = true
            sum += if (isWeightOne(cp)) 1 else 2
            i += Character.charCount(cp)
        }
        return if (emoji) 2 else sum
    }

    private fun isWeightOne(cp: Int): Boolean =
        cp in 0x0000..0x10FF || cp in 0x2000..0x200D || cp in 0x2010..0x201F || cp in 0x2032..0x2037

    /**
     * Pictographs (U+1F000-U+1FAFF: emoticons, symbols, flags' regional indicators, U+2600-U+27BF
     * misc symbols and dingbats, U+2300-U+23FF, U+2B00-U+2BFF), the keycap U+20E3 and the emoji
     * variation selector U+FE0F. A lone ©, ® or ™ without U+FE0F counts by its range.
     */
    private fun isEmojiCodePoint(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x2300..0x23FF ||
            cp in 0x2B00..0x2BFF ||
            cp == 0x20E3 ||
            cp == 0xFE0F

    private val URL_TRAILING = ".,:;!?)]}'\"".toCharArray()

    /** Common top-level domains for scheme-less URLs (see the class KDoc). */
    val TLDS: Set<String> =
        setOf(
            "com",
            "org",
            "net",
            "edu",
            "gov",
            "mil",
            "int",
            "io",
            "co",
            "ai",
            "app",
            "dev",
            "me",
            "tv",
            "xyz",
            "info",
            "biz",
            "blog",
            "news",
            "site",
            "online",
            "tech",
            "store",
            "shop",
            "page",
            "link",
            "ly",
            "gg",
            "so",
            "to",
            "fm",
            "in",
            "uk",
            "us",
            "ca",
            "au",
            "de",
            "fr",
            "es",
            "it",
            "nl",
            "jp",
            "cn",
            "kr",
            "br",
            "ru",
            "ch",
            "se",
            "no",
            "eu",
            "sg",
            "ae",
            "nz",
            "za",
            "ie",
            "pk",
            "bd",
            "lk",
            "np",
        )

    /** twitter-text's path characters, roughly: printable ASCII, Latin accents, combining marks, Cyrillic. */
    private const val PATH_CHAR = """[\x21-\x7E\u00C0-\u024F\u0300-\u036F\u0400-\u04FF\u1E00-\u1EFF]"""

    /** Common non-Latin, non-Cyrillic country TLDs (IANA IDN ccTLDs) after a scheme. */
    internal val IDN_TLDS: List<String> = listOf("中国", "中國", "香港", "台灣", "台湾", "新加坡", "भारत", "한국", "ไทย")

    /** twitter-text's TLD lookahead: a TLD ends before anything but `[a-z0-9@+-]`. */
    private const val TLD_END = """(?![a-z0-9@+-])"""

    private const val LABEL = """[\p{L}\p{N}](?:[\p{L}\p{N}_-]*[\p{L}\p{N}])?"""

    private val URL: Regex =
        Regex(
            """(?<![\w@./-])(?:https?://(?:$LABEL\.)+(?:[a-z]{2,}|\p{IsCyrillic}{2,}|${IDN_TLDS.joinToString(
                "|",
            )})$TLD_END(?::\d+)?""" +
                """|(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?:${TLDS.joinToString("|")})$TLD_END)""" +
                """(?:[/?#]$PATH_CHAR*)?""",
            RegexOption.IGNORE_CASE,
        )
}
