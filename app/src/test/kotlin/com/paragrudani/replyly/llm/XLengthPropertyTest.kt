package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.text.BreakIterator
import java.util.Locale
import kotlin.random.Random

/**
 * Seeded property test for [XLength.weighted] and [XLength.trim] on random replies mixing ASCII,
 * Devanagari, CJK, emoji (skin tones, ZWJ sequences, flags, keycaps), URLs with and without a
 * scheme, sentence punctuation and odd spacing.
 *
 * Invariants, for every seed and a random limit:
 * 1. The result's weighted length is at most the limit.
 * 2. The result is a prefix of the trimmed input, with no trailing whitespace.
 * 3. It never ends mid-word: in the input it is followed by the end, by whitespace (possibly after
 *    dangling punctuation the cut dropped, [XLength.DANGLING]), or it ends with full-width sentence
 *    punctuation.
 * 4. It never ends mid-grapheme: its length is a grapheme boundary of the input.
 * 5. An input within the limit is returned unchanged and not marked trimmed; one over the limit is
 *    marked trimmed.
 * 6. Not empty when the first word alone fits (the simulated truth: a word cut always exists then).
 * 7. Counting is additive over whitespace: the weight of words joined by spaces is the sum of
 *    their weights plus one per space (a URL never spans a space).
 * 8. A cut keeps at least half the limit, or it is the longest word cut (simulated here), with
 *    dangling punctuation dropped: a short first sentence never beats a long word cut.
 */
class XLengthPropertyTest {
    private val words =
        listOf(
            "the",
            "point",
            "about",
            "caching",
            "is",
            "fair",
            "but",
            "cold",
            "starts",
            "matter",
            "more",
            "नमस्ते",
            "बहुत",
            "अच्छा",
            "日本語",
            "中国",
            "한국어",
            "café",
            "naïve",
            "🔥",
            "👍🏽",
            "❤️",
            "1️⃣",
            "👩‍💻",
            "👨‍👩‍👧‍👦",
            "🇮🇳",
            "🇯🇵",
            "https://example.com/a/b?c=d",
            "http://x.co",
            "example.com",
            "docs.example.org/path",
            "2.5",
            "v1.2.3",
            "(see",
            "this)",
            "\"quoted\"",
            "e.g.",
            "U.S.",
            "…",
            "—",
            "well,",
            "so:",
            "then;",
            "「よし。」",
        )
    private val enders = listOf(".", "!", "?", "。", "！", "？", "...", "?!", ".\"", "!)")
    private val spaces = listOf(" ", " ", " ", "  ", "\n", " \n ")

    /** The longest prefix ending before whitespace that fits, without a dangling `,;:` or dash. */
    private fun longestWordCut(
        full: String,
        limit: Int,
    ): String {
        val ends =
            full.indices.filter {
                it > 0 &&
                    (
                        (full[it].isWhitespace() && !full[it - 1].isWhitespace()) ||
                            (full[it - 1] in "。！？" && !full[it].isWhitespace())
                    )
            }
        val cut = ends.asReversed().map { full.substring(0, it) }.firstOrNull { XLength.fits(it, limit) } ?: return ""
        return cut.trimEnd(*XLength.DANGLING).trimEnd().ifEmpty { cut }
    }

    private fun reply(random: Random): String =
        buildString {
            // A short first sentence before one long one (review r1 S4).
            if (random.nextInt(8) == 0) append(listOf("Yes.", "No!", "Hm?", "Fair.").random(random)).append(' ')
            if (random.nextInt(10) == 0) append(" ")
            repeat(random.nextInt(1, 70)) { i ->
                if (i > 0) append(spaces.random(random))
                append(words.random(random))
                if (random.nextInt(6) == 0) append(enders.random(random))
            }
            if (random.nextInt(10) == 0) append("  ")
        }

    @Test
    fun `trim keeps every invariant`() {
        var trimmedCount = 0
        var sentenceCuts = 0
        for (seed in 0 until 6_000) {
            val random = Random(seed)
            val input = reply(random)
            val limit = listOf(280, 500, random.nextInt(5, 300)).random(random)
            val result = XLength.trim(input, limit)
            val text = result.text
            val full = input.trim()
            val msg = "seed $seed, limit $limit"

            assertWithMessage("1 within limit, $msg").that(XLength.weighted(text)).isAtMost(limit)
            assertWithMessage("2 prefix, $msg").that(full.startsWith(text)).isTrue()
            assertWithMessage("2 no trailing space, $msg").that(text).isEqualTo(text.trimEnd())
            val rest = full.substring(text.length).trimStart(*XLength.DANGLING)
            val wordEnd =
                rest.isEmpty() || rest[0].isWhitespace() || text.isEmpty() || text.last() in "。！？"
            assertWithMessage("3 word boundary, $msg").that(wordEnd).isTrue()
            assertWithMessage("4 grapheme boundary, $msg").that(isGraphemeBoundary(full, text.length)).isTrue()
            if (XLength.fits(full, limit)) {
                assertWithMessage("5 unchanged, $msg").that(result).isEqualTo(XLength.Trim(full, trimmed = false))
            } else {
                assertWithMessage("5 marked, $msg").that(result.trimmed).isTrue()
                trimmedCount++
                if (text.isNotEmpty() && text.trimEnd(*"\"')".toCharArray()).last() in XLength.SENTENCE_END) {
                    sentenceCuts++
                }
            }
            if (result.trimmed && text.isNotEmpty() && XLength.weighted(text) * 2 < limit) {
                assertWithMessage("8 half the limit or the longest word cut, $msg")
                    .that(text)
                    .isEqualTo(longestWordCut(full, limit))
            }
            val firstWord = full.split(Regex("\\s+")).first()
            if (XLength.fits(firstWord, limit)) {
                assertWithMessage("6 not empty, $msg").that(text).isNotEmpty()
            }
        }
        // Coverage: the generator reaches both cut kinds often.
        assertWithMessage("trimmed").that(trimmedCount).isGreaterThan(1_000)
        assertWithMessage("sentence cuts").that(sentenceCuts).isGreaterThan(300)
    }

    @Test
    fun `weights add up across spaces`() {
        for (seed in 0 until 2_000) {
            val random = Random(seed)
            val parts = List(random.nextInt(1, 12)) { words.random(random) }
            val joined = parts.joinToString(" ")
            val sum = parts.sumOf { XLength.weighted(it) } + parts.size - 1
            assertWithMessage("seed $seed: $joined").that(XLength.weighted(joined)).isEqualTo(sum)
        }
    }

    private fun isGraphemeBoundary(
        text: String,
        index: Int,
    ): Boolean {
        if (index == 0 || index == text.length) return true
        val it = BreakIterator.getCharacterInstance(Locale.ROOT)
        it.setText(text)
        return it.isBoundary(index)
    }
}
