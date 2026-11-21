package com.paragrudani.replyly.ui.debug

import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paragrudani.replyly.di.AppGraph
import com.paragrudani.replyly.parser.explain
import com.paragrudani.replyly.screen.ScreenSnapshotJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.seconds

data class DumpItem(
    val entry: DebugDumpStore.Entry,
    /** [DumpSummary] lines of the current parsers' result for this dump. */
    val summary: List<String>,
)

data class DebugUiState(
    val serviceOn: Boolean = false,
    /** Seconds left before the capture, while counting down. */
    val countdown: Int? = null,
    /** Result of the last capture or action, for the user. */
    val status: String? = null,
    val scramble: Boolean = false,
    /** Countdown before the capture: [DebugViewModel.DELAY_CHOICES]. */
    val delaySeconds: Int = DebugViewModel.DEFAULT_DELAY_SECONDS,
    val dumps: List<DumpItem> = emptyList(),
)

class DebugViewModel(
    private val graph: AppGraph,
) : ViewModel() {
    private val store = DebugDumpStore(File(graph.app.filesDir, DUMP_DIR))
    private val capturer =
        DumpCapturer(
            hosts = graph.accessibilityHostHolder.host,
            store = store,
            policy = graph.sensitiveScreenPolicy,
            registry = graph.chatParserRegistry,
            ownPackage = graph.app.packageName,
        )
    private val state = MutableStateFlow(DebugUiState())
    val ui: StateFlow<DebugUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            graph.accessibilityHostHolder.host.collect { host -> state.update { it.copy(serviceOn = host != null) } }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val items =
                withContext(Dispatchers.IO) {
                    store.list().map { entry ->
                        val summary =
                            runCatching { graph.chatParserRegistry.explain(store.read(entry.name)) }
                                .fold(DumpSummary::lines, DumpSummary::crashed)
                        DumpItem(entry, summary)
                    }
                }
            state.update { it.copy(dumps = items) }
        }
    }

    /**
     * Runs in the app scope, not the screen's: the user leaves Replyly during the countdown and the
     * capture must still happen. A toast reports the result over the other app.
     */
    fun startCapture() {
        if (state.value.countdown != null) return
        val delay = state.value.delaySeconds
        state.update { it.copy(countdown = delay, status = null) }
        graph.appScope.launch {
            // Never let a failure escape into the app scope (it would crash the app). Class name
            // only: exception messages can contain chat text.
            val message =
                runCatching {
                    capturer.capture(delay.seconds) { left -> state.update { it.copy(countdown = left) } }
                }.fold(::describe) { "capture failed (${it::class.simpleName})." }
            state.update { it.copy(countdown = null, status = message) }
            withContext(Dispatchers.Main) { Toast.makeText(graph.app, "Replyly: $message", Toast.LENGTH_LONG).show() }
            refresh()
        }
    }

    fun setScramble(on: Boolean) = state.update { it.copy(scramble = on) }

    fun setDelay(seconds: Int) {
        if (seconds in DELAY_CHOICES && state.value.countdown == null) state.update { it.copy(delaySeconds = seconds) }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(name) }
            refresh()
        }
    }

    /** The dump's JSON as it should leave the app: scrambled when the toggle is on. */
    suspend fun exportText(name: String): String =
        withContext(Dispatchers.IO) {
            if (state.value.scramble) {
                ScreenSnapshotJson.encode(Scrambler().scramble(store.read(name)))
            } else {
                store.readText(name)
            }
        }

    fun report(message: String) = state.update { it.copy(status = message) }

    private fun describe(outcome: DumpCapturer.Outcome): String =
        when (outcome) {
            DumpCapturer.Outcome.ServiceOff -> {
                "accessibility service is not running, nothing captured."
            }

            DumpCapturer.Outcome.NoWindow -> {
                "no active window to read. Try again."
            }

            DumpCapturer.Outcome.StillOnReplyly -> {
                "Replyly was still on top. Pick a longer delay, tap Dump, then switch to the chat."
            }

            is DumpCapturer.Outcome.Blocked -> {
                "not saved: this screen is protected (${outcome.reason.name.lowercase().replace('_', ' ')})."
            }

            is DumpCapturer.Outcome.Saved -> {
                if (outcome.chatKeyChecked) {
                    "dump saved (${outcome.entry.packageName})."
                } else {
                    "dump saved (${outcome.entry.packageName}); no chat parsed, so the chat blocklist was not checked."
                }
            }
        }

    companion object {
        val DELAY_CHOICES = listOf(5, 10, 15)
        const val DEFAULT_DELAY_SECONDS = 10
        const val DUMP_DIR = "debug-dumps"
    }
}
