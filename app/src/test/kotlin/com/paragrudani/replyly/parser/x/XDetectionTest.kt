package com.paragrudani.replyly.parser.x

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.overlay.ButtonDecision
import com.paragrudani.replyly.overlay.ButtonVisibility
import com.paragrudani.replyly.overlay.FocusedInput
import com.paragrudani.replyly.overlay.HideReason
import com.paragrudani.replyly.overlay.ScreenFacts
import com.paragrudani.replyly.overlay.WindowFacts
import com.paragrudani.replyly.overlay.WindowKind
import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.testing.Fixtures
import org.junit.Test

/**
 * Issue #30 §1: on X the button shows on the reply composer, the quote composer and a DM, and
 * nowhere else. Each screen's focused field comes from its fixture, as the overlay would read it.
 */
class XDetectionTest {
    private val visibility = ButtonVisibility("com.paragrudani.replyly", { true }, XFields::allowsButton)
    private val app =
        WindowFacts(id = 3, kind = WindowKind.APPLICATION, bounds = Bounds(0, 0, 1080, 2340), active = true)
    private val keyboard = WindowFacts(id = 4, kind = WindowKind.INPUT_METHOD, bounds = Bounds(0, 1500, 1080, 2340))

    /** The focused editable of a fixture, as `ScreenFactsReader` maps it (null when nothing has focus). */
    private fun focused(fixture: String): FocusedInput? {
        val snapshot = Fixtures.snapshot("x/$fixture")
        val node = snapshot.allNodes().firstOrNull { it.focused && it.editable } ?: return null
        return FocusedInput(
            3,
            snapshot.packageName,
            editable = true,
            password = false,
            node.viewId,
            Bounds(180, 1300, 1040, 1430),
            node.hint,
        )
    }

    private fun decide(fixture: String) = visibility.decide(ScreenFacts(listOf(app, keyboard), focused(fixture)))

    @Test
    fun `shows on the reply composers, the quote composer and a DM`() {
        for (
        fixture in
        listOf(
            "synthetic-reply_thread.json",
            "synthetic-reply_composer-image.json",
            "synthetic-reply_own_post.json",
            "synthetic-reply_link_card-no_replying_line.json",
            "synthetic-quote_composer.json",
            "synthetic-dm.json",
        )
        ) {
            assertWithMessage(fixture).that(decide(fixture)).isInstanceOf(ButtonDecision.Show::class.java)
        }
    }

    @Test
    fun `hides on search and on a new post without a target`() {
        for (fixture in listOf("synthetic-search.json", "synthetic-new_post.json")) {
            assertWithMessage(
                fixture,
            ).that(decide(fixture)).isEqualTo(ButtonDecision.Hide(HideReason.NOT_A_REPLY_FIELD))
        }
    }

    @Test
    fun `hides on the timeline and a post's detail view without a focused composer`() {
        for (fixture in listOf("synthetic-timeline.json", "synthetic-post_detail.json")) {
            assertWithMessage(fixture).that(focused(fixture)).isNull()
            assertWithMessage(fixture).that(decide(fixture)).isEqualTo(ButtonDecision.Hide(HideReason.NO_INPUT_FOCUS))
        }
    }

    @Test
    fun `classifies fields by view id, then hint`() {
        val composer = "com.twitter.android:id/tweet_text"
        assertThat(XFields.classify(composer, "Post your reply")).isEqualTo(XField.REPLY)
        assertThat(XFields.classify(composer, "Tweet your reply")).isEqualTo(XField.REPLY)
        assertThat(XFields.classify(null, "POST YOUR REPLY")).isEqualTo(XField.REPLY)
        assertThat(XFields.classify(composer, "Add a comment")).isEqualTo(XField.QUOTE)
        assertThat(XFields.classify(composer, "Add a comment…")).isEqualTo(XField.QUOTE)
        assertThat(XFields.classify(composer, "What’s happening?")).isEqualTo(XField.NEW_POST)
        assertThat(XFields.classify(composer, "What's happening?")).isEqualTo(XField.NEW_POST)
        assertThat(XFields.classify(composer, "What is happening?!")).isEqualTo(XField.NEW_POST)
        assertThat(XFields.classify(composer, "Add another post")).isEqualTo(XField.NEW_POST)
        assertThat(XFields.classify("com.twitter.android:id/query", "Post your reply")).isEqualTo(XField.SEARCH)
        assertThat(XFields.classify(null, "Search X")).isEqualTo(XField.SEARCH)
        assertThat(XFields.classify(null, "Ask Grok anything")).isEqualTo(XField.SEARCH)
        assertThat(XFields.classify("com.twitter.android:id/dm_composer_edit_text", null)).isEqualTo(XField.DM)
        assertThat(XFields.classify(null, "Start a message")).isEqualTo(XField.DM)
        assertThat(XFields.classify(composer, null)).isEqualTo(XField.OTHER)
        assertThat(XFields.classify(null, "Name")).isEqualTo(XField.OTHER)
    }

    @Test
    fun `the gate only narrows X`() {
        assertThat(XFields.allowsButton("com.whatsapp", null, "Search")).isTrue()
        assertThat(XFields.allowsButton("com.twitter.android", null, "Search")).isFalse()
        assertThat(XFields.allowsButton("com.twitter.android", null, null)).isFalse()
        assertThat(XFields.allowsButton("com.twitter.android", null, "Post your reply")).isTrue()
    }
}
