package com.paragrudani.replyly.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.paragrudani.replyly.R
import com.paragrudani.replyly.panel.PanelRequest
import com.paragrudani.replyly.panel.PanelSheet
import com.paragrudani.replyly.panel.PanelSheetState
import com.paragrudani.replyly.panel.ReplyPanel
import com.paragrudani.replyly.ui.theme.HapticEvent
import com.paragrudani.replyly.ui.theme.MotionDurations
import com.paragrudani.replyly.ui.theme.MotionEasing
import com.paragrudani.replyly.ui.theme.ReplylyMotion
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import com.paragrudani.replyly.ui.theme.performReplylyHaptic

/**
 * The two overlay windows (TYPE_ACCESSIBILITY_OVERLAY, no extra permission needed):
 *
 * - the button: small, never focusable, so the chat's input keeps focus and the keyboard stays.
 *   It scales and fades in and out. The user can drag it anywhere within its [ButtonSlot]; on
 *   release it glides to the nearer left or right edge at the drop height ([ButtonSlot.snap],
 *   `MotionDurations.LONG`, instant with "Remove animations") with one `DragSnap` tick on landing.
 *   TalkBack's "Move to left/right edge" glides the same way;
 * - the panel: full screen and focusable, so its instruction field can take the keyboard. When it
 *   is removed, window focus returns to the chat app, whose input field never lost view focus.
 *
 * Both use screen coordinates (no inset fitting), the same space as `getBoundsInScreen`.
 * [context] is the accessibility service. Main thread only.
 */
