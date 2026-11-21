package com.paragrudani.replyly.overlay

import com.paragrudani.replyly.insert.InputFieldRef
import com.paragrudani.replyly.panel.PanelRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the controller drives: two overlay windows and a transient notice. */
interface OverlayUi {
    /** Shows the button at [ButtonSlot.position], or moves it there if it is already shown. */
    fun showButton(slot: ButtonSlot)

    fun hideButton()

    fun openPanel(request: PanelRequest)

    fun closePanel()

    fun showNotice(notice: Notice)

    /** Dims the button while a tap is being read (it can take a few seconds). */
    fun setButtonBusy(busy: Boolean)
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

    /**
     * Content changed. Only matters while the button is shown and only from the chat's own app
     * (the field may be gone); the keyboard's own changes, one per keystroke, are ignored.
     */
    CONTENT,
}

/**
 * The button's state machine (ticket #1). Events are coalesced: a burst of them leads to one
 * read of [readFacts] (two cheap IPC calls, no tree walk) after [settleMillis]. Content changes
 * are ignored while the button is hidden. The button stays hidden while the panel is open.
 *
 * The panel stays open while the chat's input loses focus (the panel takes it). It closes on
 * Back, a tap outside, screen off ([onScreenOff]), or when the chat's window is gone (Home,
 * Recents, another app): otherwise a tapped suggestion could land in a different chat, or the
 * panel could stay readable above the lock screen.
 *
 * Every method runs on the main thread ([scope] is a main-thread scope).
 */
class OverlayController(
    private val readFacts: () -> ScreenFacts,
    private val visibility: ButtonVisibility,
    private val place: (ButtonDecision.Show) -> ButtonSlot,
    private val ui: OverlayUi,
    private val tap: suspend (FocusedInput) -> TapOutcome,
    private val rememberTarget: (InputFieldRef) -> Unit,
    private val scope: CoroutineScope,
    /** Remembers a spot the user dragged the button to, for the app in [ButtonDecision.Show]. */
    private val rememberPosition: (shown: ButtonDecision.Show, y: Int) -> Unit = { _, _ -> },
    private val settleMillis: Long = 60,
    /** Event names and enum values only, never chat text. */
    private val log: (String) -> Unit = {},
) {
    private var lastDecision: String? = null

    private var shown: ButtonDecision.Show? = null
    private var shownAt: ButtonSlot? = null

    /** The chat window the open panel belongs to; `null` while the panel is closed. */
    private var panelWindowId: Int? = null
    private val panelOpen get() = panelWindowId != null
    private var tapJob: Job? = null
    private var pending: Job? = null

    /** [packageName] is the event's source app; only needed for [OverlayEvent.CONTENT]. */
    fun onEvent(
        event: OverlayEvent,
        packageName: String? = null,
    ) {
        if (event == OverlayEvent.CONTENT && (shown == null || packageName != shown?.input?.packageName)) return
        if (pending?.isActive == true) return
        pending =
            scope.launch {
                delay(settleMillis)
                refresh()
            }
    }

    fun onButtonTapped() {
        val current = shown ?: return
        if (tapJob?.isActive == true || panelOpen) return
        ui.setButtonBusy(true)
        tapJob =
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
                    ui.setButtonBusy(false)
                }
            }
    }

    /**
     * The user dragged the button (or moved it with TalkBack) so its window top is at [y]. Only
     * remembers the spot: a drag never counts as a tap (the view tells them apart).
     */
    fun onButtonMoved(y: Int) {
        val current = shown ?: return
        val at = shownAt ?: return
        val clamped = at.clampY(y)
        shownAt = at.copy(position = at.position.copy(y = clamped))
        log("button moved")
        rememberPosition(current, clamped)
    }

    fun onPanelClosed() {
        if (!panelOpen) return
        closePanel()
        onEvent(OverlayEvent.WINDOWS)
    }

    /** The screen turned off: drop a running tap, close the panel and hide the button. */
    fun onScreenOff() {
        log("screen off")
        clear()
    }

    /** Service disconnected: remove everything. */
    fun stop() = clear()

    private fun clear() {
        pending?.cancel()
        tapJob?.cancel()
        hide()
        if (panelOpen) closePanel()
    }

    private fun closePanel() {
        panelWindowId = null
        ui.closePanel()
    }

    private fun openPanel(
        input: FocusedInput,
        outcome: TapOutcome.Open,
    ) {
        // Reading can take seconds: the user may have left the chat meanwhile.
        if (readFacts().windows.none { it.id == input.windowId }) {
            log("panel not opened reason=window_gone")
            onEvent(OverlayEvent.WINDOWS)
            return
        }
        rememberTarget(InputFieldRef(input.windowId, input.packageName, input.viewId, input.bounds))
        panelWindowId = input.windowId
        hide()
        ui.openPanel(PanelRequest(outcome.chat))
    }

    private fun refresh() {
        val facts = readFacts()
        val panelWindow = panelWindowId
        if (panelWindow != null) {
            // Window gone, not focus lost: the panel itself takes focus and the keyboard.
            if (facts.windows.any { it.id == panelWindow }) return hide()
            log("panel closed reason=window_gone")
            closePanel()
        }
        val decision = visibility.decide(facts)
        val name = if (decision is ButtonDecision.Hide) "hidden reason=${decision.reason}" else "shown"
        if (name != lastDecision) log("button $name")
        lastDecision = name
        when (decision) {
            is ButtonDecision.Hide -> {
                hide()
            }

            is ButtonDecision.Show -> {
                shown = decision
                val slot = place(decision)
                if (slot != shownAt) {
                    shownAt = slot
                    ui.showButton(slot)
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
