package com.paragrudani.replyly.parser.x

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.parser.ParseReport
import com.paragrudani.replyly.parser.Parsers
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.parser.generic.GenericChatParser
import com.paragrudani.replyly.screen.ScreenNode
import com.paragrudani.replyly.testing.Fixtures
import org.junit.Test

class XParserTest {
    private fun report(
        fixture: String,
        handle: String? = null,
    ): ParseReport = XParser { handle }.explain(Fixtures.snapshot("x/$fixture"))

    private fun context(
        fixture: String,
        handle: String? = null,
    ): XContext {
        val report = report(fixture, handle)
        val chat = checkNotNull(report.chat) { report.toString() }
        return checkNotNull(chat.x) { report.toString() }
    }

    @Test
    fun `X is registered and keeps out of history and passive capture`() {
        assertThat(Parsers.registry().parserFor(TargetApps.X)).isInstanceOf(XParser::class.java)
        assertThat(TargetApps.X_PACKAGES).containsExactly("com.twitter.android")
        assertThat(TargetApps.FULL_PARSER_PACKAGES).doesNotContain(TargetApps.X)
    }

    @Test
    fun `reply in a thread - the focal post is the target, parents oldest first, later replies dropped`() {
        val report = report("synthetic-reply_thread.json")
        val chat = report.chat!!
        val x = chat.x!!

        assertThat(report.method).isEqualTo(XParser.METHOD_VIEW_IDS)
        assertThat(x.mode).isEqualTo(XMode.REPLY)
        assertThat(x.targetPick).isEqualTo(XContext.TargetPick.FOCAL)
        assertThat(x.target.handle).isEqualTo("meera_writes")
        assertThat(x.target.authorName).isEqualTo("Meera Iyer")
        assertThat(x.target.text)
            .isEqualTo("We tried last-write-wins and regretted it. Per-field merge was worth the extra week.")
        assertThat(x.target.timeShown).isEqualTo("6:42 PM · 21 Nov 26")
        assertThat(x.parents.map { it.handle }).containsExactly("ravi_k", "asha_dev").inOrder()
        assertThat(x.parents[0].timeShown).isEqualTo("5h")
        assertThat(x.parents[1].replyingTo).containsExactly("ravi_k")
        assertThat(x.parents[1].text).isEqualTo("Offline first is underrated. How are you handling sync conflicts?")
        assertThat(x.replyingTo).containsExactly("meera_writes")
        assertThat(x.thread.none { it.handle == "dev_null_test" }).isTrue()
        assertThat(chat.historyApplies).isFalse()
        assertThat(chat.isGroup).isFalse()
        assertThat(chat.title).isEqualTo("Meera Iyer")
    }

    @Test
    fun `a quoted post inside a parent is that parent's quote, never a parent`() {
        val x = context("synthetic-reply_thread.json")

        val quoted = x.parents[0].quoted!!
        assertThat(quoted.handle).isEqualTo("asha_dev")
        assertThat(quoted.text).isEqualTo("What is the one feature you would ship first?")
        assertThat(quoted.timeShown).isEqualTo("Nov 19")
        assertThat(x.parents[0].text).isEqualTo("Shipping the offline mode this week. Small teams can still move fast.")
        assertThat(x.parents.map { it.text }).doesNotContain(quoted.text)
    }

    @Test
    fun `no composer draft, hint or chrome becomes post text`() {
        for (fixture in FIXTURES_WITH_POSTS) {
            val x = context(fixture)
            val texts = (x.thread + x.thread.mapNotNull { it.quoted }).map { it.text }
            for (text in texts) {
                for (bad in CHROME_AND_DRAFTS) {
                    assertWithMessage("$fixture: $text").that(text.lines()).doesNotContain(bad)
                }
            }
        }
    }

    @Test
    fun `the geometry route reads the same thread without view ids`() {
        val withIds = context("synthetic-reply_thread.json")
        val report = report("synthetic-reply_thread-no_ids.json")

        assertThat(report.method).isEqualTo(XParser.METHOD_GEOMETRY)
        assertThat(report.chat!!.x).isEqualTo(withIds)
    }

