package com.paragrudani.replyly.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import com.paragrudani.replyly.R
import com.paragrudani.replyly.ui.theme.Elevation
import com.paragrudani.replyly.ui.theme.HapticEvent
import com.paragrudani.replyly.ui.theme.MotionDurations
import com.paragrudani.replyly.ui.theme.MotionEasing
import com.paragrudani.replyly.ui.theme.ReplylyMotion
import com.paragrudani.replyly.ui.theme.dynamicColorSetting
import com.paragrudani.replyly.ui.theme.performReplylyHaptic
import com.paragrudani.replyly.ui.theme.replylyColorScheme
import kotlin.math.roundToInt

/**
 * The floating button: the Replyly mark (bubble + sparkle) on a round brand fill (`primary`
 * fill, `onPrimary` mark), so it follows dark mode and the dynamic-colour setting.
 *
 * The view is the whole button window: [OverlayComponent.TOUCH_DP] square (the touch area, at
 * least 48 dp), with the [OverlayComponent.BUTTON_DP] circle centred in it, which leaves room
 * for its shadow. A View, not Compose: the button window has no lifecycle owners and must stay cheap.
 *
 * Touch: a tap goes through [View.performClick] to [Listener.onTap], the one existing tap path
 * (so still one LLM call per tap). A touch that moves past the touch slop is a drag, in any
 * direction, and never a tap ([DragTracker]). TalkBack users get "Move up" / "Move down" and
 * "Move to left edge" / "Move to right edge" actions instead of dragging.
 */
