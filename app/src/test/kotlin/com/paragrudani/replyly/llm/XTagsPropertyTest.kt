package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

/**
 * Seeded property test for [XTags.strip] (issue #46 §4). A reply is built from a leading run of
 * tags, a middle of plain words (with look-alikes: `C#`, `#1`, e-mails, `@` and `#` in URLs) and
 * inline tags, and a trailing run, so the expected result is known (the simulated truth).
 *
 * Invariants, for every seed:
 * 1. Both kinds stripped: the result is exactly the middle with the inline tags' sigils removed.
 * 2. Never adds text: the result is a subsequence of the input.
 * 3. Idempotent: stripping the result again changes nothing.
 * 4. A kept kind is untouched: every tag of that kind in the input is in the result, in order.
 * 5. No tag of a stripped kind is left in the result.
 * 6. Both kinds kept: the input comes back unchanged.
 */
class XTagsPropertyTest {
    private val plain =
        listOf(
            "the",
            "point",
            "about",
            "caching,",
            "is",
            "fair.",
            "C#",
            "F#",
            "#1",
            "asha@example.com",
            "https://example.com/@ravi_k",
            "https://example.com/#top",
            "#️⃣",
            "&#39;",
            "नमस्ते",
            "日本語",
            "🔥",
            "café",
            "—",
            "#",
            "@",
        )
    private val hashtags = listOf("#Kotlin", "#buildinpublic", "#dev_life", "#日本", "＃タグ", "#v2")
    private val mentions = listOf("@ravi_k", "@asha_dev", "@x", "＠ravi_k", "@A_1")
    private val spaces = listOf(" ", " ", "  ", "\n", " \n")

    private data class Case(
        val input: String,
        val expected: String,
    )

    private fun tag(random: Random) = if (random.nextBoolean()) hashtags.random(random) else mentions.random(random)

    private fun case(random: Random): Case {
        val lead = List(random.nextInt(0, 4)) { tag(random) }
        val trail = List(random.nextInt(0, 4)) { tag(random) }
        val middle =
            if (random.nextInt(10) == 0) {
                emptyList()
            } else {
                // Plain words at both ends, so the inline tags are never part of a run.
                buildList {
                    add(plain.random(random))
                    repeat(random.nextInt(0, 12)) {
                        add(
                            if (random.nextInt(4) ==
                                0
                            ) {
                                tag(random)
                            } else {
                                plain.random(random)
                            },
                        )
                    }
                    add(plain.random(random))
                }
            }
        val words = lead + middle + trail
        val input =
            buildString {
                if (random.nextInt(8) == 0) append(" ")
                words.forEachIndexed { i, word ->
                    if (i > 0) append(spaces.random(random))
                    append(word)
                }
            }
        // The middle as it sits in the input (its own spacing), sigils of inline tags removed.
        val expected =
            if (middle.isEmpty()) {
                ""
            } else {
                val trimmed = input.trim()
                var from = 0
                repeat(lead.size) { from = trimmed.indexOf(lead[it], from) + lead[it].length }
                val start = trimmed.indexOf(middle.first(), from)
                var end = trimmed.length
                repeat(trail.size) { end = trimmed.lastIndexOf(trail[trail.size - 1 - it], end - 1) }
                val region = trimmed.substring(start, end).trimEnd()
                middle.filter { it in hashtags || it in mentions }.fold(region) { acc, tag ->
                    acc.replaceFirst(Regex("(?<=^|\\s)" + Regex.escape(tag) + "(?=\\s|$)"), tag.drop(1))
                }
            }
        return Case(input, expected)
    }

    @Test
    fun `strip keeps every invariant`() {
        var changed = 0
        for (seed in 0 until 5_000) {
            val random = Random(seed)
            val (input, expected) = case(random)
            val msg = "seed $seed: $input"
            val both = XTags.strip(input, XTags.Keep(hashtags = false, mentions = false))
            assertWithMessage("1 simulated truth, $msg").that(both).isEqualTo(expected)
            if (both != input.trim()) changed++

            for (keep in listOf(XTags.Keep(false, false), XTags.Keep(true, false), XTags.Keep(false, true))) {
                val out = XTags.strip(input, keep)
                assertWithMessage("2 subsequence, $keep, $msg").that(isSubsequence(out, input)).isTrue()
                assertWithMessage("3 idempotent, $keep, $msg").that(XTags.strip(out, keep)).isEqualTo(out)
                if (keep.hashtags) {
                    assertWithMessage("4 hashtags kept, $keep, $msg")
                        .that(XTags.hashtags(out).map { out.substring(it) })
                        .isEqualTo(XTags.hashtags(input).map { input.substring(it) })
                } else {
                    assertWithMessage("5 no hashtag left, $keep, $msg").that(XTags.hashtags(out)).isEmpty()
                }
                if (keep.mentions) {
                    assertWithMessage("4 mentions kept, $keep, $msg")
                        .that(XTags.mentions(out).map { out.substring(it) })
                        .isEqualTo(XTags.mentions(input).map { input.substring(it) })
                } else {
                    assertWithMessage("5 no mention left, $keep, $msg").that(XTags.mentions(out)).isEmpty()
                }
            }
            assertWithMessage("6 unchanged, $msg").that(XTags.strip(input, XTags.Keep(true, true))).isEqualTo(input)
        }
        assertWithMessage("coverage").that(changed).isGreaterThan(3_000)
    }

    private fun isSubsequence(
        small: String,
        big: String,
    ): Boolean {
        var i = 0
        for (c in big) if (i < small.length && small[i] == c) i++
        return i == small.length
    }
}
