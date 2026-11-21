package com.paragrudani.replyly.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.paragrudani.replyly.di.AppGraph
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
    private val positions: ButtonPositionStore? = null,
) : AccessibilityComponent {
    private var scope: CoroutineScope? = null
    private var controller: OverlayController? = null
    private var screenOff: Pair<Context, BroadcastReceiver>? = null

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
            ButtonPlacement(buttonSizePx = dp(TOUCH_DP), marginPx = dp(MARGIN_DP), maxInputLiftPx = dp(MAX_LIFT_DP))
        val windowManager = service.getSystemService(WindowManager::class.java)
        val tapFlow = TapFlow(capture = { id -> host.captureWindowChecked(id) }, policy = policy, parsers = parsers)
        lateinit var controller: OverlayController
        val ui =
            OverlayWindows(
                context = service,
                panel = panel(),
                buttonSizePx = dp(TOUCH_DP),
                onButtonTap = { controller.onButtonTapped() },
                onButtonMoved = { y -> controller.onButtonMoved(y) },
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
                    val metrics = windowManager.currentWindowMetrics
                    val screen = metrics.bounds
                    val topInset =
                        metrics.windowInsets
                            .getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
                            .top
                    val savedLift = positions?.liftDp(shown.input.packageName)?.let { (it * density).roundToInt() }
                    placement.slot(
                        shown.keyboard,
                        shown.input.bounds,
                        screen.width(),
                        screen.height(),
                        topInset,
                        savedLift,
                    )
                },
                rememberPosition = { shown, y ->
                    positions?.save(shown.input.packageName, placement.liftOf(y, shown.keyboard.top) / density)
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
        // Overlays draw above the lock screen: nothing of a chat may stay readable there.
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) controller.onScreenOff()
                }
            }
        service.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        screenOff = service to receiver
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
        controller?.onEvent(kind, event.packageName?.toString())
    }

    override fun onDisconnected() {
        screenOff?.let { (context, receiver) ->
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        screenOff = null
        controller?.stop()
        controller = null
        scope?.cancel()
        scope = null
    }

    companion object {
        private const val TAG = "ReplylyOverlay"

        /** The visible circle. */
        const val BUTTON_DP = 44

        /** The button window = its touch area (DESIGN.md: at least 48 dp); the rest is shadow room. */
        const val TOUCH_DP = 56

        /** Window to keyboard/screen edge; the circle sits (TOUCH_DP - BUTTON_DP) / 2 further in, 8 dp. */
        const val MARGIN_DP = 2

        /** The chat's input bar may be up to this tall (multi-line) and still count as "on the keyboard". */
        const val MAX_LIFT_DP = 200

        fun create(graph: AppGraph): OverlayComponent =
            OverlayComponent(
                policy = graph.sensitiveScreenPolicy,
                parsers = graph.chatParserRegistry,
                panel = { graph.replyPanel },
                rememberTarget = graph.accessibilityReplyInserter::rememberTarget,
                positions = graph.buttonPositionStore,
            )
    }
}