@SuppressLint("ViewConstructor")
class OverlayButtonView(
    context: Context,
    private val listener: Listener,
    private val motion: ReplylyMotion = ReplylyMotion.forContext(context),
) : FrameLayout(context) {
    interface Listener {
        fun onTap()

        /** A drag started; the window should remember where it was. */
        fun onDragStart() {}

        /** [dy] px from where the drag started (raw, not yet clamped). */
        fun onDrag(dy: Int) {}

        /**
         * [dx] and [dy] px from where the drag started (raw, not yet clamped). The view calls
         * this one; by default it passes the vertical part on to [onDrag] (vertical-only listeners).
         */
        fun onDrag(
            dx: Int,
            dy: Int,
        ) = onDrag(dy)

        /** The finger went up (or the gesture was cancelled): the window snaps to the nearer edge. */
        fun onDragEnd() {}

        /**
         * Where the finger is during a drag (#56): screen px ([rawX], [rawY]) and the event's
         * uptime ([timeMs]). Sent before each [onDrag], after [onDragStart]; not for a cancelled
         * gesture (see [onDragAborted]).
         */
        fun onFingerAt(
            rawX: Float,
            rawY: Float,
            timeMs: Long,
        ) {}

        /** The system took the gesture away mid-drag (#56): this drag ends, but it was never "released". */
        fun onDragAborted() {}

        /** Accessibility action: move one step up (`-1`) or down (`+1`). */
        fun onMoveStep(direction: Int) {}

        /**
         * Whether a step in [direction] would move the button at all. The action that can't (the
         * button is at the top or bottom of its range) is not offered to TalkBack.
         */
        fun canMoveStep(direction: Int): Boolean = true

        /** Accessibility action: move to the [side] edge at the same height. */
        fun onMoveToEdge(side: ButtonSide) {}

        /**
         * Whether moving to [side] would move the button at all. Only that action is offered, so
         * a button on the right offers "Move to left edge" only. Default: never (no sides).
         */
        fun canMoveToEdge(side: ButtonSide): Boolean = false
    }

    private val density = resources.displayMetrics.density
    private val tracker = DragTracker(ViewConfiguration.get(context).scaledTouchSlop.toFloat())

    /** The visible circle. Pressed, busy and dragging change it; show/hide animate the whole view. */
    val mark: ImageView =
        ImageView(context).apply {
            setImageResource(R.drawable.overlay_button_icon)
            setBackgroundResource(R.drawable.overlay_button_background)
            scaleType = ImageView.ScaleType.CENTER
            elevation = Elevation.Sheet.value * density
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    var busy = false
        private set
    private var pressedLook = false
    private var dragging = false

    init {
        val size = (OverlayComponent.BUTTON_DP * density).roundToInt()
        addView(mark, LayoutParams(size, size, Gravity.CENTER))
        clipChildren = false
        clipToPadding = false
        contentDescription = context.getString(R.string.overlay_button_description)
        isClickable = true
        isFocusable = false
        setOnClickListener { listener.onTap() }
        applyColors(overlayButtonScheme(context))
    }

    /** Dark mode flipped (or another config change) while the button shows: recolour it. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyColors(overlayButtonScheme(context, newConfig))
    }

    fun applyColors(scheme: ColorScheme) {
        mark.backgroundTintList = ColorStateList.valueOf(scheme.primary.toArgb())
        mark.imageTintList = ColorStateList.valueOf(scheme.onPrimary.toArgb())
    }

    // Touch -------------------------------------------------------------------------------------

    /**
     * The whole window is excluded from the system Back gesture: the button rests 2 dp from a
     * screen edge, and dragging it inwards has exactly the Back-swipe shape. The window is at most
     * ~70 dp, well under the 200 dp per-edge exclusion budget.
     */
    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) systemGestureExclusionRects = listOf(Rect(0, 0, right - left, bottom - top))
    }

    @SuppressLint("ClickableViewAccessibility") // Taps call performClick(); TalkBack clicks too.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return true // Leaving: swallow, never tap.
        val step =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> tracker.down(event.rawX, event.rawY)
                MotionEvent.ACTION_MOVE -> tracker.move(event.rawX, event.rawY)
                MotionEvent.ACTION_UP -> tracker.up(event.rawX, event.rawY)
                MotionEvent.ACTION_CANCEL -> tracker.cancel()
                else -> DragStep.Ignore
            }
        when (step) {
            DragStep.Press -> {
                setPressedLook(true)
            }

            is DragStep.DragStart -> {
                setPressedLook(false)
                setDragging(true)
                performReplylyHaptic(HapticEvent.DragSnap)
                listener.onDragStart()
                listener.onFingerAt(event.rawX, event.rawY, event.eventTime)
                listener.onDrag(step.dx, step.dy)
            }

            is DragStep.Drag -> {
                listener.onFingerAt(event.rawX, event.rawY, event.eventTime)
                listener.onDrag(step.dx, step.dy)
            }

            is DragStep.DragEnd -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    listener.onDragAborted()
                } else {
                    listener.onFingerAt(event.rawX, event.rawY, event.eventTime)
                }
                listener.onDrag(step.dx, step.dy)
                // A flick (DOWN, then UP past the slop, no MOVE) ends here straight from the press.
                setPressedLook(false)
                setDragging(false)
                listener.onDragEnd()
            }

            DragStep.Tap -> {
                setPressedLook(false)
                performReplylyHaptic(HapticEvent.Tap)
                performClick()
            }

            DragStep.Cancel -> {
                setPressedLook(false)
            }

            DragStep.Ignore -> {}
        }
        return true
    }

    // Accessibility -----------------------------------------------------------------------------

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
        if (listener.canMoveStep(-1)) {
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    R.id.overlay_action_move_up,
                    context.getString(R.string.overlay_button_move_up),
                ),
            )
        }
        if (listener.canMoveStep(1)) {
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    R.id.overlay_action_move_down,
                    context.getString(R.string.overlay_button_move_down),
                ),
            )
        }
        for ((side, id, label) in EDGE_ACTIONS) {
            if (listener.canMoveToEdge(side)) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(id, context.getString(label)))
            }
        }
    }

    override fun performAccessibilityAction(
        action: Int,
        arguments: android.os.Bundle?,
    ): Boolean =
        when (action) {
            R.id.overlay_action_move_up -> {
                moveStep(-1)
            }

            R.id.overlay_action_move_down -> {
                moveStep(1)
            }

            R.id.overlay_action_move_left_edge -> {
                moveToEdge(ButtonSide.LEFT)
            }

            R.id.overlay_action_move_right_edge -> {
                moveToEdge(ButtonSide.RIGHT)
            }

            else -> {
                super.performAccessibilityAction(action, arguments)
            }
        }

    private fun moveStep(direction: Int): Boolean {
        if (!listener.canMoveStep(direction)) return false
        listener.onMoveStep(direction)
        return true
    }

    /** Never a tap: only moves the window (and remembers the side). */
    private fun moveToEdge(side: ButtonSide): Boolean {
        if (!listener.canMoveToEdge(side)) return false
        listener.onMoveToEdge(side)
        return true
    }

    // Looks -------------------------------------------------------------------------------------

    /** The bubble settings (#55) this button draws with; [applyLook] changes it. */
    var look = BubbleLook()
        private set

    /** "Shrink when idle" (#55): the dot after 5 s untouched; press and drag grow it back. */
    private val idleShrink =
        IdleShrink(
            schedule = { delayMillis, action ->
                val runnable = Runnable(action)
                postDelayed(runnable, delayMillis)
                ({ removeCallbacks(runnable) })
            },
            onShrunkChange = { updateMark(MotionDurations.MEDIUM) },
        )

    /** Shown as the small dot (tests). */
    val shrunk: Boolean get() = idleShrink.shrunk

    private val accessibilityManager: AccessibilityManager? = context.getSystemService(AccessibilityManager::class.java)
    private val touchExplorationListener =
        AccessibilityManager.TouchExplorationStateChangeListener { on -> idleShrink.setTouchExploration(on) }
    private var iconFade: ValueAnimator? = null

    /**
     * Size, opacity and "Shrink when idle" from Settings → Bubble. Before the window is attached
     * it applies at once (the first frame is already right); on a shown button the change is
     * short. The window itself is sized by [OverlayWindows].
     */
    fun applyLook(look: BubbleLook) {
        this.look = look
        val circle = look.circlePx(density)
        val params = mark.layoutParams as LayoutParams
        if (params.width != circle || params.height != circle) {
            params.width = circle
            params.height = circle
            mark.layoutParams = params
        }
        // The mark grows with the circle (24 dp at 1.0x, as before).
        val icon = (ICON_DP * look.size * density).roundToInt()
        val inset = ((circle - icon) / 2).coerceAtLeast(0)
        mark.scaleType = ImageView.ScaleType.FIT_CENTER
        mark.setPadding(inset, inset, inset, inset)
        // A see-through circle would show its own shadow as a dark disc (review pr58-r1 S2).
        mark.elevation = if (look.opacityPercent < 100) 0f else Elevation.Sheet.value * density
        idleShrink.setEnabled(look.shrinkWhenIdle)
        updateMark(if (isAttachedToWindow) MotionDurations.SHORT else 0)
    }

    /** Dimmed while a tap is being read (it can take a few seconds). */
    fun setBusy(busy: Boolean) {
        this.busy = busy
        idleShrink.setBusy(busy)
        updateMark(MotionDurations.SHORT)
    }

    fun setPressedLook(pressed: Boolean) {
        pressedLook = pressed
        idleShrink.setTouching(pressedLook || dragging)
        updateMark(MotionDurations.SHORT)
    }

    fun setDragging(dragging: Boolean) {
        this.dragging = dragging
        idleShrink.setTouching(pressedLook || dragging)
        updateMark(MotionDurations.SHORT)
    }

    private fun updateMark(durationMillis: Int) {
        val shrunk = idleShrink.shrunk
        val scale =
            when {
                dragging -> DRAG_SCALE
                pressedLook -> PRESSED_SCALE
                shrunk -> BubbleLook.DOT_SCALE
                else -> 1f
            }
        val duration = motion.viewDuration(durationMillis)
        mark.animateTo(scale, look.alpha(busy), duration, MotionEasing.viewInterpolator(MotionEasing.Standard))
        fadeIcon(if (shrunk && !pressedLook && !dragging) 0 else OPAQUE, duration)
    }

    /** The dot hides the mark; it fades with the circle's scale. */
    private fun fadeIcon(
        target: Int,
        durationMillis: Long,
    ) {
        iconFade?.cancel()
        iconFade = null
        if (durationMillis == 0L || mark.imageAlpha == target) {
            mark.imageAlpha = target
            return
        }
        iconFade =
            ValueAnimator.ofInt(mark.imageAlpha, target).apply {
                duration = durationMillis
                interpolator = MotionEasing.viewInterpolator(MotionEasing.Standard)
                addUpdateListener { mark.imageAlpha = it.animatedValue as Int }
                start()
            }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        accessibilityManager?.addTouchExplorationStateChangeListener(touchExplorationListener)
        idleShrink.setTouchExploration(accessibilityManager?.isTouchExplorationEnabled == true)
    }

    override fun onDetachedFromWindow() {
        accessibilityManager?.removeTouchExplorationStateChangeListener(touchExplorationListener)
        idleShrink.setShown(false)
        iconFade?.cancel()
        super.onDetachedFromWindow()
    }

    /** Scale and fade in from [HIDDEN_SCALE] (~150 ms; instant with "Remove animations"). Starts full size. */
    fun animateIn() {
        scaleX = HIDDEN_SCALE
        scaleY = HIDDEN_SCALE
        alpha = 0f
        idleShrink.setShown(true)
        animateTo(
            1f,
            1f,
            motion.viewDuration(MotionDurations.MEDIUM),
            MotionEasing.viewInterpolator(MotionEasing.Enter),
        )
    }

    /** Scale and fade out, then [onEnd] (remove the window). */
    fun animateOut(onEnd: () -> Unit) {
        isEnabled = false
        idleShrink.setShown(false)
        animateTo(
            HIDDEN_SCALE,
            0f,
            motion.viewDuration(MotionDurations.MEDIUM),
            MotionEasing.viewInterpolator(MotionEasing.Exit),
            onEnd,
        )
    }

    companion object {
        const val BUSY_ALPHA = 0.45f
        const val PRESSED_SCALE = 0.9f
        const val DRAG_SCALE = 1.04f
        const val HIDDEN_SCALE = 0.6f

        /** The mark's size in the 1.0x circle (`overlay_button_icon` is 24 dp). */
        const val ICON_DP = 24
        private const val OPAQUE = 255
    }
}

