package com.paragrudani.replyly.panel

import com.paragrudani.replyly.feedback.FeedbackLog
import com.paragrudani.replyly.history.ContactNoteStore
import com.paragrudani.replyly.history.HistoryRepository
import com.paragrudani.replyly.insert.InsertResult
import com.paragrudani.replyly.insert.ReplyInserter
import com.paragrudani.replyly.llm.ReplyGenerator
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.llm.SuggestionRequest
import com.paragrudani.replyly.llm.SuggestionRequestAssembler
import com.paragrudani.replyly.llm.SuggestionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.time.Duration

/** What the panel needs from [com.paragrudani.replyly.di.AppGraph]. */
data class ReplyPanelDependencies(
    val assembler: SuggestionRequestAssembler,
    val generator: ReplyGenerator,
    val inserter: ReplyInserter,
    val feedbackLog: FeedbackLog,
    val contactNotes: ContactNoteStore,
    val history: HistoryRepository,
)

sealed interface SuggestionsUi {
    data object Loading : SuggestionsUi

    data class Ready(
        val suggestions: List<Suggestion>,
        val model: String,
        val latency: Duration,
        /** Null when the feedback log could not store the entry; inserting still works. */
        val feedbackEntryId: Long?,
    ) : SuggestionsUi

    data class Error(
        val error: PanelError,
    ) : SuggestionsUi
}

data class PanelUiState(
    val suggestions: SuggestionsUi = SuggestionsUi.Loading,
    /** The instruction field, at most [MAX_INSTRUCTION_CHARS]. */
    val instruction: String = "",
    /** True while a card's text is being inserted; further taps are ignored. */
    val inserting: Boolean = false,
    /** Why the last insert failed. The panel stays open so the user can try again. */
    val insertError: String? = null,
    /** Contact note and delete-history actions: only for chats with history (not generic apps). */
    val showChatActions: Boolean = true,
    /** The open contact-note editor, or null. */
    val note: NoteEditorUi? = null,
    val confirmingDelete: Boolean = false,
    /** One-line result of a note or delete action. */
    val notice: String? = null,
)

data class NoteEditorUi(
    val text: String,
    val saving: Boolean = false,
    val error: String? = null,
)

/** The prompt keeps 1,000 characters of the note (decision #4), so the editor stops there too. */
const val MAX_NOTE_CHARS = 1_000

/** The prompt keeps 300 characters of instruction (decision #4), so the field stops there too. */
const val MAX_INSTRUCTION_CHARS = 300

/**
 * Plain state holder for one open panel. Created with an unkeyed `remember` in [ComposeReplyPanel],
 * so there is one state per panel open. Its coroutines run in their own [Job] on [scope]'s
 * dispatcher, cancelled when [scope] ends (the composition's scope); [dispose] cancels that job when the composition forgets the state, so
 * closing the panel cancels any call still in flight.
 */
