package com.paragrudani.replyly.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.paragrudani.replyly.di.AppGraph
import com.paragrudani.replyly.insert.InputFieldRef
import com.paragrudani.replyly.panel.ReplyPanel
import com.paragrudani.replyly.parser.ChatParserRegistry
import com.paragrudani.replyly.parser.x.XFields
import com.paragrudani.replyly.policy.ScreenContext
import com.paragrudani.replyly.policy.SensitiveScreenPolicy
import com.paragrudani.replyly.policy.SensitivityVerdict
import com.paragrudani.replyly.service.AccessibilityComponent
import com.paragrudani.replyly.service.AccessibilityHost
import com.paragrudani.replyly.snooze.ShakeListener
import com.paragrudani.replyly.snooze.ShakeSwitch
import com.paragrudani.replyly.snooze.SnoozeKeeper
import com.paragrudani.replyly.snooze.SnoozeStore
import com.paragrudani.replyly.snooze.SnoozeText
import com.paragrudani.replyly.ui.theme.HapticEvent
import com.paragrudani.replyly.ui.theme.performReplylyHaptic
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.Clock
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
    /** Settings → Bubble (#55), hot; `null` (tests): the defaults, never turned off. */
    private val bubble: StateFlow<BubbleLook>? = null,
    /** The bubble's snooze (#56); `null` (tests): never snoozed, no target, no shake listener. */
    private val snooze: SnoozeStore? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) : AccessibilityComponent {
    private var scope: CoroutineScope? = null
    private var controller: OverlayController? = null
    private var keeper: SnoozeKeeper? = null
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
        // Rebuilt when the bubble size changes (#55); the lambdas below read the current one.
        val look = bubble?.value ?: BubbleLook()

        fun placementFor(windowPx: Int) =
            ButtonPlacement(buttonSizePx = windowPx, marginPx = dp(MARGIN_DP), maxInputLiftPx = dp(MAX_LIFT_DP))
        var placementSizePx = look.windowPx(density)
        var placement = placementFor(placementSizePx)
        val windowManager = service.getSystemService(WindowManager::class.java)
        val tapFlow = TapFlow(capture = { id -> host.captureWindowChecked(id) }, policy = policy, parsers = parsers)
        lateinit var controller: OverlayController
        lateinit var keeper: SnoozeKeeper
        val ui =
            OverlayWindows(
                context = service,
                panel = panel(),
                buttonSizePx = placementSizePx,
                onButtonTap = { controller.onButtonTapped() },
                onButtonMoved = { x, y -> controller.onButtonMoved(x, y) },
                // Posted: the panel's close button and Back run inside its own input dispatch,
                // which must finish before the window is removed.
                onPanelDismiss = { mainHandler.post { controller.onPanelClosed() } },
                dismissTarget = snooze?.let { DismissTargetWindow(service) },
                onDroppedOnTarget = { controller.onDroppedOnTarget() },
                // A choice is a tap on Replyly's own window: store it, the button hides on the change.
                snoozePicker = snooze?.let { SnoozePickerWindow(service) { choice -> keeper.snooze(choice) } },
            )
        controller =
            OverlayController(
                readFacts = ScreenFactsReader.of(service)::read,
                visibility =
                    ButtonVisibility(
                        service.packageName,
                        isAppAllowed = { pkg -> policy.evaluate(ScreenContext(pkg)) == SensitivityVerdict.Allowed },
                        isFieldAllowed = XFields::allowsButton,
                        isTurnedOff = { bubble?.value?.show == false },
                        snoozedUntil = { snooze?.until?.value },
                        now = clock::millis,
                    ),
                place = { shown ->
                    val metrics = windowManager.currentWindowMetrics
                    val screen = metrics.bounds
                    val topInset =
                        metrics.windowInsets
                            .getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
                            .top
                    val pkg = shown.input.packageName
                    val savedLift = positions?.liftDp(pkg)?.let { (it * density).roundToInt() }
                    placement.slot(
                        shown.keyboard,
                        shown.input.bounds,
                        screen.width(),
                        screen.height(),
                        topInset,
                        savedLift,
                        positions?.side(pkg) ?: ButtonSide.RIGHT,
                    )
                },
                rememberPosition = { shown, side, y ->
                    positions?.save(shown.input.packageName, placement.liftOf(y, shown.keyboard.top) / density, side)
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
        // Snooze (#56): every change and the expiry re-decide at once; the accelerometer runs only
        // while snoozed with the screen on.
        snooze?.let { store ->
            val sensors = service.getSystemService(SensorManager::class.java)
            keeper =
                SnoozeKeeper(
                    store = store,
                    now = clock::millis,
                    zone = clock.zone,
                    scope = scope,
                    shake = sensors?.let { ShakeListener(it, onShake = { keeper.onShake() }) } ?: ShakeSwitch {},
                    refresh = { controller.onEvent(OverlayEvent.WINDOWS) },
                    onShakeEnded = {
                        service.performReplylyHaptic(HapticEvent.SnoozeEnd)
                        Toast.makeText(service, SnoozeText.BACK, Toast.LENGTH_SHORT).show()
                    },
                    log = { Log.d(TAG, it) },
                    is24Hour = { DateFormat.is24HourFormat(service) },
                )
            keeper.start(screenOn = service.getSystemService(PowerManager::class.java)?.isInteractive ?: true)
            this.keeper = keeper
        }
        // Settings → Bubble applies live, without waiting for an accessibility event (#55).
        bubble?.let { looks ->
            scope.followBubbleLook(
                looks,
                density,
                onWindowSize = { px ->
                    if (px != placementSizePx) {
                        placementSizePx = px
                        placement = placementFor(px)
                    }
                },
                apply = ui::applyLook,
                refresh = { controller.onEvent(OverlayEvent.WINDOWS) },
            )
        }
        // Overlays draw above the lock screen: nothing of a chat may stay readable there.
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            controller.onScreenOff()
                            this@OverlayComponent.keeper?.setScreenOn(false)
                        }

                        Intent.ACTION_SCREEN_ON -> {
                            this@OverlayComponent.keeper?.setScreenOn(true)
                        }
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
        service.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
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
        keeper?.stop()
        keeper = null
        controller?.stop()
        controller = null
        scope?.cancel()
        scope = null
    }

    companion object {
        private const val TAG = "ReplylyOverlay"

        /** The visible circle. */
        const val BUTTON_DP = 44

        /**
         * The button window at 1.0x = its touch area (DESIGN.md: at least 48 dp); the rest is shadow
         * room. Other bubble sizes: [BubbleLook.windowDp].
         */
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
                bubble = graph.bubbleSettings.look,
                snooze = graph.snoozeStore,
                clock = graph.clock,
            )
    }
}
