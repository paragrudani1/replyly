package com.paragrudani.replyly.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import com.paragrudani.replyly.R
import com.paragrudani.replyly.ui.theme.Elevation
import com.paragrudani.replyly.ui.theme.MotionDurations
import com.paragrudani.replyly.ui.theme.ReplylyMotion
import com.paragrudani.replyly.ui.theme.dynamicColorSetting
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
 * (so still one LLM call per tap). A touch that moves past the touch slop is a drag and never a
 * tap ([DragTracker]). TalkBack users get "Move up" / "Move down" actions instead of dragging.
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

        fun onDragEnd() {}

        /** Accessibility action: move one step up (`-1`) or down (`+1`). */
        fun onMoveStep(direction: Int) {}
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

    fun applyColors(scheme: ColorScheme) {
        mark.backgroundTintList = ColorStateList.valueOf(scheme.primary.toArgb())
        mark.imageTintList = ColorStateList.valueOf(scheme.onPrimary.toArgb())
    }

    // Touch -------------------------------------------------------------------------------------

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
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                listener.onDragStart()
                listener.onDrag(step.dy)
            }

            is DragStep.Drag -> {
                listener.onDrag(step.dy)
            }

            is DragStep.DragEnd -> {
                listener.onDrag(step.dy)
                setDragging(false)
                listener.onDragEnd()
            }

            DragStep.Tap -> {
                setPressedLook(false)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                R.id.overlay_action_move_up,
                context.getString(R.string.overlay_button_move_up),
            ),
        )
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                R.id.overlay_action_move_down,
                context.getString(R.string.overlay_button_move_down),
            ),
        )
    }

    override fun performAccessibilityAction(
        action: Int,
        arguments: android.os.Bundle?,
    ): Boolean =
        when (action) {
            R.id.overlay_action_move_up -> {
                listener.onMoveStep(-1)
                true
            }

            R.id.overlay_action_move_down -> {
                listener.onMoveStep(1)
                true
            }

            else -> {
                super.performAccessibilityAction(action, arguments)
            }
        }

    // Looks -------------------------------------------------------------------------------------

    /** Dimmed while a tap is being read (it can take a few seconds). */
    fun setBusy(busy: Boolean) {
        this.busy = busy
        updateMark(MotionDurations.SHORT)
    }

    fun setPressedLook(pressed: Boolean) {
        pressedLook = pressed
        updateMark(MotionDurations.SHORT)
    }

    fun setDragging(dragging: Boolean) {
        this.dragging = dragging
        updateMark(MotionDurations.SHORT)
    }

    private fun updateMark(durationMillis: Int) {
        val scale =
            when {
                dragging -> DRAG_SCALE
                pressedLook -> PRESSED_SCALE
                else -> 1f
            }
        mark.animateTo(
            scale,
            if (busy) BUSY_ALPHA else 1f,
            motion.viewDuration(durationMillis),
            MotionInterpolators.standard,
        )
    }

    /** Scale and fade in from [HIDDEN_SCALE] (~150 ms; instant with "Remove animations"). */
    fun animateIn() {
        scaleX = HIDDEN_SCALE
        scaleY = HIDDEN_SCALE
        alpha = 0f
        animateTo(1f, 1f, motion.viewDuration(MotionDurations.MEDIUM), MotionInterpolators.enter)
    }

    /** Scale and fade out, then [onEnd] (remove the window). */
    fun animateOut(onEnd: () -> Unit) {
        isEnabled = false
        animateTo(HIDDEN_SCALE, 0f, motion.viewDuration(MotionDurations.MEDIUM), MotionInterpolators.exit, onEnd)
    }

    companion object {
        const val BUSY_ALPHA = 0.45f
        const val PRESSED_SCALE = 0.9f
        const val DRAG_SCALE = 1.04f
        const val HIDDEN_SCALE = 0.6f
    }
}

/** The M3 curves of [com.paragrudani.replyly.ui.theme.MotionEasing], for View animations. */
private object MotionInterpolators {
    val standard get() = PathInterpolator(0.2f, 0f, 0f, 1f)
    val enter get() = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val exit get() = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
}

private fun View.animateTo(
    scale: Float,
    alpha: Float,
    durationMillis: Long,
    interpolator: PathInterpolator,
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
    dynamicColor: Boolean = context.dynamicColorSetting(),
): ColorScheme {
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return replylyColorScheme(
        context,
        darkTheme = night == Configuration.UI_MODE_NIGHT_YES,
        dynamicColor = dynamicColor,
    )
}
