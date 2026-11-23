package com.paragrudani.replyly.ui.debug

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.parser.x.XContext
import com.paragrudani.replyly.parser.x.XField
import com.paragrudani.replyly.parser.x.XFields
import com.paragrudani.replyly.parser.x.XParser
import com.paragrudani.replyly.parser.x.XPost
import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.screen.ScreenNode
import com.paragrudani.replyly.screen.ScreenSnapshot
import com.paragrudani.replyly.testing.Fixtures
import org.junit.Test
import java.time.Instant

/** Issue #30: a shared X dump hides post text, names, handles, alt text and link titles, and still parses. */
class XScramblerTest {
    private val scrambler = Scrambler()

    /** One string as it scrambles in an X dump. */
    private fun x(text: String): String = scrambler.scrambleText(text, forX = true)

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
            val out = x(text)
            assertWithMessage(text).that(out).isNotEqualTo(text)
            assertWithMessage(text).that(out.length).isEqualTo(text.length)
        }
    }

    @Test
    fun `a handle scrambles the same in a header, a replying-to line and a mention`() {
        val handle = x("@asha_dev")
        assertThat(handle).startsWith("@")
        assertThat(handle).matches("@[a-z]{4}_[a-z]{3}")

        val header = x("Asha Dev @asha_dev · 2h")
        assertThat(header).isEqualTo("${x("Asha Dev")} $handle · 2h")

        val line = x("Replying to @ravi_k and @asha_dev")
        assertThat(line).isEqualTo("Replying to ${x("@ravi_k")} and $handle")

        val mention = x("Great point @asha_dev")
        assertThat(mention).endsWith(" $handle")
        assertThat(mention).doesNotContain("Great")
        assertThat(x("Account @asha_dev")).endsWith(" $handle")
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
            assertWithMessage(text).that(x(text)).isEqualTo(text)
        }
        val card = x("From example.com")
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

    @Test
    fun `an X field's hint stays readable in any wording, its handle scrambled`() {
        val hindi = "अपना जवाब पोस्ट करें"
        val out =
            scrambler.scramble(
                snapshot(
                    TargetApps.X,
                    field("Reply to @asha_dev", text = "Reply to @asha_dev"),
                    field(hindi, text = hindi),
                    field("Post your reply…", text = "Per-field merge is the way"),
                    label("Offline first is underrated.", hint = "Offline first is underrated."),
                ),
            )
        val (reply, other, drafted, post) = out.root.children

        val handle = x("@asha_dev")
        assertThat(reply.hint).isEqualTo("Reply to $handle")
        assertThat(reply.text).isEqualTo(reply.hint)
        assertThat(XFields.classify(null, reply.hint)).isEqualTo(XField.REPLY)
        assertThat(other.hint).isEqualTo(hindi)
        assertThat(other.text).isEqualTo(hindi)
        assertThat(drafted.hint).isEqualTo("Post your reply…")
        assertThat(drafted.text).isNotEqualTo("Per-field merge is the way")
        // A hint on a node that is not a text field is scrambled like any text.
        assertThat(post.hint).isNotEqualTo("Offline first is underrated.")
        assertThat(post.text).isNotEqualTo("Offline first is underrated.")
    }

    @Test
    fun `X rules stay out of WhatsApp and Telegram dumps`() {
        val fixed = Scrambler(ByteArray(32) { it.toByte() })
        val texts =
            listOf(
                "like",
                "more",
                "Follow",
                "Photo",
                "2h",
                "1.2K",
                "Post your reply",
                "Asha Dev @asha_dev · 2h",
                "Replying to @ravi_k",
                "Thanks @ravi_k",
                "From example.com",
            )
        for (pkg in listOf(TargetApps.WHATSAPP, TargetApps.TELEGRAM)) {
            val out = fixed.scramble(snapshot(pkg, *(texts.map { label(it) } + field("Type a message")).toTypedArray()))
            out.root.children.dropLast(1).zip(texts).forEach { (node, text) ->
                assertWithMessage("$pkg $text").that(node.text).isNotEqualTo(text)
                assertWithMessage("$pkg $text").that(node.text).isEqualTo(fixed.scrambleText(text))
            }
            assertWithMessage(pkg)
                .that(
                    out.root.children
                        .last()
                        .hint,
                ).isNotEqualTo("Type a message")
            // "Thanks @ravi_k" is one string there, not a handle scrambled on its own.
            val thanks = out.root.children[texts.indexOf("Thanks @ravi_k")].text!!
            assertWithMessage(pkg).that(thanks).isNotEqualTo(fixed.scrambleText("Thanks @ravi_k", forX = true))
        }
    }

    private fun field(
        hint: String,
        text: String? = null,
    ) = ScreenNode(
        className = "android.widget.EditText",
        text = text,
        hint = hint,
        bounds = Bounds(180, 1500, 1040, 1630),
        editable = true,
    )

    private fun label(
        text: String,
        hint: String? = null,
    ) = ScreenNode(
        className = "android.widget.TextView",
        text = text,
        hint = hint,
        bounds = Bounds(180, 300, 1040, 360),
    )

    private fun snapshot(
        pkg: String,
        vararg kids: ScreenNode,
    ) = ScreenSnapshot(
        packageName = pkg,
        capturedAt = Instant.parse("2026-11-21T18:15:30Z"),
        screenWidth = 1080,
        screenHeight = 2340,
        root =
            ScreenNode(
                className = "android.widget.FrameLayout",
                bounds = Bounds(0, 0, 1080, 2340),
                children = kids.toList(),
            ),
    )

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
