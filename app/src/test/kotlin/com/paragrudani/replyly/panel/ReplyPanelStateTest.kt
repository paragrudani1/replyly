package com.paragrudani.replyly.panel

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.llm.SuggestionResult
import com.paragrudani.replyly.parser.ParsedChat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplyPanelStateTest {
    private val assembler = FakeAssembler()
    private val inserter = FakeInserter()
    private val feedback = FakeFeedbackLog()
    private val notes = FakeNoteStore()
    private val history = FakeHistory()
    private var closed = 0

    private fun TestScope.panel(
        generator: FakeGenerator = FakeGenerator(),
        chat: ParsedChat = whatsAppChat,
    ): ReplyPanelState =
        ReplyPanelState(
            request = PanelRequest(chat),
            deps = ReplyPanelDependencies(assembler, generator, inserter, feedback, notes, history),
            scope = this,
            onClose = { closed++ },
        )

    @Test
    fun `opening shows loading, then the three suggestions`() =
        runTest {
            val panel = panel()

            panel.start()
            runCurrent()
            assertThat(panel.state.value.suggestions).isEqualTo(SuggestionsUi.Loading)

            advanceUntilIdle()
            val ready = panel.state.value.suggestions as SuggestionsUi.Ready
            assertThat(ready.suggestions).isEqualTo(threeSuggestions)
            assertThat(ready.model).isEqualTo("test/model")
            assertThat(assembler.calls).containsExactly(whatsAppChat to null)
        }

    @Test
    fun `a successful generation is recorded in the feedback log`() =
        runTest {
            val panel = panel(FakeGenerator(success(model = "m1")))

            panel.start()
            advanceUntilIdle()

            assertThat(feedback.recorded)
                .containsExactly(FakeFeedbackLog.Recorded(whatsAppChat.chatKey, "m1", "PROMPT", threeSuggestions))
        }

    @Test
    fun `each failure shows its own fixed message and action`() =
        runTest {
            val cases: Map<SuggestionResult.Failure, PanelError> =
                mapOf(
                    SuggestionResult.Failure.NoApiKey to
                        PanelError("No API key yet", "Add your API key in Settings.", ErrorAction.OpenSettings),
                    SuggestionResult.Failure.InvalidApiKey to
                        PanelError(
                            "API key not usable",
                            "The API key has invalid characters. Paste it again in Settings.",
                            ErrorAction.OpenSettings,
                        ),
                    SuggestionResult.Failure.LimitReached(dailyLimit = 50) to
                        PanelError(
                            "Daily limit reached",
                            "You have used all 50 requests for today. It resets tomorrow, or raise the limit in Settings.",
                            ErrorAction.OpenSettings,
                        ),
                    SuggestionResult.Failure.Timeout to
                        PanelError("The model took too long", "Check your connection and try again.", ErrorAction.Retry),
                    SuggestionResult.Failure.Network("No internet connection.") to
                        PanelError("Could not reach the API", "No internet connection.", ErrorAction.Retry),
                    SuggestionResult.Failure.Http(401, "API key rejected. Check the key in Settings.") to
                        PanelError("API key rejected (401)", "Check the key in Settings.", ErrorAction.OpenSettings),
                    SuggestionResult.Failure.Http(402, "Out of credits on the API account.") to
                        PanelError(
                            "Out of credits (402)",
                            "Add credits to your API account, then retry.",
                            ErrorAction.Retry,
                        ),
                    SuggestionResult.Failure.Http(429, "Rate limited. Wait a moment and try again.") to
                        PanelError(
                            "Too many requests (429)",
                            "The provider is rate limiting. Wait a moment, then retry.",
                            ErrorAction.Retry,
                        ),
                    SuggestionResult.Failure.Http(503, "The provider had an error. Try again.") to
                        PanelError("The API returned an error (503)", "The provider had an error. Try again.", ErrorAction.Retry),
                    SuggestionResult.Failure.Http(418, null) to
                        PanelError("The API returned an error (418)", null, ErrorAction.Retry),
                    SuggestionResult.Failure.BadOutput("reply cut off at the token limit") to
                        PanelError(
                            "The reply was cut off",
                            "The model ran out of room before finishing. Try again.",
                            ErrorAction.Retry,
                        ),
                    SuggestionResult.Failure.BadOutput("reply was not JSON") to
                        PanelError(
                            "Could not read the model's reply",
                            "It was not in the expected format. Try again.",
                            ErrorAction.Retry,
                        ),
                )

            for ((failure, expected) in cases) {
                val panel = panel(FakeGenerator(failure))
                panel.start()
                advanceUntilIdle()
                assertThat(panel.state.value.suggestions).isEqualTo(SuggestionsUi.Error(expected))
            }
            assertThat(feedback.recorded).isEmpty()
        }

    @Test
    fun `a failure while reading history shows a retryable error, without exception text`() =
        runTest {
            assembler.failWith = IllegalStateException("secret chat text")
            val panel = panel()

            panel.start()
            advanceUntilIdle()

            assertThat(panel.state.value.suggestions)
                .isEqualTo(
                    SuggestionsUi.Error(
                        PanelError(
                            "Could not read this chat's history",
                            "Try again. If it keeps failing, close and reopen the panel.",
                            ErrorAction.Retry,
                        ),
                    ),
                )
        }
}
