package com.paragrudani.replyly.ui.debug

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.parser.x.XContext
import com.paragrudani.replyly.parser.x.XParser
import com.paragrudani.replyly.parser.x.XPost
import com.paragrudani.replyly.testing.Fixtures
import org.junit.Test

/** Issue #30: a shared X dump hides post text, names, handles, alt text and link titles, and still parses. */
class XScramblerTest {
    private val scrambler = Scrambler()

    @Test
    fun `post text, names, handles, alt text and link titles are scrambled`() {
        for (
        text in
        listOf(
            "Offline first is underrated.",
            "Asha Dev",
            "@asha_dev",
            "A whiteboard with boxes and arrows",
            "How screen readers see your app",
        )
        ) {
            val out = scrambler.scrambleText(text)
            assertWithMessage(text).that(out).isNotEqualTo(text)
            assertWithMessage(text).that(out.length).isEqualTo(text.length)
        }
    }

    @Test
    fun `a handle scrambles the same in a header, a replying-to line and a mention`() {
        val handle = scrambler.scrambleText("@asha_dev")
        assertThat(handle).startsWith("@")
        assertThat(handle).matches("@[a-z]{4}_[a-z]{3}")

        val header = scrambler.scrambleText("Asha Dev @asha_dev · 2h")
        assertThat(header).isEqualTo("${scrambler.scrambleText("Asha Dev")} $handle · 2h")

        val line = scrambler.scrambleText("Replying to @ravi_k and @asha_dev")
        assertThat(line).isEqualTo("Replying to ${scrambler.scrambleText("@ravi_k")} and $handle")

        val mention = scrambler.scrambleText("Great point @asha_dev")
        assertThat(mention).endsWith(" $handle")
        assertThat(mention).doesNotContain("Great")
        assertThat(scrambler.scrambleText("Account @asha_dev")).endsWith(" $handle")
    }

    @Test
    fun `X chrome the parser reads stays whole`() {
        for (
        text in
        listOf(
            "2h",
            "now",
            "Nov 19",
            "· 2h",
            "6:42 PM · 21 Nov 26 · 1,204 Views",
            "1.2K",
            "48 Likes",
            "12 Reposts",
            "Post your reply",
            "Add a comment",
            "What’s happening?",
            "Start a message",
        )
        ) {
            assertWithMessage(text).that(scrambler.scrambleText(text)).isEqualTo(text)
        }
        val card = scrambler.scrambleText("From example.com")
        assertThat(card).startsWith("From ")
        assertThat(card).isNotEqualTo("From example.com")
        assertThat(card).matches("From [a-z]{7}\\.[a-z]{3}")
    }

    @Test
    fun `a scrambled X dump parses to the same structure`() {
        for (
        fixture in
        listOf(
            "synthetic-reply_thread.json",
            "synthetic-reply_thread-no_ids.json",
            "synthetic-reply_composer-image.json",
            "synthetic-reply_own_post.json",
            "synthetic-reply_link_card-no_replying_line.json",
            "synthetic-quote_composer.json",
            "synthetic-quote_composer-no_ids.json",
        )
        ) {
            val original = Fixtures.snapshot("x/$fixture")
            val plain = XParser().parse(original)!!.x!!
            val scrambled = XParser().parse(scrambler.scramble(original))!!.x!!

            assertWithMessage(fixture).that(shape(scrambled)).isEqualTo(shape(plain))
            val plainTexts = plain.thread.map { it.text }.filter { it.length > 3 }
            assertWithMessage(fixture).that(scrambled.thread.map { it.text }).containsNoneIn(plainTexts)
            assertWithMessage(fixture)
                .that(scrambled.thread.flatMap { it.imageAltTexts })
                .containsNoneIn(plain.thread.flatMap { it.imageAltTexts })
        }
    }

    /** Everything but the private text: mode, sizes, times, which posts share an author, mine. */
    private fun shape(x: XContext): List<Any?> {
        val handles = x.thread.map { it.handle?.lowercase() } + x.replyingTo.map { it.lowercase() }

        fun post(p: XPost) =
            listOf(
                p.timeShown,
                p.text.length,
                p.imageAltTexts.size,
                p.imagesWithoutAlt,
                p.linkCard?.title?.length,
                p.linkCard?.domain?.length,
                p.isMine,
                p.quoted?.text?.length,
                p.replyingTo.size,
            )
        return listOf(x.mode, x.parents.size, x.myHandleSource, handles.map { handles.indexOf(it) }) +
            x.thread.map(::post)
    }
}
