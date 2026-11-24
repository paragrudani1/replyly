package com.paragrudani.replyly.overlay

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.panel.PanelRequest
import com.paragrudani.replyly.panel.ReplyPanel
import com.paragrudani.replyly.ui.theme.ReplylyMotion
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The drag-to-snooze target in the button window's drag (#56). Same layout as
 * `OverlayWindowsDragTest`: button window 104 px, edges 20 and 900, lowest spot y 1200. At xhdpi
 * the target's centre is (512, 1252), its hit radius 96 px, the early reveal 144 px down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class OverlayWindowsSnoozeTargetTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val moved = mutableListOf<Pair<Int, Int>>()
    private var taps = 0
    private var drops = 0
    private val start = SystemClock.uptimeMillis()

    private val noPanel =
        object : ReplyPanel {
            @Composable
            override fun Content(
                request: PanelRequest,
                onClose: () -> Unit,
            ) = Unit
        }

    private val target = DismissTargetWindow(context) { ReplylyMotion(reduced = true) }

    private val windows =
        OverlayWindows(
            context = context,
            panel = noPanel,
            buttonSizePx = 104,
            onButtonTap = { taps++ },
            onPanelDismiss = {},
            onButtonMoved = { x, y -> moved += x to y },
            motion = { ReplylyMotion(reduced = true) },
            dismissTarget = target,
            onDroppedOnTarget = { drops++ },
        )

    private val slot =
        ButtonSlot(ButtonPosition(900, 1000), minY = 400, maxY = 1200, leftX = 20, rightX = 900, minX = 0, maxX = 976)

    @After
    fun tearDown() = windows.hideButton(immediate = true)

    private fun show(): OverlayButtonView {
        windows.showButton(slot)
        return checkNotNull(windows.buttonView)
    }

    private val View.windowX get() = (layoutParams as WindowManager.LayoutParams).x
    private val View.windowY get() = (layoutParams as WindowManager.LayoutParams).y

    /** A touch at screen ([x], [y]), [atMs] after the finger went down. */
    private fun View.touch(
        action: Int,
        x: Float,
        y: Float,
        atMs: Long,
    ) {
        val event = MotionEvent.obtain(start, start + atMs, action, x, y, 0)
        dispatchTouchEvent(event)
        event.recycle()
    }

    /** Down on the button, then a slow drag (past the slop at 20 ms) onto the target at 400 ms. */
    private fun View.slowDragOntoTarget() {
        touch(MotionEvent.ACTION_DOWN, 1000f, 1100f, 0)
        touch(MotionEvent.ACTION_MOVE, 960f, 1100f, 20)
        touch(MotionEvent.ACTION_MOVE, 800f, 1150f, 200)
        assertThat(target.isShown).isFalse()
        touch(MotionEvent.ACTION_MOVE, 520f, 1250f, 420)
    }

    @Test
    fun `a slow drag shows the target, entering it ticks and lights it`() {
        val button = show()
        button.slowDragOntoTarget()

        assertThat(target.isShown).isTrue()
        assertThat(target.targetView!!.active).isTrue()
        assertThat(shadowOf(button).lastHapticFeedbackPerformed()).isEqualTo(HapticFeedbackConstants.SEGMENT_TICK)
        button.touch(MotionEvent.ACTION_CANCEL, 520f, 1250f, 430)
    }

    @Test
    fun `the target window is centred between the edges at the lowest spot and never takes touches or focus`() {
        val button = show()
        button.slowDragOntoTarget()

        val view = target.targetView!!
        val params = view.layoutParams as WindowManager.LayoutParams
        assertThat(params.x + params.width / 2f).isWithin(1f).of(512f)
        assertThat(params.y + params.height / 2f).isWithin(1f).of(1252f)
        assertThat(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isNotEqualTo(0)
        assertThat(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
        assertThat(params.type).isEqualTo(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        assertThat(view.importantForAccessibility).isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
        button.touch(MotionEvent.ACTION_CANCEL, 520f, 1250f, 430)
    }

    @Test
    fun `release on the target puts the button back, saves nothing and reports the drop`() {
        val button = show()
        button.slowDragOntoTarget()
        button.touch(MotionEvent.ACTION_UP, 520f, 1250f, 440)

        assertThat(drops).isEqualTo(1)
        assertThat(moved).isEmpty()
        assertThat(taps).isEqualTo(0)
        assertThat(button.windowX to button.windowY).isEqualTo(900 to 1000)
        assertThat(target.isShown).isFalse()
    }

    @Test
    fun `moving away before release cancels the snooze and snaps as usual`() {
        val button = show()
        button.slowDragOntoTarget()
        button.touch(MotionEvent.ACTION_MOVE, 300f, 700f, 500)
        assertThat(target.targetView!!.active).isFalse()
        button.touch(MotionEvent.ACTION_UP, 300f, 700f, 520)

        assertThat(drops).isEqualTo(0)
        assertThat(moved).hasSize(1)
        assertThat(target.isShown).isFalse()
    }

    @Test
    fun `a quick flick onto the target never shows it and snaps as usual`() {
        val button = show()
        button.touch(MotionEvent.ACTION_DOWN, 1000f, 1100f, 0)
        button.touch(MotionEvent.ACTION_MOVE, 700f, 1200f, 40)
        button.touch(MotionEvent.ACTION_MOVE, 520f, 1250f, 90)
        assertThat(target.isShown).isFalse()
        button.touch(MotionEvent.ACTION_UP, 520f, 1250f, 110)

        assertThat(drops).isEqualTo(0)
        assertThat(moved).hasSize(1)
    }

    @Test
    fun `72 dp down after 120 ms shows it early`() {
        val button = show()
        button.touch(MotionEvent.ACTION_DOWN, 1000f, 900f, 0)
        // Past the touch slop: the drag (and the rule's clock) starts here, at y 1000.
        button.touch(MotionEvent.ACTION_MOVE, 1000f, 1000f, 10)
        button.touch(MotionEvent.ACTION_MOVE, 1000f, 1143f, 140)
        assertThat(target.isShown).isFalse()
        button.touch(MotionEvent.ACTION_MOVE, 1000f, 1144f, 150)
        assertThat(target.isShown).isTrue()
        button.touch(MotionEvent.ACTION_CANCEL, 1000f, 1144f, 160)
    }

    @Test
    fun `a gesture the system cancels on the target is not a drop`() {
        val button = show()
        button.slowDragOntoTarget()
        button.touch(MotionEvent.ACTION_CANCEL, 520f, 1250f, 440)

        assertThat(drops).isEqualTo(0)
        assertThat(target.isShown).isFalse()
    }

    @Test
    fun `hiding the button removes the target`() {
        val button = show()
        button.slowDragOntoTarget()
        windows.hideButton(immediate = true)
        assertThat(target.isShown).isFalse()
    }

    @Test
    fun `without a target window every drag snaps`() {
        val plain =
            OverlayWindows(
                context = context,
                panel = noPanel,
                buttonSizePx = 104,
                onButtonTap = {},
                onPanelDismiss = {},
                onButtonMoved = { x, y -> moved += x to y },
                motion = { ReplylyMotion(reduced = true) },
            )
        plain.showButton(slot)
        val button = checkNotNull(plain.buttonView)
        button.slowDragOntoTarget()
        button.touch(MotionEvent.ACTION_UP, 520f, 1250f, 440)
        assertThat(moved).hasSize(1)
        plain.hideButton(immediate = true)
    }
}
