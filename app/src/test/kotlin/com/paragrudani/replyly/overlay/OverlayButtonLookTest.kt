package com.paragrudani.replyly.overlay

import android.app.Activity
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.panel.PanelRequest
import com.paragrudani.replyly.panel.ReplyPanel
import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.ui.theme.ReplylyMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.math.roundToInt

/**
 * Settings → Bubble on the real button (#55): size on the circle and the window, alpha, the idle
 * dot, one tap on the dot, and live updates while the button shows. Separate from
 * [OverlayButtonViewTest] (touch and drag, ticket A).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class OverlayButtonLookTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val density = context.resources.displayMetrics.density
    private var taps = 0
    private val listener =
        object : OverlayButtonView.Listener {
            override fun onTap() {
                taps++
            }
        }
    private var time = SystemClock.uptimeMillis()

    @Before
    fun reducedMotion() {
        // OverlayWindows builds its views with the system motion setting: make every look instant.
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After
    fun restoreMotion() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun View.touch(action: Int) {
        time += 16
        val event = MotionEvent.obtain(time, time, action, 50f, 50f, 0)
        dispatchTouchEvent(event)
        event.recycle()
    }

    /** A button on screen (attached, shown), as OverlayWindows would add it. */
    private fun shownButton(look: BubbleLook): OverlayButtonView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = OverlayButtonView(activity, listener, ReplylyMotion(reduced = true)).apply { applyLook(look) }
        val size = look.windowPx(density)
        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(size, size)) })
        view.animateIn()
        return view
    }

    private fun idle(millis: Long) = ShadowLooper.idleMainLooper(millis, java.util.concurrent.TimeUnit.MILLISECONDS)

    @Test
    fun `size sets the circle, opacity the alpha, busy dims relative to it`() {
        val view = OverlayButtonView(context, listener, ReplylyMotion(reduced = true))

        view.applyLook(BubbleLook(size = 1.3f, opacityPercent = 40))

        assertThat(view.mark.layoutParams.width).isEqualTo((44 * 1.3f * density).roundToInt())
        assertThat(view.mark.layoutParams.height).isEqualTo((44 * 1.3f * density).roundToInt())
        assertThat(view.mark.alpha).isWithin(1e-6f).of(0.4f)
        view.setBusy(true)
        assertThat(view.mark.alpha).isWithin(1e-6f).of(0.4f * OverlayButtonView.BUSY_ALPHA)
        view.setBusy(false)
        assertThat(view.mark.alpha).isWithin(1e-6f).of(0.4f)
    }

    @Test
    fun `below full opacity the circle has no shadow, at 100 percent it has one`() {
        val view = OverlayButtonView(context, listener, ReplylyMotion(reduced = true))

        view.applyLook(BubbleLook(opacityPercent = 60))
        assertThat(view.mark.elevation).isEqualTo(0f)

        view.applyLook(BubbleLook(opacityPercent = 100))
        assertThat(view.mark.elevation).isGreaterThan(0f)
    }

    @Test
    fun `a new window takes the window size and look of the current settings`() {
        val windows = OverlayWindows(context, noPanel, buttonSizePx = 112, onButtonTap = {}, onPanelDismiss = {})
        windows.applyLook(BubbleLook(size = 0.85f, opacityPercent = 60))

        windows.showButton(ButtonSlot(ButtonPosition(900, 1000), minY = 400, maxY = 1200))

        val button = checkNotNull(windows.buttonView)
        val params = button.layoutParams as WindowManager.LayoutParams
        assertThat(params.width).isEqualTo(BubbleLook(size = 0.85f).windowPx(density))
        assertThat(params.height).isEqualTo(BubbleLook(size = 0.85f).windowPx(density))
        assertThat(button.mark.layoutParams.width).isEqualTo(BubbleLook(size = 0.85f).circlePx(density))
        assertThat(button.mark.alpha).isWithin(1e-6f).of(0.6f)
        windows.hideButton(immediate = true)
    }

    @Test
    fun `shrink when idle - a dot after 5 s, the window keeps its size, press brings it back`() {
        val look = BubbleLook(shrinkWhenIdle = true)
        val view = shownButton(look)

        idle(4_900)
        assertThat(view.shrunk).isFalse()
        idle(200)
        assertThat(view.shrunk).isTrue()
        assertThat(view.mark.scaleX).isEqualTo(BubbleLook.DOT_SCALE)
        assertThat(view.mark.imageAlpha).isEqualTo(0)
        assertThat(view.layoutParams.width).isEqualTo(look.windowPx(density))
        assertThat(view.width).isAtLeast((48 * density).roundToInt())

        view.touch(MotionEvent.ACTION_DOWN)
        assertThat(view.shrunk).isFalse()
        assertThat(view.mark.scaleX).isEqualTo(OverlayButtonView.PRESSED_SCALE)
        assertThat(view.mark.imageAlpha).isEqualTo(255)
        view.touch(MotionEvent.ACTION_CANCEL)
        assertThat(view.mark.scaleX).isEqualTo(1f)

        // The release starts the 5 s again.
        idle(4_900)
        assertThat(view.shrunk).isFalse()
        idle(200)
        assertThat(view.shrunk).isTrue()
    }

    @Test
    fun `one tap on the dot calls onTap once`() {
        val view = shownButton(BubbleLook(shrinkWhenIdle = true))
        idle(5_100)
        assertThat(view.shrunk).isTrue()

        view.touch(MotionEvent.ACTION_DOWN)
        view.touch(MotionEvent.ACTION_UP)

        assertThat(taps).isEqualTo(1)
        assertThat(view.shrunk).isFalse()
    }

    @Test
    fun `shrink when idle off, busy or TalkBack - never a dot`() {
        val off = shownButton(BubbleLook(shrinkWhenIdle = false))
        val busy = shownButton(BubbleLook(shrinkWhenIdle = true)).apply { setBusy(true) }
        idle(30_000)
        assertThat(off.shrunk).isFalse()
        assertThat(busy.shrunk).isFalse()

        val manager = context.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setTouchExplorationEnabled(true)
        try {
            val talkBack = shownButton(BubbleLook(shrinkWhenIdle = true))
            idle(30_000)
            assertThat(talkBack.shrunk).isFalse()
        } finally {
            shadowOf(manager).setTouchExplorationEnabled(false)
        }
    }

    @Test
    fun `turning shrink off grows a dot back at once`() {
        val view = shownButton(BubbleLook(shrinkWhenIdle = true))
        idle(5_100)
        assertThat(view.shrunk).isTrue()

        view.applyLook(BubbleLook(shrinkWhenIdle = false))

        assertThat(view.shrunk).isFalse()
        assertThat(view.mark.scaleX).isEqualTo(1f)
        assertThat(view.mark.imageAlpha).isEqualTo(255)
    }

    // Live updates ---------------------------------------------------------------------------------

    private val noPanel =
        object : ReplyPanel {
            @Composable
            override fun Content(
                request: PanelRequest,
                onClose: () -> Unit,
            ) = Unit
        }

    @Test
    fun `a settings change while the button shows applies with no accessibility event`() {
        val keyboard = Bounds(0, 1440, 1080, 2340)
        val input = FocusedInput(7, "com.whatsapp", true, false, "com.whatsapp:id/entry", Bounds(30, 1300, 900, 1420))
        val facts =
            ScreenFacts(
                listOf(
                    WindowFacts(7, WindowKind.APPLICATION, Bounds(0, 0, 1080, 2340), active = true),
                    WindowFacts(9, WindowKind.INPUT_METHOD, keyboard),
                ),
                input,
            )

        fun dp(value: Int) = (value * density).roundToInt()

        // The same wiring as OverlayComponent.onConnected, with fixed screen facts.
        val looks = MutableStateFlow(BubbleLook())

        fun placementFor(px: Int) =
            ButtonPlacement(px, dp(OverlayComponent.MARGIN_DP), dp(OverlayComponent.MAX_LIFT_DP))
        var placement = placementFor(looks.value.windowPx(density))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var tapsThroughController = 0
        val windows =
            OverlayWindows(
                context,
                noPanel,
                buttonSizePx = looks.value.windowPx(density),
                onButtonTap = {},
                onPanelDismiss = {},
            )
        val controller =
            OverlayController(
                readFacts = { facts },
                visibility =
                    ButtonVisibility(
                        "com.paragrudani.replyly",
                        isAppAllowed = { true },
                        isTurnedOff = { !looks.value.show },
                    ),
                place = { shown -> placement.slot(shown.keyboard, shown.input.bounds, 1080, 2340) },
                ui = windows,
                tap = {
                    tapsThroughController++
                    TapOutcome.NotAChat
                },
                rememberTarget = {},
                scope = scope,
            )
        scope.followBubbleLook(
            looks,
            density,
            onWindowSize = { placement = placementFor(it) },
            apply = windows::applyLook,
            refresh = { controller.onEvent(OverlayEvent.WINDOWS) },
        )
        try {
            idle(100)
            val first = checkNotNull(windows.buttonView)
            assertThat((first.layoutParams as WindowManager.LayoutParams).width).isEqualTo(dp(56))

            // Bigger and fainter: only the settings flow changes, no onEvent from a screen change.
            looks.value = BubbleLook(size = 1.3f, opacityPercent = 40)
            idle(16) // One frame: the settle delay (60 ms) has not run yet.
            val bigger = BubbleLook(size = 1.3f).windowPx(density)
            val params = first.layoutParams as WindowManager.LayoutParams
            assertThat(params.width).isEqualTo(bigger)
            assertThat(params.height).isEqualTo(bigger)
            assertThat(first.mark.alpha).isWithin(1e-6f).of(0.4f)
            idle(100)
            // Re-placed for the new size: same margin from the keyboard's right edge, to the window.
            assertThat(windows.buttonView).isSameInstanceAs(first)
            assertThat(params.x).isEqualTo(keyboard.right - dp(OverlayComponent.MARGIN_DP) - bigger)

            // Show bubble off hides it; on shows it again, both without an accessibility event.
            looks.value = looks.value.copy(show = false)
            idle(100)
            assertThat(windows.buttonView).isNull()
            looks.value = looks.value.copy(show = true)
            idle(100)
            assertThat(windows.buttonView).isNotNull()
            assertThat((windows.buttonView!!.layoutParams as WindowManager.LayoutParams).width).isEqualTo(bigger)

            // A settings change never reaches the tap path.
            assertThat(tapsThroughController).isEqualTo(0)
            assertThat(taps).isEqualTo(0)
        } finally {
            windows.hideButton(immediate = true)
            scope.cancel()
        }
    }
}
