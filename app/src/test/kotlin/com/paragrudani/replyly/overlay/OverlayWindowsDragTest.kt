package com.paragrudani.replyly.overlay

import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.R
import com.paragrudani.replyly.panel.PanelRequest
import com.paragrudani.replyly.panel.ReplyPanel
import com.paragrudani.replyly.ui.theme.MotionDurations
import com.paragrudani.replyly.ui.theme.ReplylyMotion
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * The button window's 2D drag, edge snap and TalkBack edge moves (#54). Screen 1080 px wide,
 * button window 104 px: left edge 20, right edge 900 (a tie at window x 460, centre 512), drag
 * range 0..976.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class OverlayWindowsDragTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val moved = mutableListOf<Pair<Int, Int>>()
    private var taps = 0
    private var reduced = true
    private var time = SystemClock.uptimeMillis()

    private val noPanel =
        object : ReplyPanel {
            @Composable
            override fun Content(
                request: PanelRequest,
                onClose: () -> Unit,
            ) = Unit
        }

    private val windows =
        OverlayWindows(
            context = context,
            panel = noPanel,
            buttonSizePx = 104,
            onButtonTap = { taps++ },
            onPanelDismiss = {},
            onButtonMoved = { x, y -> moved += x to y },
            motion = { ReplylyMotion(reduced) },
        )

    private val slot =
        ButtonSlot(
            ButtonPosition(900, 1000),
            minY = 400,
            maxY = 1200,
            leftX = 20,
            rightX = 900,
            minX = 0,
            maxX = 976,
        )

    @After
    fun tearDown() {
        ShadowChoreographer.setPaused(false)
        windows.hideButton(immediate = true)
    }

    private fun show(slot: ButtonSlot = this.slot): OverlayButtonView {
        windows.showButton(slot)
        return checkNotNull(windows.buttonView)
    }

    private val View.windowX get() = (layoutParams as WindowManager.LayoutParams).x
    private val View.windowY get() = (layoutParams as WindowManager.LayoutParams).y

    private fun View.touch(
        action: Int,
        x: Float,
        y: Float,
    ) {
        time += 16
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        dispatchTouchEvent(event)
        event.recycle()
    }

    /** Drags by ([dx], [dy]) from the window's resting spot and lets go. */
    private fun View.drag(
        dx: Int,
        dy: Int,
    ) {
        touch(MotionEvent.ACTION_DOWN, 1000f, 1100f)
        touch(MotionEvent.ACTION_MOVE, 1000f + dx / 2, 1100f + dy / 2)
        touch(MotionEvent.ACTION_MOVE, 1000f + dx, 1100f + dy)
        touch(MotionEvent.ACTION_UP, 1000f + dx, 1100f + dy)
    }

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun `the window follows the finger in x and y, fully on screen`() {
        val button = show()

        button.touch(MotionEvent.ACTION_DOWN, 1000f, 1100f)
        button.touch(MotionEvent.ACTION_MOVE, 700f, 900f)
        assertThat(button.windowX).isEqualTo(600)
        assertThat(button.windowY).isEqualTo(800)

        button.touch(MotionEvent.ACTION_MOVE, -2000f, 5000f)
        assertThat(button.windowX).isEqualTo(0)
        assertThat(button.windowY).isEqualTo(1200)

        button.touch(MotionEvent.ACTION_MOVE, 5000f, -5000f)
        assertThat(button.windowX).isEqualTo(976)
        assertThat(button.windowY).isEqualTo(400)
        button.touch(MotionEvent.ACTION_CANCEL, 5000f, -5000f)
    }

    @Test
    fun `released left of the centre it lands on the left edge at once with reduced motion`() {
        val button = show()

        button.drag(dx = -500, dy = -200)

        assertThat(button.windowX).isEqualTo(20)
        assertThat(button.windowY).isEqualTo(800)
        assertThat(moved).containsExactly(20 to 800)
        assertThat(taps).isEqualTo(0)
        assertThat(shadowOf(button).lastHapticFeedbackPerformed()).isEqualTo(HapticFeedbackConstants.SEGMENT_TICK)
    }

    @Test
    fun `released right of the centre it goes back to the right edge`() {
        val button = show()

        button.drag(dx = -300, dy = 0)

        assertThat(button.windowX).isEqualTo(900)
        assertThat(moved).containsExactly(900 to 1000)
    }

    @Test
    fun `an exact tie goes right`() {
        val button = show()

        // 900 - 440 = 460: the window's centre 512 is on the keyboard's centre (20 + 900) / 2 + 52.
        button.drag(dx = -440, dy = 0)
        assertThat(button.windowX).isEqualTo(900)

        button.drag(dx = -441, dy = 0)
        assertThat(button.windowX).isEqualTo(20)
    }

    @Test
    fun `a drag released in the middle never taps, never dims and never stays in the middle`() {
        val button = show()

        button.drag(dx = -450, dy = -300)

        assertThat(button.windowX).isAnyOf(20, 900)
        assertThat(taps).isEqualTo(0)
        assertThat(button.busy).isFalse()
        assertThat(button.mark.alpha).isEqualTo(1f)
    }

    @Test
    fun `with animations on it glides to the edge and ticks once it lands`() {
        reduced = false
        // Frames only when the clock moves, 16 ms apart, so the glide can be watched mid-way.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val button = show()
        button.touch(MotionEvent.ACTION_DOWN, 1000f, 1100f)
        button.touch(MotionEvent.ACTION_MOVE, 400f, 1100f)
        button.touch(MotionEvent.ACTION_UP, 400f, 1100f)

        // Saved at once (where it will land); the window is still on its way.
        assertThat(moved).containsExactly(20 to 1000)
        assertThat(button.windowX).isEqualTo(300)
        // The drag start ticked; mark the haptic log so that only a later tick shows.
        assertThat(button.lastHaptic()).isEqualTo(HapticFeedbackConstants.SEGMENT_TICK)
        button.markHaptics()

        frames(8) // 128 ms of 250
        assertThat(button.windowX).isIn(21..299)
        assertThat(button.lastHaptic()).isEqualTo(MARK)
        frames(7) // 240 ms
        assertThat(button.lastHaptic()).isEqualTo(MARK)

        frames(2) // 272 ms: landed
        assertThat(button.windowX).isEqualTo(20)
        assertThat(button.lastHaptic()).isEqualTo(HapticFeedbackConstants.SEGMENT_TICK)

        // Exactly one landing tick.
        button.markHaptics()
        frames(20)
        assertThat(button.lastHaptic()).isEqualTo(MARK)
    }

    /** [count] Choreographer frames, 16 ms apart (with the paused Choreographer). */
    private fun frames(count: Int) = repeat(count) { idle(16) }

    @Test
    fun `TalkBack offers only the edge that would move it, moves there and saves it`() {
        val button = show()
        assertThat(button.actionIds()).contains(R.id.overlay_action_move_left_edge)
        assertThat(button.actionIds()).doesNotContain(R.id.overlay_action_move_right_edge)
        assertThat(button.actionIds()).containsAtLeast(R.id.overlay_action_move_up, R.id.overlay_action_move_down)

        assertThat(button.performAccessibilityAction(R.id.overlay_action_move_left_edge, null)).isTrue()

        assertThat(button.windowX).isEqualTo(20)
        assertThat(moved).containsExactly(20 to 1000)
        assertThat(button.actionIds()).contains(R.id.overlay_action_move_right_edge)
        assertThat(button.actionIds()).doesNotContain(R.id.overlay_action_move_left_edge)
        assertThat(taps).isEqualTo(0)

        button.performAccessibilityAction(R.id.overlay_action_move_right_edge, null)
        assertThat(button.windowX).isEqualTo(900)
        assertThat(moved.last()).isEqualTo(900 to 1000)
    }

    @Test
    fun `a TalkBack edge move glides like a drag and ticks only when it lands`() {
        reduced = false
        val button = show()

        button.performAccessibilityAction(R.id.overlay_action_move_left_edge, null)

        assertThat(shadowOf(button).lastHapticFeedbackPerformed()).isNotEqualTo(HapticFeedbackConstants.SEGMENT_TICK)
        // While it glides, the action already counts as done: no second "Move to left edge".
        assertThat(button.actionIds()).doesNotContain(R.id.overlay_action_move_left_edge)

        idle(MotionDurations.LONG + 100L)

        assertThat(button.windowX).isEqualTo(20)
        assertThat(shadowOf(button).lastHapticFeedbackPerformed()).isEqualTo(HapticFeedbackConstants.SEGMENT_TICK)
    }

    @Test
    fun `Move up and down keep the side`() {
        val button = show(slot.onSide(ButtonSide.LEFT))

        button.performAccessibilityAction(R.id.overlay_action_move_up, null)

        assertThat(button.windowX).isEqualTo(20)
        assertThat(moved).containsExactly(20 to 896)
    }

    @Test
    fun `Move up during a glide lands on the glide's edge at once`() {
        reduced = false
        val button = show()
        button.performAccessibilityAction(R.id.overlay_action_move_left_edge, null)

        button.performAccessibilityAction(R.id.overlay_action_move_up, null)
        idle(MotionDurations.LONG + 100L)

        assertThat(button.windowX).isEqualTo(20)
        assertThat(button.windowY).isEqualTo(896)
        assertThat(moved).containsExactly(20 to 1000, 20 to 896).inOrder()
    }

    @Test
    fun `a slot with one spot for both edges offers no edge action`() {
        val button = show(ButtonSlot(ButtonPosition(500, 1000), minY = 400, maxY = 1200))

        assertThat(
            button.actionIds(),
        ).containsNoneOf(R.id.overlay_action_move_left_edge, R.id.overlay_action_move_right_edge)
    }

    @Test
    fun `hiding while it glides removes the window and stops the glide`() {
        reduced = false
        val button = show()
        button.drag(dx = -700, dy = 0)

        windows.hideButton(immediate = true)
        idle(MotionDurations.LONG + 100L)

        assertThat(windows.buttonView).isNull()
        assertThat(button.parent).isNull()
        assertThat(button.windowX).isNotEqualTo(20)
    }

    @Test
    fun `a drag cut off by an immediate hide (screen off) leaves no window and saves nothing`() {
        val button = show()
        button.touch(MotionEvent.ACTION_DOWN, 1000f, 1100f)
        button.touch(MotionEvent.ACTION_MOVE, 400f, 900f)

        windows.hideButton(immediate = true)
        button.touch(MotionEvent.ACTION_MOVE, 300f, 900f)
        button.touch(MotionEvent.ACTION_UP, 300f, 900f)

        assertThat(button.parent).isNull()
        assertThat(windows.buttonView).isNull()
        assertThat(windows.leavingView).isNull()
        assertThat(moved).isEmpty()
        assertThat(taps).isEqualTo(0)
    }

    @Test
    fun `a new slot while dragging never pulls the button from under the finger`() {
        val button = show()
        button.touch(MotionEvent.ACTION_DOWN, 1000f, 1100f)
        button.touch(MotionEvent.ACTION_MOVE, 300f, 900f)

        windows.showButton(slot.copy(position = ButtonPosition(900, 1100), maxY = 1100))

        assertThat(button.windowX).isEqualTo(200)
        assertThat(button.windowY).isEqualTo(800)
        button.touch(MotionEvent.ACTION_UP, 300f, 900f)
        assertThat(moved).containsExactly(20 to 800)
    }

    @Test
    fun `a new slot while dragging that no longer holds the spot moves it into range at once`() {
        val button = show()
        button.touch(MotionEvent.ACTION_DOWN, 1000f, 1100f)
        button.touch(MotionEvent.ACTION_MOVE, 300f, 1000f)
        assertThat(button.windowY).isEqualTo(900)

        // A narrow floating keyboard higher up: the held spot is right of and below the new range.
        windows.showButton(
            ButtonSlot(
                ButtonPosition(130, 600),
                minY = 200,
                maxY = 600,
                leftX = 20,
                rightX = 130,
                minX = 0,
                maxX = 150,
            ),
        )

        assertThat(button.windowX).isEqualTo(150)
        assertThat(button.windowY).isEqualTo(600)
        // Later moves still follow the finger, clamped to the new range.
        button.touch(MotionEvent.ACTION_MOVE, 950f, 750f)
        assertThat(button.windowX).isEqualTo(150)
        assertThat(button.windowY).isEqualTo(600)
        button.touch(MotionEvent.ACTION_MOVE, 1000f, 500f)
        assertThat(button.windowX).isEqualTo(150)
        assertThat(button.windowY).isEqualTo(400)
        button.touch(MotionEvent.ACTION_UP, 1000f, 500f)
        assertThat(moved).containsExactly(130 to 400)
    }

    private fun View.lastHaptic() = shadowOf(this).lastHapticFeedbackPerformed()

    /** A constant no Replyly haptic uses, so [lastHaptic] tells "nothing since" apart. */
    private fun View.markHaptics() {
        performHapticFeedback(MARK)
    }

    private companion object {
        const val MARK = HapticFeedbackConstants.KEYBOARD_RELEASE
    }

    private fun View.actionIds(): List<Int> {
        val info = AccessibilityNodeInfo.obtain()
        onInitializeAccessibilityNodeInfo(info)
        return info.actionList.map { it.id }
    }
}
