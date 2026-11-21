package com.paragrudani.replyly.overlay

import com.paragrudani.replyly.insert.InputFieldRef
import com.paragrudani.replyly.panel.PanelRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the controller drives: two overlay windows and a transient notice. */
interface OverlayUi {
    fun showButton(position: ButtonPosition)

    fun hideButton()

    fun openPanel(request: PanelRequest)

    fun closePanel()

    fun showNotice(notice: Notice)
}

enum class Notice {
    /** The screen is blocked (spec 4): "Replyly is off here". */
    OFF_HERE,

    /** Nothing that looks like a chat on screen. */
    NO_CHAT,
}

enum class OverlayEvent {
    /** Windows changed, window state changed, or focus moved: the button may need to show or hide. */
    WINDOWS,

    /** Content changed somewhere. Only matters while the button is shown (the field may be gone). */
    CONTENT,
}

/**
 * The button's state machine (ticket #1). Events are coalesced: a burst of them leads to one
 * read of [readFacts] (two cheap IPC calls, no tree walk) after [settleMillis]. Content changes
 * are ignored while the button is hidden. The button stays hidden while the panel is open.
 * Every method runs on the main thread ([scope] is a main-thread scope).
 */
class OverlayController(
    private val readFacts: () -> ScreenFacts,
    private val visibility: ButtonVisibility,
    private val place: (ButtonDecision.Show) -> ButtonPosition,
    private val ui: OverlayUi,
    private val tap: suspend (FocusedInput) -> TapOutcome,
    private val rememberTarget: (InputFieldRef) -> Unit,
    private val scope: CoroutineScope,
    private val settleMillis: Long = 60,
    /** Event names and enum values only, never chat text. */
    private val log: (String) -> Unit = {},
) {
    private var lastDecision: String? = null

    private var shown: ButtonDecision.Show? = null
    private var shownAt: ButtonPosition? = null
    private var panelOpen = false
    private var tapInFlight = false
    private var pending: Job? = null

    fun onEvent(event: OverlayEvent) {
        if (event == OverlayEvent.CONTENT && shown == null) return
        if (pending?.isActive == true) return
        pending =
            scope.launch {
                delay(settleMillis)
                refresh()
            }
    }

    fun onButtonTapped() {
        val current = shown ?: return
        if (tapInFlight || panelOpen) return
        tapInFlight = true
        scope.launch {
            try {
                val outcome = tap(current.input)
                log("tap outcome=${outcome.logName}")
                when (outcome) {
                    is TapOutcome.Open -> openPanel(current.input, outcome)
                    is TapOutcome.Blocked -> ui.showNotice(Notice.OFF_HERE)
                    TapOutcome.NotAChat -> ui.showNotice(Notice.NO_CHAT)
                }
            } finally {
                tapInFlight = false
            }
        }
    }

    fun onPanelClosed() {
        if (!panelOpen) return
        panelOpen = false
        ui.closePanel()
        onEvent(OverlayEvent.WINDOWS)
    }

    /** Service disconnected: remove everything. */
    fun stop() {
        pending?.cancel()
        hide()
        if (panelOpen) {
            panelOpen = false
            ui.closePanel()
        }
    }

    private fun openPanel(
        input: FocusedInput,
        outcome: TapOutcome.Open,
    ) {
        rememberTarget(InputFieldRef(input.windowId, input.packageName, input.viewId, input.bounds))
        panelOpen = true
        hide()
        ui.openPanel(PanelRequest(outcome.chat))
    }

    private fun refresh() {
        if (panelOpen) return hide()
        val decision = visibility.decide(readFacts())
        val name = if (decision is ButtonDecision.Hide) "hidden reason=${decision.reason}" else "shown"
        if (name != lastDecision) log("button $name")
        lastDecision = name
        when (decision) {
            is ButtonDecision.Hide -> {
                hide()
            }

            is ButtonDecision.Show -> {
                shown = decision
                val position = place(decision)
                if (position != shownAt) {
                    shownAt = position
                    ui.showButton(position)
                }
            }
        }
    }

    private fun hide() {
        shown = null
        if (shownAt != null) {
            shownAt = null
            ui.hideButton()
        }
    }
}

private val TapOutcome.logName: String
    get() =
        when (this) {
            is TapOutcome.Open -> "open"
            is TapOutcome.Blocked -> "blocked reason=$reason"
            TapOutcome.NotAChat -> "no_chat"
        }
