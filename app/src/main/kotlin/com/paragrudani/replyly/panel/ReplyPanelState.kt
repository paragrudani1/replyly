package com.paragrudani.replyly.panel

import com.paragrudani.replyly.feedback.FeedbackLog
import com.paragrudani.replyly.history.ContactNoteStore
import com.paragrudani.replyly.history.HistoryRepository
import com.paragrudani.replyly.insert.InsertResult
import com.paragrudani.replyly.insert.ReplyInserter
import com.paragrudani.replyly.llm.NothingToReplyReason
import com.paragrudani.replyly.llm.ReplyGenerator
import com.paragrudani.replyly.llm.ReplyTarget
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.llm.SuggestionFailureException
import com.paragrudani.replyly.llm.SuggestionRequest
import com.paragrudani.replyly.llm.SuggestionRequestAssembler
import com.paragrudani.replyly.llm.SuggestionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.util.UUID
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
        /** X only: the post length each card shows a counter against ("212/280"). */
        val lengthLimit: Int? = null,
        /** X only: cards dropped because they could not be trimmed to [lengthLimit]. */
        val droppedOverLimit: Int = 0,
    ) : SuggestionsUi

    data class Error(
        val error: PanelError,
    ) : SuggestionsUi

    /**
     * Nothing needs an answer ([ReplyTarget.NothingToReply], for [reason]): no call was made.
     * "Suggest follow-ups anyway" ([ReplyPanelState.suggestFollowUpsAnyway]) makes one.
     */
    data class NothingToReply(
        val reason: NothingToReplyReason = NothingToReplyReason.MyMessageLast,
    ) : SuggestionsUi
}

