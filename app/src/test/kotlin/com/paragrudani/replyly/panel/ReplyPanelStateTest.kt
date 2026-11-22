package com.paragrudani.replyly.panel

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.insert.InsertResult
import com.paragrudani.replyly.insert.ReplyInserter
import com.paragrudani.replyly.llm.ReplyGenerator
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.llm.SuggestionFailureException
import com.paragrudani.replyly.llm.SuggestionRequest
import com.paragrudani.replyly.llm.SuggestionResult
import com.paragrudani.replyly.parser.ParsedChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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

            val recorded = feedback.recorded.single()
            assertThat(recorded.chatKey).isEqualTo(whatsAppChat.chatKey)
            assertThat(recorded.model).isEqualTo("m1")
            assertThat(recorded.prompt).isEqualTo("PROMPT")
            assertThat(recorded.suggestions).isEqualTo(threeSuggestions)
            assertThat(recorded.analysis).isEqualTo("ANALYSIS")
            assertThat(recorded.promptVersion).isEqualTo(2)
            assertThat(recorded.panelId).isNotEmpty()
        }

    @Test
    fun `regenerate passes the trimmed instruction and shows the new suggestions`() =
        runTest {
            val second = listOf(Suggestion("agree", "6 baje call karta hoon"), threeSuggestions[1], threeSuggestions[2])
            val generator = FakeGenerator(success(), success(second))
            val panel = panel(generator)
            panel.start()
            advanceUntilIdle()

            panel.onInstructionChange("  say I'll call at 6 ")
            panel.regenerate()
            runCurrent()
            assertThat(panel.state.value.suggestions).isEqualTo(SuggestionsUi.Loading)
            advanceUntilIdle()

            assertThat(assembler.calls.map { it.second }).containsExactly(null, "say I'll call at 6").inOrder()
            assertThat(generator.requests.last().instruction).isEqualTo("say I'll call at 6")
            assertThat((panel.state.value.suggestions as SuggestionsUi.Ready).suggestions).isEqualTo(second)
            assertThat(feedback.recorded).hasSize(2)
        }

    @Test
    fun `a blank instruction is sent as none`() =
        runTest {
            val panel = panel()
            panel.start()
            advanceUntilIdle()

            panel.onInstructionChange("   ")
            panel.regenerate()
            advanceUntilIdle()

            assertThat(assembler.calls.map { it.second }).containsExactly(null, null)
        }

    @Test
    fun `the instruction is capped at 300 characters`() =
        runTest {
            val panel = panel()
            panel.onInstructionChange("a".repeat(350))
            assertThat(panel.state.value.instruction).hasLength(300)
        }

    @Test
    fun `the LLM is called only on open and on regenerate, never twice at once`() =
        runTest {
            val generator = FakeGenerator()
            val panel = panel(generator)

            panel.start()
            panel.start()
            runCurrent()
            panel.regenerate() // ignored: a generation is already running
            advanceUntilIdle()
            assertThat(generator.requests).hasSize(1)

            panel.onInstructionChange("shorter")
            advanceUntilIdle()
            assertThat(generator.requests).hasSize(1)

            panel.regenerate()
            advanceUntilIdle()
            assertThat(generator.requests).hasSize(2)
        }

    @Test
    fun `retry after a failure generates again`() =
        runTest {
            val generator = FakeGenerator(SuggestionResult.Failure.Timeout, success())
            val panel = panel(generator)
            panel.start()
            advanceUntilIdle()
            assertThat(panel.state.value.suggestions).isInstanceOf(SuggestionsUi.Error::class.java)

            panel.regenerate()
            advanceUntilIdle()

            assertThat(panel.state.value.suggestions).isInstanceOf(SuggestionsUi.Ready::class.java)
        }

    @Test
    fun `tapping a card inserts its text, marks the feedback entry and closes`() =
        runTest {
            val panel = panel()
            panel.start()
            advanceUntilIdle()

            panel.insert(1)
            advanceUntilIdle()

            assertThat(inserter.inserted).containsExactly("Kal nahi ho payega yaar")
            assertThat(feedback.marked).containsExactly(41L to 1) // the fake's first entry id
            assertThat(closed).isEqualTo(1)
        }

    @Test
    fun `an insert into the latest generation marks that generation's entry`() =
        runTest {
            val panel = panel()
            panel.start()
            advanceUntilIdle()
            panel.regenerate()
            advanceUntilIdle()

            panel.insert(2)
            advanceUntilIdle()

            assertThat(feedback.marked).containsExactly(42L to 2)
        }

    @Test
    fun `no focused field shows an error and keeps the panel open`() =
        runTest {
            inserter.result = InsertResult.NoFocusedField
            val panel = panel()
            panel.start()
            advanceUntilIdle()

            panel.insert(0)
            advanceUntilIdle()

            assertThat(panel.state.value.insertError)
                .isEqualTo("Could not find the chat's message box. Tap it, then pick a reply again.")
            assertThat(feedback.marked).isEmpty()
            assertThat(closed).isEqualTo(0)
            assertThat(panel.state.value.suggestions).isInstanceOf(SuggestionsUi.Ready::class.java)
        }

    @Test
    fun `a failed insert shows fixed text, never the inserter's reason, and keeps the panel open`() =
        runTest {
            inserter.result = InsertResult.Failed("field is not editable")
            val panel = panel()
            panel.start()
            advanceUntilIdle()

            panel.insert(0)
            advanceUntilIdle()

            assertThat(panel.state.value.insertError)
                .isEqualTo("Could not insert the reply. Tap the message box, then try again.")
            assertThat(closed).isEqualTo(0)

            inserter.result = InsertResult.Inserted
            panel.insert(0)
            advanceUntilIdle()
            assertThat(closed).isEqualTo(1)
        }

    @Test
    fun `a feedback log failure still shows suggestions, and insert still works`() =
        runTest {
            feedback.failRecord = true
            val panel = panel()
            panel.start()
            advanceUntilIdle()
            assertThat(panel.state.value.suggestions).isInstanceOf(SuggestionsUi.Ready::class.java)

            panel.insert(0)
            advanceUntilIdle()

            assertThat(inserter.inserted).hasSize(1)
            assertThat(feedback.marked).isEmpty()
            assertThat(closed).isEqualTo(1)
        }

    @Test
    fun `contact note opens with the saved note, saves the edit and closes the editor`() =
        runTest {
            notes.notes.value = mapOf(whatsAppChat.chatKey to "College friend")
            val panel = panel()

            panel.openNote()
            advanceUntilIdle()
            assertThat(panel.state.value.note).isEqualTo(NoteEditorUi(text = "College friend"))

            panel.onNoteChange("College friend, lives in Pune")
            panel.saveNote()
            advanceUntilIdle()

            assertThat(notes.notes.value[whatsAppChat.chatKey]).isEqualTo("College friend, lives in Pune")
            assertThat(panel.state.value.note).isNull()
        }

    @Test
    fun `cancel closes the note editor without saving`() =
        runTest {
            val panel = panel()
            panel.openNote()
            advanceUntilIdle()
            assertThat(panel.state.value.note).isEqualTo(NoteEditorUi(text = ""))

            panel.onNoteChange("draft")
            panel.cancelNote()
            advanceUntilIdle()

            assertThat(notes.notes.value).isEmpty()
            assertThat(panel.state.value.note).isNull()
        }

    @Test
    fun `a failed note save keeps the editor open with an error`() =
        runTest {
            notes.failSet = true
            val panel = panel()
            panel.openNote()
            advanceUntilIdle()
            panel.onNoteChange("draft")

            panel.saveNote()
            advanceUntilIdle()

            assertThat(panel.state.value.note)
                .isEqualTo(NoteEditorUi(text = "draft", error = "Could not save the note. Try again."))
        }

    @Test
    fun `the note is capped at 1000 characters`() =
        runTest {
            val panel = panel()
            panel.openNote()
            advanceUntilIdle()

            panel.onNoteChange("n".repeat(1_200))

            assertThat(
                panel.state.value.note!!
                    .text,
            ).hasLength(1_000)
        }

    @Test
    fun `deleting this chat's history asks first, then deletes`() =
        runTest {
            val panel = panel()

            panel.requestDelete()
            assertThat(panel.state.value.confirmingDelete).isTrue()
            assertThat(history.deleted).isEmpty()

            panel.confirmDelete()
            advanceUntilIdle()

            assertThat(history.deleted).containsExactly(whatsAppChat.chatKey)
            assertThat(panel.state.value.confirmingDelete).isFalse()
            assertThat(panel.state.value.notice).isEqualTo("Saved messages for this chat were deleted.")
        }

    @Test
    fun `cancelling the delete confirmation deletes nothing`() =
        runTest {
            val panel = panel()
            panel.requestDelete()
            panel.cancelDelete()
            advanceUntilIdle()

            assertThat(history.deleted).isEmpty()
            assertThat(panel.state.value.confirmingDelete).isFalse()
        }

    @Test
    fun `a failed delete says so`() =
        runTest {
            history.failDelete = true
            val panel = panel()
            panel.requestDelete()
            panel.confirmDelete()
            advanceUntilIdle()

            assertThat(panel.state.value.notice).isEqualTo("Could not delete this chat's history. Try again.")
        }

    @Test
    fun `generic apps have no note or delete actions`() =
        runTest {
            val panel = panel(chat = genericChat)
            assertThat(panel.state.value.showChatActions).isFalse()

            panel.openNote()
            panel.requestDelete()
            panel.confirmDelete()
            advanceUntilIdle()

            assertThat(panel.state.value.note).isNull()
            assertThat(panel.state.value.confirmingDelete).isFalse()
            assertThat(history.deleted).isEmpty()
            assertThat(panel(chat = whatsAppChat).state.value.showChatActions).isTrue()
        }

    @Test
    fun `an inserter that throws shows a fixed error and keeps the panel open`() =
        runTest {
            val panel =
                ReplyPanelState(
                    request = PanelRequest(whatsAppChat),
                    deps =
                        ReplyPanelDependencies(
                            assembler,
                            FakeGenerator(),
                            object : ReplyInserter {
                                override suspend fun insert(text: String): InsertResult =
                                    throw IllegalStateException(text)
                            },
                            feedback,
                            notes,
                            history,
                        ),
                    scope = this,
                    onClose = { closed++ },
                )
            panel.start()
            advanceUntilIdle()

            panel.insert(0)
            advanceUntilIdle()

            assertThat(panel.state.value.insertError)
                .isEqualTo("Could not insert the reply. Tap the message box, then try again.")
            assertThat(closed).isEqualTo(0)
        }

    @Test
    fun `a generator that throws shows a retryable error, without exception text`() =
        runTest {
            val throwing =
                object : ReplyGenerator {
                    override suspend fun generate(request: SuggestionRequest): SuggestionResult =
                        throw IllegalStateException("secret chat text")
                }
            val panel =
                ReplyPanelState(
                    request = PanelRequest(whatsAppChat),
                    deps = ReplyPanelDependencies(assembler, throwing, inserter, feedback, notes, history),
                    scope = this,
                    onClose = { closed++ },
                )

            panel.start()
            advanceUntilIdle()

            assertThat(panel.state.value.suggestions)
                .isEqualTo(
                    SuggestionsUi.Error(
                        PanelError("Something went wrong", "Could not get suggestions. Try again.", ErrorAction.Retry),
                    ),
                )
        }

    @Test
    fun `each failure shows its own fixed message and action`() =
        runTest {
            val cases: Map<SuggestionResult.Failure, PanelError> =
                mapOf(
                    SuggestionResult.Failure.NoApiKey to
                        PanelError(
                            "No API key yet",
                            "Add your API key in Settings.",
                            ErrorAction.OpenSettings,
                            ErrorKind.Key,
                        ),
                    SuggestionResult.Failure.InvalidApiKey to
                        PanelError(
                            "API key not usable",
                            "The API key has invalid characters. Paste it again in Settings.",
                            ErrorAction.OpenSettings,
                            ErrorKind.Key,
                        ),
                    SuggestionResult.Failure.LimitReached(dailyLimit = 50) to
                        PanelError(
                            "Daily limit reached",
                            "You have used all 50 requests for today. " +
                                "It resets tomorrow, or raise the limit in Settings.",
                            ErrorAction.OpenSettings,
                            ErrorKind.Limit,
                        ),
                    SuggestionResult.Failure.InsecureEndpoint to
                        PanelError(
                            "Endpoint is not https",
                            "The API endpoint must start with https://. Fix it in Settings.",
                            ErrorAction.OpenSettings,
                            ErrorKind.Settings,
                        ),
                    SuggestionResult.Failure.Timeout to
                        PanelError(
                            "The model took too long",
                            "Check your connection and try again.",
                            ErrorAction.Retry,
                            ErrorKind.Connection,
                        ),
                    SuggestionResult.Failure.Network("No internet connection.") to
                        PanelError(
                            "Could not reach the API",
                            "No internet connection.",
                            ErrorAction.Retry,
                            ErrorKind.Connection,
                        ),
                    SuggestionResult.Failure.Http(401, "API key rejected. Check the key in Settings.") to
                        PanelError(
                            "API key rejected (401)",
                            "Check the key in Settings.",
                            ErrorAction.OpenSettings,
                            ErrorKind.Key,
                        ),
                    SuggestionResult.Failure.Http(402, "Out of credits on the API account.") to
                        PanelError(
                            "Out of credits (402)",
                            "Add credits to your API account, then retry.",
                            ErrorAction.Retry,
                            ErrorKind.Limit,
                        ),
                    SuggestionResult.Failure.Http(429, "Rate limited. Wait a moment and try again.") to
                        PanelError(
                            "Too many requests (429)",
                            "The provider is rate limiting. Wait a moment, then retry.",
                            ErrorAction.Retry,
                            ErrorKind.Limit,
                        ),
                    SuggestionResult.Failure.Http(503, "The provider had an error. Try again.") to
                        PanelError(
                            "The API returned an error (503)",
                            "The provider had an error. Try again.",
                            ErrorAction.Retry,
                            ErrorKind.Connection,
                        ),
                    SuggestionResult.Failure.Http(500, null) to
                        PanelError("The API returned an error (500)", null, ErrorAction.Retry, ErrorKind.Connection),
                    SuggestionResult.Failure.Http(599, null) to
                        PanelError("The API returned an error (599)", null, ErrorAction.Retry, ErrorKind.Connection),
                    SuggestionResult.Failure.Http(400, "Request rejected. Check the model id in Settings.") to
                        PanelError(
                            "The API returned an error (400)",
                            "Request rejected. Check the model id in Settings.",
                            ErrorAction.OpenSettings,
                            ErrorKind.Settings,
                        ),
                    SuggestionResult.Failure.Http(403, "Access denied for this key or model.") to
                        PanelError(
                            "The API returned an error (403)",
                            "Access denied for this key or model.",
                            ErrorAction.OpenSettings,
                            ErrorKind.Settings,
                        ),
                    SuggestionResult.Failure.Http(404, "Model or endpoint not found. Check Settings.") to
                        PanelError(
                            "The API returned an error (404)",
                            "Model or endpoint not found. Check Settings.",
                            ErrorAction.OpenSettings,
                            ErrorKind.Settings,
                        ),
                    SuggestionResult.Failure.Http(413, "Prompt too large for this model.") to
                        PanelError(
                            "The API returned an error (413)",
                            "Prompt too large for this model.",
                            ErrorAction.OpenSettings,
                            ErrorKind.Settings,
                        ),
                    SuggestionResult.Failure.Http(418, null) to
                        PanelError("The API returned an error (418)", null, ErrorAction.Retry),
                    SuggestionResult.Failure.BadOutput("reply cut off at the token limit", cutOff = true) to
                        PanelError(
                            "The reply was cut off",
                            "The model ran out of room before finishing. Try again.",
                            ErrorAction.Retry,
                        ),
                    // Only the flag decides; the wording of the reason does not.
                    SuggestionResult.Failure.BadOutput("cut off, but not at the token limit") to
                        PanelError(
                            "Could not read the model's reply",
                            "It was not in the expected format. Try again.",
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
                            ErrorKind.Storage,
                        ),
                    ),
                )
        }

    @Test
    fun `a locked storage key shows its own fixed, retryable error`() =
        runTest {
            assembler.failWith = SuggestionFailureException(SuggestionResult.Failure.StorageLocked)
            val panel = panel()

            panel.start()
            advanceUntilIdle()

            assertThat(panel.state.value.suggestions)
                .isEqualTo(
                    SuggestionsUi.Error(
                        PanelError(
                            "Secure storage is locked",
                            "Replyly's encrypted storage is locked right now. Try again in a moment.",
                            ErrorAction.Retry,
                            ErrorKind.Storage,
                        ),
                    ),
                )
        }

    @Test
    fun `closing during a call cancels it, stays loading, records nothing, does not close`() =
        runTest {
            val generator = FakeGenerator()
            val panel = panel(generator)

            panel.start()
            runCurrent()
            assertThat(generator.calls).isEqualTo(1)

            panel.dispose()
            advanceUntilIdle()

            assertThat(panel.state.value.suggestions).isEqualTo(SuggestionsUi.Loading)
            assertThat(feedback.recorded).isEmpty()
            assertThat(closed).isEqualTo(0)
        }

    @Test
    fun `cancelling the parent scope during a call also stops it`() =
        runTest {
            val parent = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val generator = FakeGenerator()
            val panel =
                ReplyPanelState(
                    request = PanelRequest(whatsAppChat),
                    deps = ReplyPanelDependencies(assembler, generator, inserter, feedback, notes, history),
                    scope = parent,
                    onClose = { closed++ },
                )

            panel.start()
            runCurrent()
            parent.cancel()
            advanceUntilIdle()

            assertThat(generator.calls).isEqualTo(1)
            assertThat(panel.state.value.suggestions).isEqualTo(SuggestionsUi.Loading)
            assertThat(feedback.recorded).isEmpty()
            assertThat(closed).isEqualTo(0)
        }

    @Test
    fun `a note that fails to load shows a notice and opens no editor`() =
        runTest {
            notes.failGet = true
            val panel = panel()
            panel.start()
            advanceUntilIdle()

            panel.openNote()
            advanceUntilIdle()

            assertThat(panel.state.value.note).isNull()
            assertThat(panel.state.value.notice).isEqualTo("Could not open the note. Try again.")
        }
}
