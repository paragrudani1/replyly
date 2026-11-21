package com.paragrudani.replyly.panel

import com.paragrudani.replyly.feedback.FeedbackLog
import com.paragrudani.replyly.history.ContactNoteStore
import com.paragrudani.replyly.history.HistoryRepository
import com.paragrudani.replyly.insert.ReplyInserter
import com.paragrudani.replyly.llm.ReplyGenerator
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.llm.SuggestionRequest
import com.paragrudani.replyly.llm.SuggestionRequestAssembler
import com.paragrudani.replyly.llm.SuggestionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    ) : SuggestionsUi

    data class Error(
        val error: PanelError,
    ) : SuggestionsUi
}

data class PanelUiState(
    val suggestions: SuggestionsUi = SuggestionsUi.Loading,
    /** The instruction field, at most [MAX_INSTRUCTION_CHARS]. */
    val instruction: String = "",
)

/** The prompt keeps 300 characters of instruction (decision #4), so the field stops there too. */
const val MAX_INSTRUCTION_CHARS = 300

/**
 * Plain state holder for one open panel. Created with `remember` in [ComposeReplyPanel], so it
 * lives exactly as long as the composition; [scope] is the composition's scope.
 */
class ReplyPanelState(
    private val request: PanelRequest,
    private val deps: ReplyPanelDependencies,
    private val scope: CoroutineScope,
    private val onClose: () -> Unit,
) {
    private val mutableState = MutableStateFlow(PanelUiState())
    val state: StateFlow<PanelUiState> = mutableState.asStateFlow()

    private var started = false
    private var generation: Job? = null

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
        val instruction = mutableState.value.instruction.trim().ifEmpty { null }
        mutableState.update { it.copy(suggestions = SuggestionsUi.Loading) }
        generation =
            scope.launch {
                val suggestionRequest =
                    assembleOrNull(instruction) ?: run {
                        mutableState.update { it.copy(suggestions = SuggestionsUi.Error(HISTORY_READ_ERROR)) }
                        return@launch
                    }
                val next =
                    when (val result = deps.generator.generate(suggestionRequest)) {
                        is SuggestionResult.Success -> {
                            deps.feedbackLog.record(request.chat.chatKey, result.model, result.promptSent, result.suggestions)
                            SuggestionsUi.Ready(result.suggestions, result.model, result.latency)
                        }
                        is SuggestionResult.Failure -> SuggestionsUi.Error(result.toPanelError())
                    }
                mutableState.update { it.copy(suggestions = next) }
            }
    }

    private suspend fun assembleOrNull(instruction: String?): SuggestionRequest? =
        try {
            deps.assembler.assemble(request.chat, instruction)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
}