class OverlayWindows(
    private val context: Context,
    private val panel: ReplyPanel,
    /** The button window's side (the touch area); [applyLook] changes it for the bubble size (#55). */
    private var buttonSizePx: Int,
    private val onButtonTap: () -> Unit,
    private val onPanelDismiss: () -> Unit,
    /**
     * The user dragged (or moved with TalkBack) the button: it rests at window ([x], [y]), `x`
     * being the edge it glides to. Called once per move, at release, before the glide ends.
     */
    private val onButtonMoved: (x: Int, y: Int) -> Unit = { _, _ -> },
    /** How far one TalkBack "Move up/down" goes. */
    private val moveStepPx: Int = buttonSizePx,
    /** Read at each snap, so "Remove animations" applies without a restart. */
    private val motion: () -> ReplylyMotion = { ReplylyMotion.forContext(context) },
    /** The drag-to-snooze target (#56); `null`: no target, every drag snaps to an edge. */
    private val dismissTarget: DismissTargetWindow? = null,
    /** The button was let go on the target (#56): it is back at its pre-drag spot, nothing saved. */
    private val onDroppedOnTarget: () -> Unit = {},
    /** The snooze card's window (#56), opened by the controller after a drop on the target. */
    private val snoozePicker: SnoozePickerWindow? = null,
) : OverlayUi {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var button: OverlayButtonView? = null
    private var slot: ButtonSlot? = null

    /** The button playing its hide animation; removed at its end or as soon as a new one shows. */
    private var leaving: OverlayButtonView? = null
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragging = false

    /** Where the button rested before this drag (#56): a drop on the target puts it back there. */
    private var preDragX = 0

    /** This drag against the snooze target (#56), from its first finger position; `null` without one. */
    private var targetDrag: DismissTargetDrag? = null
    private var targetDragStartMs = 0L
    private var dragAborted = false

    /** The glide to an edge, while it runs, and the x it ends at. */
    private var snap: ValueAnimator? = null
    private var snapTargetX: Int? = null
    private var buttonBusy = false
    private var panelWindow: PanelWindow? = null

    private class PanelWindow(
        val root: View,
        val compose: ComposeView,
        val owners: OverlayViewOwners,
        val sheet: PanelSheetState,
    )

    private val buttonListener =
        object : OverlayButtonView.Listener {
            override fun onTap() = onButtonTap()

            override fun onDragStart() {
                val params = buttonParams() ?: return
                preDragX = restingX(params)
                cancelSnap()
                dragging = true
                dragStartX = params.x
                dragStartY = params.y
                endTargetDrag()
                dragAborted = false
            }

            override fun onFingerAt(
                rawX: Float,
                rawY: Float,
                timeMs: Long,
            ) {
                if (!dragging) return
                trackTarget(rawX, rawY, timeMs)
            }

            override fun onDragAborted() {
                dragAborted = true
            }

            override fun onDrag(
                dx: Int,
                dy: Int,
            ) {
                if (!dragging) return
                moveButtonTo(dragStartX + dx, dragStartY + dy)
            }

            override fun onDragEnd() {
                if (!dragging) return
                dragging = false
                val onTarget = targetDrag?.releasedInside() == true && !dragAborted
                endTargetDrag()
                if (onTarget) {
                    // Back where it was, nothing remembered; the controller opens the snooze card.
                    moveButtonTo(preDragX, dragStartY)
                    onDroppedOnTarget()
                    return
                }
                val params = buttonParams() ?: return
                val target = slot?.let { it.edgeX(it.snap(params.x)) } ?: params.x
                val y = params.y
                glideTo(target)
                onButtonMoved(target, y)
            }

            override fun onMoveStep(direction: Int) {
                val params = buttonParams() ?: return
                // A running glide jumps to its end: the step keeps the side.
                val x = restingX(params)
                cancelSnap()
                val y = moveButtonTo(x, params.y + direction * moveStepPx)?.second ?: return
                onButtonMoved(x, y)
            }

            override fun canMoveStep(direction: Int): Boolean {
                val params = buttonParams() ?: return false
                val target = params.y + direction * moveStepPx
                return (slot?.clampY(target) ?: target) != params.y
            }

            override fun onMoveToEdge(side: ButtonSide) {
                val params = buttonParams() ?: return
                val target = slot?.edgeX(side) ?: return
                glideTo(target)
                onButtonMoved(target, params.y)
            }

            override fun canMoveToEdge(side: ButtonSide): Boolean {
                val params = buttonParams() ?: return false
                val edge = slot?.edgeX(side) ?: return false
                return edge != restingX(params)
            }
        }

    /** Where the button rests: the end of a running glide, else where it is. */
    private fun restingX(params: WindowManager.LayoutParams): Int = snapTargetX ?: params.x

    /**
     * The drag-to-snooze target (#56): the first finger position starts the rule's clock; the
     * circle shows once [DismissTargetRule] reveals it, centred between the two edges at the
     * height of the button's lowest allowed spot, and the finger entering it ticks once.
     */
    private fun trackTarget(
        rawX: Float,
        rawY: Float,
        timeMs: Long,
    ) {
        val window = dismissTarget ?: return
        val current = slot ?: return
        val drag =
            targetDrag ?: run {
                val rule = DismissTargetRule(context.resources.displayMetrics.density)
                val center =
                    DismissTargetRule.Center(
                        x = (current.leftX + current.rightX + buttonSizePx) / 2f,
                        y = current.maxY + buttonSizePx / 2f,
                    )
                targetCenter = center
                targetRule = rule
                targetDragStartMs = timeMs
                DismissTargetDrag(rule, rawY, center).also { targetDrag = it }
            }
        val update = drag.onMove(timeMs - targetDragStartMs, rawX, rawY)
        if (update.revealed && !window.isShown) window.show(targetCenter ?: return, targetRule?.radiusPx ?: return)
        window.setActive(update.inside)
        if (update.entered) button?.performReplylyHaptic(HapticEvent.DragSnap)
    }

    private var targetCenter: DismissTargetRule.Center? = null
    private var targetRule: DismissTargetRule? = null

    /** The drag is over (release, hide, screen off): the target goes. */
    private fun endTargetDrag() {
        targetDrag = null
        targetCenter = null
        targetRule = null
        dismissTarget?.remove()
    }

    override fun openSnoozePicker(keyboardTop: Int) {
        snoozePicker?.open(keyboardTop)
    }

    override fun closeSnoozePicker() {
        snoozePicker?.close()
    }

    override fun showButton(slot: ButtonSlot) {
        this.slot = slot
        val existing = button
        if (existing != null) {
            val params = existing.layoutParams as WindowManager.LayoutParams
            if (dragging) {
                // Stays under the finger where the new range allows; otherwise to the nearest spot
                // in it at once (a rotation or a taller keyboard must not leave it over the input
                // bar until the next move). Later moves clamp to the new range too.
                moveButtonTo(params.x, params.y)
                return
            }
            cancelSnap()
            params.x = slot.position.x
            params.y = slot.position.y
            windowManager.updateViewLayout(existing, params)
            return
        }
        leaving?.let(::remove)
        leaving = null
        val view =
            OverlayButtonView(context, buttonListener).apply {
                applyLook(this@OverlayWindows.look)
                setBusy(buttonBusy)
            }
        val params =
            baseParams(buttonSizePx, buttonSizePx).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                x = slot.position.x
                y = slot.position.y
            }
        windowManager.addView(view, params)
        button = view
        view.animateIn()
    }

    override fun setButtonBusy(busy: Boolean) {
        buttonBusy = busy
        button?.setBusy(busy)
    }

    /** The bubble look for the next button; kept so a new window draws it in its first frame. */
    private var look = BubbleLook()

    /**
     * Settings → Bubble changed (#55): keeps [look] for the next button and applies it to a shown
     * one at once: its window takes [BubbleLook.windowPx] (the placement follows on the next
     * refresh), its circle the new size and alpha, its idle timer the shrink setting.
     */
    fun applyLook(look: BubbleLook) {
        this.look = look
        buttonSizePx = look.windowPx(context.resources.displayMetrics.density)
        val view = button ?: return
        view.applyLook(look)
        val params = buttonParams() ?: return
        if (params.width == buttonSizePx && params.height == buttonSizePx) return
        params.width = buttonSizePx
        params.height = buttonSizePx
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            // Not attached any more.
        }
    }

    override fun hideButton(immediate: Boolean) {
        cancelSnap()
        endTargetDrag()
        val view = button
        button = null
        slot = null
        dragging = false
        leaving?.let {
            it.animate().cancel()
            remove(it)
        }
        leaving = null
        if (view == null) return
        if (immediate) {
            view.animate().cancel()
            remove(view)
            return
        }
        // The fading window must not block touches meant for the chat app underneath.
        (view.layoutParams as? WindowManager.LayoutParams)?.let { params ->
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: IllegalArgumentException) {
                // Not attached any more; the fade below still ends in remove().
            }
        }
        leaving = view
        view.animateOut {
            if (leaving === view) leaving = null
            remove(view)
        }
    }

    /** The button window's view while shown (tests). */
    @VisibleForTesting
    internal val buttonView: OverlayButtonView? get() = button

    /** The button window playing its hide animation, if any (tests). */
    @VisibleForTesting
    internal val leavingView: OverlayButtonView? get() = leaving

    private fun buttonParams() = button?.layoutParams as? WindowManager.LayoutParams

    /**
     * Moves the button window to ([x], [y]) clamped to the slot (fully on screen, never below the
     * default spot); returns the clamped spot (`null`: no button).
     */
    private fun moveButtonTo(
        x: Int,
        y: Int,
    ): Pair<Int, Int>? {
        val view = button ?: return null
        val params = buttonParams() ?: return null
        val clampedX = slot?.clampX(x) ?: x
        val clampedY = slot?.clampY(y) ?: y
        if (params.x != clampedX || params.y != clampedY) {
            params.x = clampedX
            params.y = clampedY
            windowManager.updateViewLayout(view, params)
        }
        return clampedX to clampedY
    }

    /**
     * Glides the button window to [x] at its current height, then plays one `DragSnap` tick.
     * Instant (and the tick at once) with "Remove animations". Stopped by [cancelSnap]: a new
     * drag, a new slot or a hide; a stopped glide never ticks.
     */
    private fun glideTo(x: Int) {
        val view = button ?: return
        val params = buttonParams() ?: return
        cancelSnap()
        val duration = motion().viewDuration(MotionDurations.LONG)
        if (duration == 0L || params.x == x) {
            moveButtonTo(x, params.y)
            view.performReplylyHaptic(HapticEvent.DragSnap)
            return
        }
        snapTargetX = x
        snap =
            ValueAnimator.ofInt(params.x, x).apply {
                this.duration = duration
                interpolator = MotionEasing.viewInterpolator(MotionEasing.Standard)
                addUpdateListener {
                    if (button === view) moveButtonTo(it.animatedValue as Int, params.y)
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (snap === animation) {
                                snap = null
                                snapTargetX = null
                            }
                            if (!cancelled && button === view) view.performReplylyHaptic(HapticEvent.DragSnap)
                        }
                    },
                )
                start()
            }
    }

    private fun cancelSnap() {
        val running = snap ?: return
        snap = null
        snapTargetX = null
        running.cancel()
    }

    override fun openPanel(request: PanelRequest) {
        closePanel()
        val owners = OverlayViewOwners()
        lateinit var root: View
        lateinit var sheet: PanelSheetState
        // Every close (insert, Close, Open settings, Back, backdrop, swipe) slides the sheet down
        // first; the window goes when the sheet says it is closed. The fallback, posted from the
        // one place every close passes, removes it anyway if no frame runs (the slide takes 250 ms).
        sheet =
            PanelSheetState(onCloseRequested = {
                root.postDelayed({ if (panelWindow?.sheet === sheet) onPanelDismiss() }, CLOSE_FALLBACK_MS)
            })
        val requestClose = sheet::requestClose
        val compose =
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    ReplylyTheme {
                        PanelSheet(sheet, onClosed = onPanelDismiss) { panel.Content(request, requestClose) }
                    }
                }
            }
        root =
            BackKeyFrameLayout(context, onBack = requestClose).apply {
                addView(compose, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        owners.attach(root)
        val params =
            baseParams(MATCH, MATCH).apply {
                @Suppress("DEPRECATION") // Still honoured for overlay windows; insets do the rest.
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        windowManager.addView(root, params)
        panelWindow = PanelWindow(root, compose, owners, sheet)
    }

    /** The open panel's sheet (tests: the backdrop and swipe call its requestClose directly). */
    @VisibleForTesting
    internal val panelSheet: PanelSheetState? get() = panelWindow?.sheet

    /** The open panel's window root (tests). */
    @VisibleForTesting
    internal val panelRoot: View? get() = panelWindow?.root

    override fun closePanel() {
        val window = panelWindow ?: return
        panelWindow = null
        remove(window.root)
        window.compose.disposeComposition()
        window.owners.destroy()
    }

    override fun showNotice(notice: Notice) {
        val text =
            when (notice) {
                Notice.OFF_HERE -> R.string.overlay_notice_off_here
                Notice.NO_CHAT -> R.string.overlay_notice_no_chat
            }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("RtlHardcoded")
    private fun baseParams(
        width: Int,
        height: Int,
    ) = WindowManager
        .LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // LEFT, not START: x is a screen coordinate, also in right-to-left layouts.
            gravity = Gravity.TOP or Gravity.LEFT
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    private fun remove(view: View) {
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            // Already removed (the service is going away).
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /** Longer than the sheet's slide-down (250 ms), short enough not to feel stuck. */
        const val CLOSE_FALLBACK_MS = 1_000L
    }
}

/** Back closes the panel. */
@SuppressLint("ViewConstructor")
private class BackKeyFrameLayout(
    context: Context,
    private val onBack: () -> Unit,
) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