    @Test
    fun `image post - alt text read, a generic image counted`() {
        val x = context("synthetic-reply_composer-image.json")

        assertThat(x.parents).isEmpty()
        assertThat(x.target.handle).isEqualTo("asha_dev")
        assertThat(x.target.timeShown).isEqualTo("2h")
        assertThat(x.target.text).isEqualTo("Sketched the new data flow on the office whiteboard")
        assertThat(x.target.imageAltTexts)
            .containsExactly("A whiteboard with boxes for screen, parser and panel joined by arrows")
        assertThat(x.target.imagesWithoutAlt).isEqualTo(1)
        assertThat(x.replyingTo).containsExactly("asha_dev")
        assertThat(context("synthetic-reply_composer-image-no_ids.json")).isEqualTo(x)
    }

    @Test
    fun `link card and no replying-to line - the nearest post above is the target`() {
        val report = report("synthetic-reply_link_card-no_replying_line.json")
        val x = report.chat!!.x!!
        assertThat(x.targetPick).isEqualTo(XContext.TargetPick.NEAREST)

        assertThat(x.target.handle).isEqualTo("dev_null_test")
        assertThat(x.target.text).isEqualTo("Good read on accessibility trees")
        assertThat(x.target.linkCard).isEqualTo(XLinkCard("How screen readers see your app", "example.com"))
        assertThat(x.replyingTo).isEmpty()
        assertThat(report.notes).contains("replying-to line: not shown")
    }

    @Test
    fun `my own post - from the setting, any case and with @`() {
        val x = context("synthetic-reply_own_post.json", handle = "@Kiran_Test")

        assertThat(x.parents.single().isMine).isTrue()
        assertThat(x.target.isMine).isFalse()
        assertThat(x.target.handle).isEqualTo("ravi_k")
        assertThat(x.myHandleSource).isEqualTo(XContext.HandleSource.SETTING)
    }

    @Test
    fun `my own post - from the composer's account when the setting is empty`() {
        val x = context("synthetic-reply_own_post.json", handle = "")

        assertThat(x.parents.single().isMine).isTrue()
        assertThat(x.myHandle).isEqualTo("kiran_test")
        assertThat(x.myHandleSource).isEqualTo(XContext.HandleSource.COMPOSER_ACCOUNT)
    }

    @Test
    fun `another handle in the setting marks nothing mine`() {
        val x = context("synthetic-reply_own_post.json", handle = "someone_else")

        assertThat(x.thread.none { it.isMine }).isTrue()
    }

    @Test
    fun `quote composer - the quoted card is the target, no parents`() {
        val report = report("synthetic-quote_composer.json")
        val x = report.chat!!.x!!

        assertThat(x.mode).isEqualTo(XMode.QUOTE)
        assertThat(x.targetPick).isEqualTo(XContext.TargetPick.QUOTED_CARD)
        assertThat(x.target.handle).isEqualTo("meera_writes")
        assertThat(x.target.text).isEqualTo("Per-field merge was worth the extra week.")
        assertThat(x.target.timeShown).isEqualTo("1h")
        assertThat(x.parents).isEmpty()
        assertThat(x.replyingTo).isEmpty()
        assertThat(report.notes).contains("mode: quote")
        assertThat(context("synthetic-quote_composer-no_ids.json")).isEqualTo(x)
    }

    @Test
    fun `screens without a reply or quote composer are not read`() {
        for (
        fixture in
        listOf(
            "synthetic-timeline.json",
            "synthetic-post_detail.json",
            "synthetic-search.json",
            "synthetic-new_post.json",
        )
        ) {
            assertWithMessage(fixture).that(report(fixture).chat).isNull()
        }
    }

    @Test
    fun `a DM goes through the generic parser as a 1-1 chat without history`() {
        val report = report("synthetic-dm.json")
        val chat = report.chat!!

        assertThat(report.method).isEqualTo(XParser.METHOD_DM)
        assertThat(chat.x).isNull()
        assertThat(chat.historyApplies).isFalse()
        assertThat(chat.isGroup).isFalse()
        assertThat(chat.title).isEqualTo("Asha Dev")
        assertThat(
            chat.messages,
        ).isEqualTo(GenericChatParser().parse(Fixtures.snapshot("x/synthetic-dm.json"))!!.messages)
        assertThat(chat.messages.map { it.text })
            .containsExactly("are you coming to the meetup?", "yes, see you there", "can you bring the demo phone")
            .inOrder()
    }

