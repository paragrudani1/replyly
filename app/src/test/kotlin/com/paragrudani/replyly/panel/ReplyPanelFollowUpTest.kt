package com.paragrudani.replyly.panel

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.llm.PromptMessage
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParsedChat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration

/** Issue #26: "Nothing to reply to" makes no call; only the "anyway" tap makes one. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplyPanelFollowUpTest {
    private val assembler = FakeAssembler()
    private val feedback = FakeFeedbackLog()

    private fun TestScope.panel(
        generator: FakeGenerator,
        chat: ParsedChat = myMessageLastChat,
    ) = ReplyPanelState(
        request = PanelRequest(chat),
        deps = ReplyPanelDependencies(assembler, generator, FakeInserter(), feedback, FakeNoteStore(), FakeHistory()),
        scope = this,
        onClose = {},
    )

    @Test
    fun `opening with my message last and recent shows Nothing to reply to and makes no call`() =
        runTest {
            val generator = FakeGenerator()
            val panel = panel(generator)

            panel.start()
            advanceUntilIdle()

            assertThat(panel.state.value.suggestions).isEqualTo(SuggestionsUi.NothingToReply)
            assertThat(generator.calls).isEqualTo(0)
            assertThat(assembler.calls).hasSize(1)
            assertThat(feedback.recorded).isEmpty()
        }

    @Test
    fun `the anyway tap makes exactly one call for follow-ups`() =
        runTest {
            val generator = FakeGenerator()
            val panel = panel(generator)
            panel.start()
            advanceUntilIdle()

            panel.suggestFollowUpsAnyway()
            panel.suggestFollowUpsAnyway() // a double tap while loading
            runCurrent()
            assertThat(panel.state.value.suggestions).isEqualTo(SuggestionsUi.Loading)
            advanceUntilIdle()

            assertThat(generator.calls).isEqualTo(1)
            assertThat(generator.requests.single().followUpAnyway).isTrue()
            assertThat(panel.state.value.suggestions).isInstanceOf(SuggestionsUi.Ready::class.java)
        }

    @Test
    fun `the anyway action does nothing outside the Nothing to reply to state`() =
        runTest {
            val generator = FakeGenerator()
            val panel = panel(generator, chat = whatsAppChat)
            panel.start()
            advanceUntilIdle()
            assertThat(generator.calls).isEqualTo(1)

            panel.suggestFollowUpsAnyway()
            advanceUntilIdle()

            assertThat(generator.calls).isEqualTo(1)
        }

    @Test
    fun `regenerate on Nothing to reply to checks again and still makes no call`() =
        runTest {
            val generator = FakeGenerator()
            val panel = panel(generator)
            panel.start()
            advanceUntilIdle()

            panel.regenerate()
            advanceUntilIdle()
            panel.toggleChip(QuickInstruction.Shorter)
            advanceUntilIdle()

            assertThat(generator.calls).isEqualTo(0)
            assertThat(assembler.calls).hasSize(3)
            assertThat(panel.state.value.suggestions).isEqualTo(SuggestionsUi.NothingToReply)
        }

    @Test
    fun `after the anyway tap, regenerate keeps asking for follow-ups, one call per tap`() =
        runTest {
            val generator = FakeGenerator()
            val panel = panel(generator)
            panel.start()
            advanceUntilIdle()
            panel.suggestFollowUpsAnyway()
            advanceUntilIdle()

            panel.regenerate()
            advanceUntilIdle()

            assertThat(generator.calls).isEqualTo(2)
            assertThat(generator.requests.map { it.followUpAnyway }).containsExactly(true, true)
        }

    @Test
    fun `an unanswered question of mine gets follow-ups on open, without the anyway tap`() =
        runTest {
            val generator = FakeGenerator()
            val asked =
                whatsAppChat.copy(
                    messages = whatsAppChat.messages + ChatMessage(null, "kitne baje?", null, isMe = true),
                )
            val panel = panel(generator, chat = asked)

            panel.start()
            advanceUntilIdle()

            assertThat(generator.calls).isEqualTo(1)
            assertThat(generator.requests.single().followUpAnyway).isFalse()
        }

    @Test
    fun `my message older than 2 hours gets follow-ups on open`() =
        runTest {
            val generator = FakeGenerator()
            assembler.historyOverride =
                listOf(
                    PromptMessage(
                        ChatMessage(null, "chal theek hai", null, isMe = true),
                        firstSeenAt = panelNow.toInstant().minus(Duration.ofHours(3)),
                    ),
                )
            val panel = panel(generator)

            panel.start()
            advanceUntilIdle()

            assertThat(generator.calls).isEqualTo(1)
        }

    @Test
    fun `every generation of one panel shares a panel id, another panel gets another`() =
        runTest {
            val first = panel(FakeGenerator(), chat = whatsAppChat)
            first.start()
            advanceUntilIdle()
            first.regenerate()
            advanceUntilIdle()
            val second = panel(FakeGenerator(), chat = whatsAppChat)
            second.start()
            advanceUntilIdle()

            val ids = feedback.recorded.map { it.panelId }
            assertThat(ids).hasSize(3)
            assertThat(ids[0]).isEqualTo(ids[1])
            assertThat(ids[2]).isNotEqualTo(ids[0])
        }
}
