package com.paragrudani.replyly.panel

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.parser.x.XContext
import com.paragrudani.replyly.parser.x.XMode
import com.paragrudani.replyly.parser.x.XPost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The panel on X (#30 PR 2): no "Nothing to reply to", X chips, counters and fewer than 3 cards. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplyPanelXTest {
    private val assembler = FakeAssembler()
    private val feedback = FakeFeedbackLog()

    /** Replying to my own post: as a chat, my message last and recent would be "Nothing to reply to". */
    private val ownPost = XPost("Kiran", "kiran_test", "shipping offline mode today", "5m", isMine = true)
    private val xChat =
        ParsedChat(
            packageName = TargetApps.X,
            title = "Kiran",
            isGroup = false,
            messages = listOf(ChatMessage(null, "shipping offline mode today", "5m", isMe = true)),
            historyApplies = false,
            x = XContext(XMode.REPLY, target = ownPost, myHandle = "kiran_test"),
        )

    private fun TestScope.panel(
        generator: FakeGenerator,
        chat: ParsedChat = xChat,
    ) = ReplyPanelState(
        request = PanelRequest(chat),
        deps = ReplyPanelDependencies(assembler, generator, FakeInserter(), feedback, FakeNoteStore(), FakeHistory()),
        scope = this,
        onClose = {},
    )

    @Test
    fun `replying to my own post on X makes the one call, no Nothing to reply to`() =
        runTest {
            val generator = FakeGenerator()
            val panel = panel(generator)

            panel.start()
            advanceUntilIdle()

            assertThat(generator.calls).isEqualTo(1)
            assertThat(generator.requests.single().x).isEqualTo(xChat.x)
            assertThat(panel.state.value.suggestions).isInstanceOf(SuggestionsUi.Ready::class.java)
            assertThat(panel.state.value.isX).isTrue()
            assertThat(panel.state.value.showChatActions).isFalse()
            // The feedback entry records the X mode; the app is in the chat key.
            assertThat(feedback.recorded.single().xMode).isEqualTo(XMode.REPLY)
            assertThat(
                feedback.recorded
                    .single()
                    .chatKey.packageName,
            ).isEqualTo(TargetApps.X)
        }

    @Test
    fun `the cards carry the post length and the dropped count`() =
        runTest {
            val two =
                listOf(
                    Suggestion("insight", "Sync is the hard part.", trimmed = true),
                    Suggestion("question", "Which part?"),
                )
            val generator = FakeGenerator(success(two).copy(lengthLimit = 280, droppedOverLimit = 1))
            val panel = panel(generator)

            panel.start()
            advanceUntilIdle()

            val ready = panel.state.value.suggestions as SuggestionsUi.Ready
            assertThat(ready.suggestions).isEqualTo(two)
            assertThat(ready.lengthLimit).isEqualTo(280)
            assertThat(ready.droppedOverLimit).isEqualTo(1)
            // A card of the two is still insertable by its index.
            panel.insert(1)
            advanceUntilIdle()
            assertThat(panel.state.value.finished).isTrue()
        }

    @Test
    fun `a chat panel keeps the chat chips and no counter`() =
        runTest {
            val generator = FakeGenerator()
            val panel = panel(generator, chat = whatsAppChat)
            panel.start()
            advanceUntilIdle()
            assertThat(panel.state.value.isX).isFalse()
            assertThat((panel.state.value.suggestions as SuggestionsUi.Ready).lengthLimit).isNull()
        }

    @Test
    fun `X chips swap More formal for Punchier`() {
        assertThat(QuickInstruction.forPanel(isX = true))
            .containsExactly(
                QuickInstruction.Shorter,
                QuickInstruction.Punchier,
                QuickInstruction.InEnglish,
                QuickInstruction.AddEmoji,
            ).inOrder()
        assertThat(QuickInstruction.forPanel(isX = false))
            .containsExactly(
                QuickInstruction.Shorter,
                QuickInstruction.MoreFormal,
                QuickInstruction.InEnglish,
                QuickInstruction.AddEmoji,
            ).inOrder()
        assertThat(combineInstruction("", setOf(QuickInstruction.Punchier, QuickInstruction.Shorter)))
            .isEqualTo("Make the replies shorter. Make them punchier: one clear point each.")
    }

    @Test
    fun `the dropped notice reads naturally`() {
        assertThat(
            droppedNotice(1, 280),
        ).isEqualTo("1 reply was over 280 characters and was left out. Regenerate for more.")
        assertThat(
            droppedNotice(2, 500),
        ).isEqualTo("2 replies were over 500 characters and were left out. Regenerate for more.")
    }
}