data class PanelUiState(
    val suggestions: SuggestionsUi = SuggestionsUi.Loading,
    /** The instruction field, at most [instructionRoom] for the selected [chips]. */
    val instruction: String = "",
    /** True while a card's text is being inserted; further taps are ignored. */
    val inserting: Boolean = false,
    /** The card being inserted (highlighted); stays set after a successful insert while the panel closes. */
    val insertingIndex: Int? = null,
    /** A card was inserted and the panel is closing: every further action is ignored. */
    val finished: Boolean = false,
    /** Why the last insert failed. The panel stays open so the user can try again. */
    val insertError: String? = null,
    /** Contact note and delete-history actions: only for chats with history (not generic apps). */
    val showChatActions: Boolean = true,
    /** The open contact-note editor, or null. */
    val note: NoteEditorUi? = null,
    val confirmingDelete: Boolean = false,
    /** One-line result of a note, delete or copy action. */
    val notice: String? = null,
    /** Selected quick-chips; they apply to every generation until tapped again. */
    val chips: Set<QuickInstruction> = emptySet(),
    /** Generations started so far: 1 is the first load, more means a regenerate (cards animate out and in). */
    val generation: Int = 0,
    /** A long-press just copied a card: shows the "Copied" confirmation for [COPIED_VISIBLE_MILLIS]. */
    val copied: Boolean = false,
    /** Successful copies so far; each new one plays the copy haptic once. */
    val copies: Int = 0,
    /** An X reply or quote (#30): X's quick chips, and no "Nothing to reply to". */
    val isX: Boolean = false,
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

/** How long the "Copied" confirmation stays. */
const val COPIED_VISIBLE_MILLIS = 1_500L

/**
 * Plain state holder for one open panel. Created with an unkeyed `remember` in [ComposeReplyPanel],
 * so there is one state per panel open. Its coroutines run in their own [Job] on [scope]'s
 * dispatcher, cancelled when [scope] ends (the composition's scope); [dispose] cancels that job when the composition forgets the state, so
 * closing the panel cancels any call still in flight.
 *
 * LLM calls: one on [start], one per [regenerate], one per [toggleChip] tap and one on
 * [suggestFollowUpsAnyway], each ignored while a call runs, while an insert runs and after the
 * panel finished. Before each call the assembled request is checked with [ReplyTarget.decide]:
 * when there is nothing to reply to, the panel shows [SuggestionsUi.NothingToReply] and makes no
 * call, until the user taps "Suggest follow-ups anyway", Regenerate, or a chip that leaves an
 * instruction (from then on, this panel's generations are follow-ups). On X there is no such check:
 * replying to my own post continues my thread. Each of those is a user
 * tap and makes one call (invariant 4). The panel's out-and-in card animation only follows [PanelUiState.suggestions]; it
 * never starts or delays a call.
 *
 * The instruction sent is [combineInstruction]: the selected chips' fixed prompts, then the typed
 * text. After a successful insert the state is [PanelUiState.finished] and ignores every action, so
 * taps while the panel animates closed cannot insert, copy or call again.
 */
class ReplyPanelState(
    private val request: PanelRequest,
    private val deps: ReplyPanelDependencies,
    private val scope: CoroutineScope,
    private val onClose: () -> Unit,
    private val clipboard: SuggestionClipboard = SuggestionClipboard.Unavailable,
) {
    private val chatKey = request.chat.chatKey
    private val mutableState =
        MutableStateFlow(PanelUiState(showChatActions = request.chat.historyApplies, isX = request.chat.x != null))
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
    private var followUpAnyway = false
    private var generation: Job? = null

    /** Ties every feedback entry of this panel open together (insert rate per panel). */
    private val panelId = UUID.randomUUID().toString()
    private var noteLoading = false
    private var copiedTimer: Job? = null

    /** Call once when the panel opens (the user's button tap). Later calls do nothing. */
    fun start() {
        if (started) return
        started = true
        generate()
    }

    /** The field stops at [instructionRoom] for the selected chips, never inside an emoji. */
    fun onInstructionChange(text: String) {
        mutableState.update { it.copy(instruction = text.takeWholeCharacters(instructionRoom(it.chips))) }
    }

    /**
     * Regenerate and Retry: one new call with the current instruction. Ignored while one runs. On
     * the [SuggestionsUi.NothingToReply] state it is an explicit tap for follow-ups, like
     * [suggestFollowUpsAnyway] (with or without an instruction): one call.
     */
    fun regenerate() {
        if (mutableState.value.suggestions is SuggestionsUi.NothingToReply && canGenerate()) followUpAnyway = true
        generate()
    }

    /**
     * "Suggest follow-ups anyway" on the [SuggestionsUi.NothingToReply] state: one call for 3
     * follow-ups to my last message. Ignored in any other state.
     */
    fun suggestFollowUpsAnyway() {
        if (mutableState.value.suggestions !is SuggestionsUi.NothingToReply || !canGenerate()) return
        followUpAnyway = true
        generate()
    }

    /**
     * A quick-chip tap: toggles [chip] and makes one new call with the combined instruction.
     * Ignored (nothing changes) while a call or an insert runs, or after the panel finished.
     *
     * On the [SuggestionsUi.NothingToReply] state, a tap that leaves an instruction (a chip or
     * typed text) asks for follow-ups that follow it, like [suggestFollowUpsAnyway]: one call. A tap
     * that leaves no instruction only checks again (no call).
     *
     * A chip takes room from the typed text: if the field holds more than the new
     * [instructionRoom], the field itself is shortened (visible, with the counter at its limit), so
     * what is sent is exactly what the field shows.
     */
    fun toggleChip(chip: QuickInstruction) {
        if (!canGenerate()) return
        val nothingToReply = mutableState.value.suggestions is SuggestionsUi.NothingToReply
        mutableState.update {
            val chips = if (chip in it.chips) it.chips - chip else it.chips + chip
            it.copy(chips = chips, instruction = it.instruction.takeWholeCharacters(instructionRoom(chips)))
        }
        val current = mutableState.value
        if (nothingToReply && combineInstruction(current.instruction, current.chips) != null) followUpAnyway = true
        generate()
    }

    /**
     * No call while one runs, while a card is being inserted (the insert may still succeed and
     * close the panel), or after the panel finished.
     */
    private fun canGenerate(): Boolean {
        val current = mutableState.value
        return generation?.isActive != true && !current.inserting && !current.finished
    }

    private fun generate() {
        if (!canGenerate()) return
        val current = mutableState.value
        val instruction = combineInstruction(current.instruction, current.chips)
        mutableState.update {
            it.copy(
                suggestions = SuggestionsUi.Loading,
                insertError = null,
                generation = it.generation + 1,
                copied = false,
            )
        }
        generation =
            ownScope.launch {
                val suggestionRequest =
                    when (val assembled = assemble(instruction)) {
                        is Assembled.Ok -> {
                            assembled.request.copy(followUpAnyway = followUpAnyway)
                        }

                        is Assembled.Failed -> {
                            mutableState.update { it.copy(suggestions = SuggestionsUi.Error(assembled.error)) }
                            return@launch
                        }
                    }
                // Pure and local: deciding there is nothing to reply to costs no call. Not on X:
                // replying to my own post there is a normal reply (continuing my thread).
                val nothingReason = suggestionRequest.takeIf { it.x == null }?.let(ReplyTarget::nothingReason)
                if (nothingReason != null) {
                    mutableState.update { it.copy(suggestions = SuggestionsUi.NothingToReply(nothingReason)) }
                    return@launch
                }
                val next =
                    when (val result = ignoringErrors { deps.generator.generate(suggestionRequest) }) {
                        is SuggestionResult.Success -> {
                            val entryId = recordOrNull(result)
                            SuggestionsUi.Ready(
                                result.suggestions,
                                result.model,
                                result.latency,
                                entryId,
                                lengthLimit = result.lengthLimit,
                                droppedOverLimit = result.droppedOverLimit,
                            )
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
        if (current.inserting || current.finished) return
        mutableState.update { it.copy(inserting = true, insertingIndex = index, insertError = null) }
        ownScope.launch {
            when (val result = ignoringErrors { deps.inserter.insert(suggestion.text) }) {
                InsertResult.Inserted -> {
                    ready.feedbackEntryId?.let { id -> ignoringErrors { deps.feedbackLog.markInserted(id, index) } }
                    // Stays highlighted and ignores every tap while the panel animates closed.
                    mutableState.update { it.copy(inserting = false, finished = true) }
                    onClose()
                }

                InsertResult.NoFocusedField -> {
                    mutableState.update {
                        it.copy(inserting = false, insertingIndex = null, insertError = NO_FOCUSED_FIELD)
                    }
                }

                // Fixed text: the inserter's reason is never shown (it could carry node text).
                is InsertResult.Failed, null -> {
                    mutableState.update {
                        it.copy(inserting = false, insertingIndex = null, insertError = INSERT_FAILED)
                    }
                }
            }
        }
    }

    /**
     * Long-press on card [index]: copies its text (marked sensitive) instead of inserting it, and
     * shows "Copied" briefly. Nothing is inserted, the feedback entry is not marked, the panel
     * stays open. Ignored while loading, while inserting and after the panel finished.
     */
    fun copy(index: Int) {
        val current = mutableState.value
        val ready = current.suggestions as? SuggestionsUi.Ready ?: return
        val suggestion = ready.suggestions.getOrNull(index) ?: return
        if (current.inserting || current.finished) return
        val copied =
            try {
                clipboard.copy(suggestion.text)
            } catch (_: Exception) {
                // Fixed text only: the exception could describe the clip.
                false
            }
        copiedTimer?.cancel()
        if (!copied) {
            mutableState.update { it.copy(copied = false, notice = COPY_FAILED) }
            return
        }
        mutableState.update { it.copy(copied = true, copies = it.copies + 1, notice = null) }
        copiedTimer =
            ownScope.launch {
                delay(COPIED_VISIBLE_MILLIS)
                mutableState.update { it.copy(copied = false) }
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
            deps.feedbackLog.record(
                request.chat.chatKey,
                result.model,
                result.promptSent,
                result.suggestions,
                analysis = result.analysis,
                promptVersion = result.promptVersion,
                panelId = panelId,
                cacheReadTokens = result.usage.cacheReadTokens,
                xMode = request.chat.x?.mode,
            )
        }

    private sealed interface Assembled {
        data class Ok(
            val request: SuggestionRequest,
        ) : Assembled

        data class Failed(
            val error: PanelError,
        ) : Assembled
    }

    /** A known assembler failure gets its own card; anything else is a generic history read error. */
    private suspend fun assemble(instruction: String?): Assembled =
        try {
            Assembled.Ok(deps.assembler.assemble(request.chat, instruction))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SuggestionFailureException) {
            Assembled.Failed(e.failure.toPanelError())
        } catch (_: Exception) {
            Assembled.Failed(HISTORY_READ_ERROR)
        }
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

internal const val COPY_FAILED = "Could not copy the reply. Try again."