class ReplyPanelState(
    private val request: PanelRequest,
    private val deps: ReplyPanelDependencies,
    private val scope: CoroutineScope,
    private val onClose: () -> Unit,
) {
    private val chatKey = request.chat.chatKey
    private val mutableState = MutableStateFlow(PanelUiState(showChatActions = request.chat.historyApplies))
    val state: StateFlow<PanelUiState> = mutableState.asStateFlow()

    // Created on first use, never during composition (the composition scope's context is not
    // available there).
    private var disposed = false
    private val ownScope: CoroutineScope by lazy {
        // Not a structural child: nothing waits for this job to complete, but it is cancelled
        // together with [scope] (the composition going away) or by [dispose], whichever is first.
        val job = Job()
        if (disposed) job.cancel()
        val parentHandle = scope.coroutineContext[Job]?.invokeOnCompletion { job.cancel() }
        job.invokeOnCompletion { parentHandle?.dispose() }
        scope + job
    }

    private var started = false
    private var generation: Job? = null
    private var noteLoading = false

    /** Call once when the panel opens (the user's button tap). Later calls do nothing. */
    fun start() {
        if (started) return
        started = true
        generate()
    }

    fun onInstructionChange(text: String) {
        mutableState.update { it.copy(instruction = text.take(MAX_INSTRUCTION_CHARS)) }
    }

    /** Regenerate and Retry: one new call with the current instruction. Ignored while one runs. */
    fun regenerate() = generate()

    private fun generate() {
        if (generation?.isActive == true) return
        val instruction =
            mutableState.value.instruction
                .trim()
                .ifEmpty { null }
        mutableState.update { it.copy(suggestions = SuggestionsUi.Loading, insertError = null) }
        generation =
            ownScope.launch {
                val suggestionRequest =
                    assembleOrNull(instruction) ?: run {
                        mutableState.update { it.copy(suggestions = SuggestionsUi.Error(HISTORY_READ_ERROR)) }
                        return@launch
                    }
                val next =
                    when (val result = ignoringErrors { deps.generator.generate(suggestionRequest) }) {
                        is SuggestionResult.Success -> {
                            val entryId = recordOrNull(result)
                            SuggestionsUi.Ready(result.suggestions, result.model, result.latency, entryId)
                        }

                        is SuggestionResult.Failure -> {
                            SuggestionsUi.Error(result.toPanelError())
                        }

                        null -> {
                            SuggestionsUi.Error(UNEXPECTED_ERROR)
                        }
                    }
                mutableState.update { it.copy(suggestions = next) }
            }
    }

    /**
     * Tapping card [index]: puts its text into the chat's input field, marks the feedback entry
     * and closes the panel. On failure the panel stays open and shows why. Never sends.
     *
     * Closing the panel while `markInserted` still runs cancels it: the text is already in the
     * chat's field, only that feedback mark is lost.
     */
    fun insert(index: Int) {
        val current = mutableState.value
        val ready = current.suggestions as? SuggestionsUi.Ready ?: return
        val suggestion = ready.suggestions.getOrNull(index) ?: return
        if (current.inserting) return
        mutableState.update { it.copy(inserting = true, insertError = null) }
        ownScope.launch {
            when (val result = ignoringErrors { deps.inserter.insert(suggestion.text) }) {
                InsertResult.Inserted -> {
                    ready.feedbackEntryId?.let { id -> ignoringErrors { deps.feedbackLog.markInserted(id, index) } }
                    mutableState.update { it.copy(inserting = false) }
                    onClose()
                }

                InsertResult.NoFocusedField -> {
                    mutableState.update { it.copy(inserting = false, insertError = NO_FOCUSED_FIELD) }
                }

                // Fixed text: the inserter's reason is never shown (it could carry node text).
                is InsertResult.Failed, null -> {
                    mutableState.update { it.copy(inserting = false, insertError = INSERT_FAILED) }
                }
            }
        }
    }

    /** Opens the editor with this chat's saved note. Ignored for generic apps. */
    fun openNote() {
        if (!request.chat.historyApplies || mutableState.value.note != null || noteLoading) return
        noteLoading = true
        mutableState.update { it.copy(notice = null, confirmingDelete = false) }
        ownScope.launch {
            // A failed load must not open an empty editor: saving it would delete the real note.
            val saved = ignoringErrors { Result.success(deps.contactNotes.get(chatKey)) }
            noteLoading = false
            mutableState.update {
                if (saved == null) {
                    it.copy(notice = "Could not open the note. Try again.")
                } else {
                    it.copy(note = NoteEditorUi(text = saved.getOrNull().orEmpty()))
                }
            }
        }
    }

    fun onNoteChange(text: String) {
        mutableState.update { state ->
            state.copy(note = state.note?.copy(text = text.take(MAX_NOTE_CHARS), error = null))
        }
    }

    /** Saves the note (blank deletes it) and closes the editor; on failure the editor stays open. */
    fun saveNote() {
        val editor = mutableState.value.note ?: return
        if (editor.saving) return
        mutableState.update { it.copy(note = editor.copy(saving = true, error = null)) }
        ownScope.launch {
            val saved = ignoringErrors { deps.contactNotes.set(chatKey, editor.text.trim()) } != null
            mutableState.update {
                if (saved) {
                    it.copy(note = null, notice = "Note saved.")
                } else {
                    it.copy(note = editor.copy(saving = false, error = "Could not save the note. Try again."))
                }
            }
        }
    }

    fun cancelNote() {
        mutableState.update { if (it.note?.saving == true) it else it.copy(note = null) }
    }

    /** First step of "Delete this chat's history": shows the confirmation. Ignored for generic apps. */
    fun requestDelete() {
        if (!request.chat.historyApplies) return
        mutableState.update { it.copy(confirmingDelete = true, notice = null) }
    }

    fun cancelDelete() {
        mutableState.update { it.copy(confirmingDelete = false) }
    }

    /** Deletes the saved messages of this chat (the contact note stays). */
    fun confirmDelete() {
        if (!mutableState.value.confirmingDelete) return
        mutableState.update { it.copy(confirmingDelete = false) }
        ownScope.launch {
            val deleted = ignoringErrors { deps.history.deleteChat(chatKey) } != null
            val notice =
                if (deleted) DELETED_NOTICE else "Could not delete this chat's history. Try again."
            mutableState.update { it.copy(notice = notice) }
        }
    }

    /** Cancels everything this state started. Called when the composition forgets the state. */
    fun dispose() {
        disposed = true
        ownScope.cancel()
    }

    /** The feedback log is for judging quality later; its failure must not block the user. */
    private suspend fun recordOrNull(result: SuggestionResult.Success): Long? =
        ignoringErrors {
            deps.feedbackLog.record(request.chat.chatKey, result.model, result.promptSent, result.suggestions)
        }

    private suspend fun assembleOrNull(instruction: String?): SuggestionRequest? =
        ignoringErrors { deps.assembler.assemble(request.chat, instruction) }
}

/** Runs [block]; any exception except cancellation becomes null. Exception text is never shown or logged. */
private suspend fun <T> ignoringErrors(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

internal const val NO_FOCUSED_FIELD = "Could not find the chat's message box. Tap it, then pick a reply again."

internal const val INSERT_FAILED = "Could not insert the reply. Tap the message box, then try again."

internal const val DELETED_NOTICE = "Saved messages for this chat were deleted."