    @Test
    fun `interim messages - parents then target, mine marked, notes for other authors and media`() {
        val chat = XParser { "kiran_test" }.parse(Fixtures.snapshot("x/synthetic-reply_own_post.json"))!!

        assertThat(chat.title).isEqualTo("Ravi K")
        assertThat(chat.messages.map { it.isMe }).containsExactly(true, false).inOrder()
        assertThat(chat.messages[0].sender).isNull()
        assertThat(chat.messages[1].sender).isEqualTo("Ravi K")
        assertThat(chat.messages[1].text).isEqualTo("Disagree, power users want control.")
        assertThat(chat.messages[1].timeShown).isEqualTo("2h")

        val thread = XParser().parse(Fixtures.snapshot("x/synthetic-reply_thread.json"))!!
        assertThat(thread.messages.map { it.text })
            .containsExactly(
                "[by Ravi K (@ravi_k)] Shipping the offline mode this week. Small teams can still move fast. " +
                    "[quotes Asha Dev (@asha_dev): What is the one feature you would ship first?]",
                "[by Asha Dev (@asha_dev)] Offline first is underrated. How are you handling sync conflicts?",
                "We tried last-write-wins and regretted it. Per-field merge was worth the extra week.",
            ).inOrder()

        val image = XParser().parse(Fixtures.snapshot("x/synthetic-reply_composer-image.json"))!!
        assertThat(image.messages.single().text).isEqualTo(
            "Sketched the new data flow on the office whiteboard " +
                "[image: A whiteboard with boxes for screen, parser and panel joined by arrows] [image]",
        )
        val card = XParser().parse(Fixtures.snapshot("x/synthetic-reply_link_card-no_replying_line.json"))!!
        assertThat(card.messages.single().text)
            .isEqualTo("Good read on accessibility trees [link: How screen readers see your app (example.com)]")
    }

    @Test
    fun `notes name the mode and count posts, without post text`() {
        val report = report("synthetic-reply_thread.json")

        assertThat(report.notes).containsAtLeast("mode: reply", "3 posts: 2 parents + target", "target: focal post")
        val all = report.notes.joinToString(" ")
        for (word in listOf("Ravi", "Meera", "merge", "offline", "@")) {
            assertThat(all).doesNotContain(word)
        }
    }

    @Test
    fun `an unknown field under a replying-to line is a reply, and the notes say no button showed`() {
        val original = Fixtures.snapshot("x/synthetic-reply_composer-image-no_ids.json")

        fun unknown(n: ScreenNode): ScreenNode =
            if (n.editable) {
                n.copy(viewId = null, hint = "Tap to write", text = "Tap to write")
            } else {
                n.copy(children = n.children.map(::unknown))
            }
        val renamed = original.copy(root = unknown(original.root))

        assertThat(XFields.allowsButton(renamed.packageName, null, "Tap to write")).isFalse()
        val report = XParser().explain(renamed)
        assertThat(report.chat?.x?.mode).isEqualTo(XMode.REPLY)
        assertThat(
            report.chat
                ?.x
                ?.target
                ?.handle,
        ).isEqualTo("asha_dev")
        assertThat(report.notes).contains(XParser.NOTE_UNRECOGNISED_FIELD)
        assertThat(XParser().explain(original).notes).doesNotContain(XParser.NOTE_UNRECOGNISED_FIELD)
    }

    private companion object {
        val FIXTURES_WITH_POSTS =
            listOf(
                "synthetic-reply_thread.json",
                "synthetic-reply_thread-no_ids.json",
                "synthetic-reply_composer-image.json",
                "synthetic-reply_composer-image-no_ids.json",
                "synthetic-reply_own_post.json",
                "synthetic-reply_link_card-no_replying_line.json",
                "synthetic-quote_composer.json",
                "synthetic-quote_composer-no_ids.json",
            )

        val CHROME_AND_DRAFTS =
            listOf(
                "Post your reply",
                "Add a comment",
                "Per-field merge is the way",
                "This matches what we saw",
                "Replying to @meera_writes",
                "Replying to @asha_dev",
                "Replying to @ravi_k",
                "Reply",
                "Post",
                "Cancel",
                "Follow",
                "12 Reposts",
                "48 Likes",
                "3",
                "12",
                "48",
                "From example.com",
                "6:42 PM · 21 Nov 26 · 1,204 Views",
            )
    }
}
