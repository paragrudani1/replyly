package com.paragrudani.replyly.overlay

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.paragrudani.replyly.di.AppGraph
import com.paragrudani.replyly.insert.AccessibilityReplyInserter
import com.paragrudani.replyly.insert.InputFieldRef
import com.paragrudani.replyly.panel.ReplyPanel
import com.paragrudani.replyly.parser.ChatParserRegistry
import com.paragrudani.replyly.policy.ScreenContext
import com.paragrudani.replyly.policy.SensitiveScreenPolicy
import com.paragrudani.replyly.policy.SensitivityVerdict
import com.paragrudani.replyly.service.AccessibilityComponent
import com.paragrudani.replyly.service.AccessibilityHost
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Ticket #1: the floating button above the keyboard and the panel window it opens. It only
 * wires Android to [OverlayController]; the decisions live in [ButtonVisibility],
 * [ButtonPlacement] and [TapFlow].
 */
class OverlayComponent(
    private val policy: SensitiveScreenPolicy,
    private val parsers: ChatParserRegistry,
    private val panel: () -> ReplyPanel,
    private val rememberTarget: (InputFieldRef) -> Unit,
) : AccessibilityComponent {
    private var scope: CoroutineScope? = null
    private var controller: OverlayController? = null

    override fun onConnected(host: AccessibilityHost) {
        onDisconnected()
        val service = host.service
        val density = service.resources.displayMetrics.density

        fun dp(value: Int) = (value * density).roundToInt()

        val mainHandler = Handler(Looper.getMainLooper())
        val errors =
            CoroutineExceptionHandler { _, e ->
                // Class name only: exception messages can contain chat text.
                Log.e(TAG, "overlay failed: ${e::class.simpleName}")
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)
        val placement =
            ButtonPlacement(buttonSizePx = dp(BUTTON_DP), marginPx = dp(MARGIN_DP), maxInputLiftPx = dp(MAX_LIFT_DP))
        val windowManager = service.getSystemService(WindowManager::class.java)
        val tapFlow = TapFlow(capture = { id -> host.captureWindowChecked(id) }, policy = policy, parsers = parsers)
        lateinit var controller: OverlayController
        val ui =
            OverlayWindows(
                context = service,
                panel = panel(),
                buttonSizePx = dp(BUTTON_DP),
                onButtonTap = { controller.onButtonTapped() },
                // Posted: the panel's close button and Back run inside its own input dispatch,
                // which must finish before the window is removed.
                onPanelDismiss = { mainHandler.post { controller.onPanelClosed() } },
            )
        controller =
            OverlayController(
                readFacts = ScreenFactsReader.of(service)::read,
                visibility =
                    ButtonVisibility(service.packageName) { pkg ->
                        policy.evaluate(ScreenContext(pkg)) == SensitivityVerdict.Allowed
                    },
                place = { shown ->
                    val screen = windowManager.currentWindowMetrics.bounds
                    placement.place(shown.keyboard, shown.input.bounds, screen.width(), screen.height())
                },
                ui = ui,
                // Reading and parsing the tree is many IPC calls: off the main thread.
                tap = { input -> withContext(Dispatchers.Default) { tapFlow.run(input.packageName, input.windowId) } },
                rememberTarget = rememberTarget,
                scope = scope,
                log = { Log.d(TAG, it) },
            )
        this.scope = scope
        this.controller = controller
    }

    override fun onEvent(
        event: AccessibilityEvent,
        host: AccessibilityHost,
    ) {
        val kind =
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                -> OverlayEvent.WINDOWS

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> OverlayEvent.CONTENT

                else -> return
            }
        controller?.onEvent(kind)
    }

    override fun onDisconnected() {
        controller?.stop()
        controller = null
        scope?.cancel()
        scope = null
    }

    companion object {
        private const val TAG = "ReplylyOverlay"
        const val BUTTON_DP = 44
        const val MARGIN_DP = 8

        /** The chat's input bar may be up to this tall (multi-line) and still count as "on the keyboard". */
        const val MAX_LIFT_DP = 200

        fun create(graph: AppGraph): OverlayComponent =
            OverlayComponent(
                policy = graph.sensitiveScreenPolicy,
                parsers = graph.chatParserRegistry,
                panel = { graph.replyPanel },
                rememberTarget = { ref -> (graph.replyInserter as? AccessibilityReplyInserter)?.rememberTarget(ref) },
            )
    }
}
