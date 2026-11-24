package com.paragrudani.replyly.overlay

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.R
import com.paragrudani.replyly.panel.PanelRequest
import com.paragrudani.replyly.panel.ReplyPanel
import com.paragrudani.replyly.ui.theme.ReplylyMotion
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The button View's touch handling (drag versus tap), accessibility, and the window it drags. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class OverlayButtonViewTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private var taps = 0
    private val drags = mutableListOf<Int>()
    private var dragEnds = 0
    private val steps = mutableListOf<Int>()

    private val listener =
        object : OverlayButtonView.Listener {
            override fun onTap() {
                taps++
            }

            override fun onDrag(dy: Int) {
                drags += dy
            }

            override fun onDragEnd() {
                dragEnds++
            }

            override fun onMoveStep(direction: Int) {
                steps += direction
            }
        }

    private val view = OverlayButtonView(context, listener, ReplylyMotion(reduced = true))

    private var time = SystemClock.uptimeMillis()

    private fun View.touch(
        action: Int,
        y: Float,
        x: Float = 50f,
    ) {
        time += 16
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        dispatchTouchEvent(event)
        event.recycle()
    }

    @Test
    fun `a tap goes through the click path once`() {
        view.touch(MotionEvent.ACTION_DOWN, 500f)
        view.touch(MotionEvent.ACTION_UP, 500f)

        assertThat(taps).isEqualTo(1)
        assertThat(dragEnds).isEqualTo(0)
    }

    @Test
    fun `a drag does not trigger a tap`() {
        view.touch(MotionEvent.ACTION_DOWN, 500f)
        view.touch(MotionEvent.ACTION_MOVE, 450f)
        view.touch(MotionEvent.ACTION_MOVE, 300f)
        view.touch(MotionEvent.ACTION_UP, 300f)

        assertThat(taps).isEqualTo(0)
        assertThat(drags.last()).isEqualTo(-200)
        assertThat(dragEnds).isEqualTo(1)
    }

    @Test
    fun `a drag back to the start point does not trigger a tap`() {
        view.touch(MotionEvent.ACTION_DOWN, 500f)
        view.touch(MotionEvent.ACTION_MOVE, 380f)
        view.touch(MotionEvent.ACTION_MOVE, 500f)
        view.touch(MotionEvent.ACTION_UP, 500f)

        assertThat(taps).isEqualTo(0)
        assertThat(dragEnds).isEqualTo(1)
    }

    @Test
    fun `the press look shows while the finger is down and clears on up`() {
        view.touch(MotionEvent.ACTION_DOWN, 500f)
        assertThat(view.mark.scaleX).isEqualTo(OverlayButtonView.PRESSED_SCALE)

        view.touch(MotionEvent.ACTION_UP, 500f)
        assertThat(view.mark.scaleX).isEqualTo(1f)
    }

    @Test
    fun `busy dims the mark`() {
        view.setBusy(true)
        assertThat(view.mark.alpha).isEqualTo(OverlayButtonView.BUSY_ALPHA)
        view.setBusy(false)
        assertThat(view.mark.alpha).isEqualTo(1f)
    }

    @Test
    fun `a leaving button swallows touches and never taps`() {
        var removed = false
        view.animateOut { removed = true }

        view.touch(MotionEvent.ACTION_DOWN, 500f)
        view.touch(MotionEvent.ACTION_UP, 500f)

        assertThat(removed).isTrue()
        assertThat(taps).isEqualTo(0)
    }

    @Test
    fun `TalkBack reads a label and offers move up and down`() {
        assertThat(view.contentDescription.toString()).isEqualTo("Replyly: suggest replies")
        val info = AccessibilityNodeInfo.obtain()
        view.onInitializeAccessibilityNodeInfo(info)
        val labels = info.actionList.associate { it.id to it.label?.toString() }
        assertThat(labels).containsEntry(R.id.overlay_action_move_up, "Move up")
        assertThat(labels).containsEntry(R.id.overlay_action_move_down, "Move down")

        view.performAccessibilityAction(R.id.overlay_action_move_up, null)
        view.performAccessibilityAction(R.id.overlay_action_move_down, null)
        assertThat(steps).containsExactly(-1, 1).inOrder()
    }

    @Test
    fun `a 2D drag reaches the listener with dx and dy and never taps`() {
        val moves = mutableListOf<Pair<Int, Int>>()
        var ends = 0
        var twoDTaps = 0
        val twoD =
            OverlayButtonView(
                context,
                object : OverlayButtonView.Listener {
                    override fun onTap() {
                        twoDTaps++
                    }

                    override fun onDrag(
                        dx: Int,
                        dy: Int,
                    ) {
                        moves += dx to dy
                    }

                    override fun onDragEnd() {
                        ends++
                    }
                },
                ReplylyMotion(reduced = true),
            )

        twoD.touch(MotionEvent.ACTION_DOWN, y = 500f, x = 800f)
        twoD.touch(MotionEvent.ACTION_MOVE, y = 490f, x = 700f)
        twoD.touch(MotionEvent.ACTION_MOVE, y = 300f, x = 100f)
        twoD.touch(MotionEvent.ACTION_UP, y = 320f, x = 90f)

        assertThat(moves.first()).isEqualTo(-100 to -10)
        assertThat(moves.last()).isEqualTo(-710 to -180)
        assertThat(ends).isEqualTo(1)
        assertThat(twoDTaps).isEqualTo(0)
        // Released anywhere: the drag look clears and the button is never dimmed.
        assertThat(twoD.mark.scaleX).isEqualTo(1f)
        assertThat(twoD.mark.alpha).isEqualTo(1f)
    }

    @Test
    fun `a sideways drag reaches an old vertical-only listener as dy`() {
        view.touch(MotionEvent.ACTION_DOWN, y = 500f, x = 800f)
        view.touch(MotionEvent.ACTION_MOVE, y = 520f, x = 300f)
        view.touch(MotionEvent.ACTION_UP, y = 520f, x = 300f)

        assertThat(drags.last()).isEqualTo(20)
        assertThat(taps).isEqualTo(0)
    }

    @Test
    fun `the edge actions are offered only when they would move the button, and never tap`() {
        val edges = mutableListOf<ButtonSide>()
        var onRight = true
        val sided =
            OverlayButtonView(
                context,
                object : OverlayButtonView.Listener {
                    override fun onTap() {
                        taps++
                    }

                    override fun onMoveToEdge(side: ButtonSide) {
                        edges += side
                        onRight = side == ButtonSide.RIGHT
                    }

                    override fun canMoveToEdge(side: ButtonSide): Boolean = (side == ButtonSide.RIGHT) != onRight
                },
                ReplylyMotion(reduced = true),
            )
        val labels = sided.actionLabels()
        assertThat(labels).containsEntry(R.id.overlay_action_move_left_edge, "Move to left edge")
        assertThat(labels).doesNotContainKey(R.id.overlay_action_move_right_edge)
        assertThat(sided.performAccessibilityAction(R.id.overlay_action_move_right_edge, null)).isFalse()

        assertThat(sided.performAccessibilityAction(R.id.overlay_action_move_left_edge, null)).isTrue()

        assertThat(edges).containsExactly(ButtonSide.LEFT)
        val after = sided.actionLabels()
        assertThat(after).containsEntry(R.id.overlay_action_move_right_edge, "Move to right edge")
        assertThat(after).doesNotContainKey(R.id.overlay_action_move_left_edge)
        assertThat(taps).isEqualTo(0)
    }

    @Test
    fun `a listener without sides offers no edge actions`() {
        assertThat(
            view.actionIds(),
        ).containsNoneOf(R.id.overlay_action_move_left_edge, R.id.overlay_action_move_right_edge)
    }

    @Test
    fun `an accessibility click is a tap`() {
        view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
        assertThat(taps).isEqualTo(1)
    }

    // The window ---------------------------------------------------------------------------------

    private val noPanel =
        object : ReplyPanel {
            @Composable
            override fun Content(
                request: PanelRequest,
                onClose: () -> Unit,
            ) = Unit
        }

    @Test
    fun `dragging the window stays within the slot, remembers the drop spot and never taps`() {
        val moved = mutableListOf<Int>()
        var windowTaps = 0
        val windows =
            OverlayWindows(
                context = context,
                panel = noPanel,
                buttonSizePx = 104,
                onButtonTap = { windowTaps++ },
                onPanelDismiss = {},
                onButtonMoved = { _, y -> moved += y },
            )
        windows.showButton(ButtonSlot(ButtonPosition(900, 1000), minY = 400, maxY = 1200))
        val button = checkNotNull(windows.buttonView)

        button.touch(MotionEvent.ACTION_DOWN, 1050f)
        button.touch(MotionEvent.ACTION_MOVE, 900f)
        assertThat((button.layoutParams as WindowManager.LayoutParams).y).isEqualTo(850)
        button.touch(MotionEvent.ACTION_MOVE, 0f)
        button.touch(MotionEvent.ACTION_UP, 0f)

        assertThat((button.layoutParams as WindowManager.LayoutParams).y).isEqualTo(400)
        assertThat(moved).containsExactly(400)
        assertThat(windowTaps).isEqualTo(0)
        windows.hideButton()
    }

    @Test
    fun `a TalkBack step moves the window one step and remembers it`() {
        val moved = mutableListOf<Int>()
        val windows =
            OverlayWindows(context, noPanel, buttonSizePx = 104, onButtonTap = {
            }, onPanelDismiss = {}, onButtonMoved = { _, y ->
                moved += y
            })
        windows.showButton(ButtonSlot(ButtonPosition(900, 1000), minY = 400, maxY = 1050))

        checkNotNull(windows.buttonView).performAccessibilityAction(R.id.overlay_action_move_up, null)
        checkNotNull(windows.buttonView).performAccessibilityAction(R.id.overlay_action_move_down, null)
        checkNotNull(windows.buttonView).performAccessibilityAction(R.id.overlay_action_move_down, null)

        assertThat(moved).containsExactly(896, 1000, 1050).inOrder()

        // At the bottom of the range: "Move down" is not offered and does nothing.
        val button = checkNotNull(windows.buttonView)
        assertThat(button.actionIds()).contains(R.id.overlay_action_move_up)
        assertThat(button.actionIds()).doesNotContain(R.id.overlay_action_move_down)
        assertThat(button.performAccessibilityAction(R.id.overlay_action_move_down, null)).isFalse()
        assertThat(moved).hasSize(3)
        windows.hideButton()
    }

    @Test
    fun `at the top of the range only move down is offered`() {
        val windows = OverlayWindows(context, noPanel, buttonSizePx = 104, onButtonTap = {}, onPanelDismiss = {})
        windows.showButton(ButtonSlot(ButtonPosition(900, 400), minY = 400, maxY = 1050))

        val ids = checkNotNull(windows.buttonView).actionIds()

        assertThat(ids).doesNotContain(R.id.overlay_action_move_up)
        assertThat(ids).contains(R.id.overlay_action_move_down)
        windows.hideButton(immediate = true)
    }

    @Test
    fun `a fading button stops taking touches, and an immediate hide removes it at once`() {
        val windows = OverlayWindows(context, noPanel, buttonSizePx = 104, onButtonTap = {}, onPanelDismiss = {})
        windows.showButton(ButtonSlot(ButtonPosition(900, 1000), minY = 400, maxY = 1200))
        val button = checkNotNull(windows.buttonView)

        windows.hideButton()

        // Fading out (the looper is paused, so the fade has not ended yet).
        assertThat(windows.leavingView).isSameInstanceAs(button)
        assertThat(button.parent).isNotNull()
        val flags = (button.layoutParams as WindowManager.LayoutParams).flags
        assertThat(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isNotEqualTo(0)

        windows.hideButton(immediate = true)

        assertThat(windows.leavingView).isNull()
        assertThat(button.parent).isNull()
    }

    @Test
    fun `an immediate hide removes a shown button without a fade`() {
        val windows = OverlayWindows(context, noPanel, buttonSizePx = 104, onButtonTap = {}, onPanelDismiss = {})
        windows.showButton(ButtonSlot(ButtonPosition(900, 1000), minY = 400, maxY = 1200))
        val button = checkNotNull(windows.buttonView)

        windows.hideButton(immediate = true)

        assertThat(windows.buttonView).isNull()
        assertThat(windows.leavingView).isNull()
        assertThat(button.parent).isNull()
    }

    @Test
    fun `a dark mode flip while showing recolours the button`() {
        val light = view.mark.backgroundTintList?.defaultColor

        RuntimeEnvironment.setQualifiers("+night")
        view.dispatchConfigurationChanged(context.resources.configuration)

        val dark = overlayButtonScheme(context)
        assertThat(view.mark.backgroundTintList?.defaultColor).isEqualTo(dark.primary.toArgb())
        assertThat(view.mark.imageTintList?.defaultColor).isEqualTo(dark.onPrimary.toArgb())
        assertThat(view.mark.backgroundTintList?.defaultColor).isNotEqualTo(light)
    }

    private fun View.actionLabels(): Map<Int, String?> {
        val info = AccessibilityNodeInfo.obtain()
        onInitializeAccessibilityNodeInfo(info)
        return info.actionList.associate { it.id to it.label?.toString() }
    }

    private fun View.actionIds(): List<Int> {
        val info = AccessibilityNodeInfo.obtain()
        onInitializeAccessibilityNodeInfo(info)
        return info.actionList.map { it.id }
    }
}