private fun View.animateTo(
    scale: Float,
    alpha: Float,
    durationMillis: Long,
    interpolator: Interpolator,
    onEnd: (() -> Unit)? = null,
) {
    animate().cancel()
    if (durationMillis == 0L) {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
        onEnd?.invoke()
        return
    }
    animate()
        .scaleX(scale)
        .scaleY(scale)
        .alpha(alpha)
        .setDuration(durationMillis)
        .setInterpolator(interpolator)
        .withEndAction { onEnd?.invoke() }
        .start()
}

/** The scheme for the button right now: system dark mode plus the user's colour setting. */
internal fun overlayButtonScheme(
    context: Context,
    configuration: Configuration = context.resources.configuration,
    dynamicColor: Boolean = context.dynamicColorSetting(),
): ColorScheme {
    val night = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return replylyColorScheme(
        context,
        darkTheme = night == Configuration.UI_MODE_NIGHT_YES,
        dynamicColor = dynamicColor,
    )
}

/** The TalkBack edge actions: side, action id, label. */
private val EDGE_ACTIONS =
    listOf(
        Triple(ButtonSide.LEFT, R.id.overlay_action_move_left_edge, R.string.overlay_button_move_left_edge),
        Triple(ButtonSide.RIGHT, R.id.overlay_action_move_right_edge, R.string.overlay_button_move_right_edge),
    )
