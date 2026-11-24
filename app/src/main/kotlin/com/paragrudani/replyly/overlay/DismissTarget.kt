package com.paragrudani.replyly.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.paragrudani.replyly.R
import com.paragrudani.replyly.ui.theme.MotionDurations
import com.paragrudani.replyly.ui.theme.MotionEasing
import com.paragrudani.replyly.ui.theme.ReplylyMotion
import kotlin.math.roundToInt

/**
 * The drag-to-snooze target (#56): a circle with the snooze mark, drawn with theme roles
 * (`inverseSurface` / `inverseOnSurface`; `primary` / `onPrimary` while the finger is on it,
 * slightly larger). Not interactive: it only shows where to drop the button.
 */
@SuppressLint("ViewConstructor")
class DismissTargetView(
    context: Context,
    private val radiusPx: Float,
    private val motion: ReplylyMotion = ReplylyMotion.forContext(context),
) : View(context) {
    private val scheme = overlayButtonScheme(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_snooze)!!.mutate()
    private val iconPx = (ICON_DP * resources.displayMetrics.density).roundToInt()

    var active: Boolean = false
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        applyColors()
    }

    /** The finger is on the target ([inside]) or not; grows a little while it is. */
    fun setActive(inside: Boolean) {
        if (inside == active) return
        active = inside
        applyColors()
        val scale = if (inside) ACTIVE_SCALE else 1f
        animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(motion.viewDuration(MotionDurations.SHORT))
            .setInterpolator(MotionEasing.viewInterpolator(MotionEasing.Standard))
            .start()
        invalidate()
    }

    /** Fades in (instant with "Remove animations"). */
    fun fadeIn() {
        alpha = 0f
        animate()
            .alpha(1f)
            .setDuration(motion.viewDuration(MotionDurations.MEDIUM))
            .setInterpolator(MotionEasing.viewInterpolator(MotionEasing.Enter))
            .start()
    }

    private fun applyColors() {
        fill.color = (if (active) scheme.primary else scheme.inverseSurface).toArgb()
        icon.setTint((if (active) scheme.onPrimary else scheme.inverseOnSurface).toArgb())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radiusPx, fill)
        val half = iconPx / 2
        icon.setBounds(cx.roundToInt() - half, cy.roundToInt() - half, cx.roundToInt() + half, cy.roundToInt() + half)
        icon.draw(canvas)
    }

    companion object {
        const val ACTIVE_SCALE = 1.15f
        const val ICON_DP = 28

        /** The view's side: the circle at its active size plus a little room. */
        fun sizePx(radiusPx: Float): Int = (radiusPx * 2 * ACTIVE_SCALE).roundToInt() + 2
    }
}

/**
 * The target's own accessibility overlay window (#56): not touchable and not focusable, so it
 * never takes a touch or the keyboard from the chat; created when a drag reveals it and removed
 * on release, on hide and on screen off. Main thread only.
 */
class DismissTargetWindow(
    private val context: Context,
    private val motion: () -> ReplylyMotion = { ReplylyMotion.forContext(context) },
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: DismissTargetView? = null

    val isShown: Boolean get() = view != null

    /** Shows the circle centred on [center] (screen px) with [radiusPx]. */
    @SuppressLint("RtlHardcoded")
    fun show(
        center: DismissTargetRule.Center,
        radiusPx: Float,
    ) {
        if (view != null) return
        val target = DismissTargetView(context, radiusPx, motion())
        val size = DismissTargetView.sizePx(radiusPx)
        val params =
            WindowManager
                .LayoutParams(
                    size,
                    size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    x = (center.x - size / 2f).roundToInt()
                    y = (center.y - size / 2f).roundToInt()
                    fitInsetsTypes = 0
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
        windowManager.addView(target, params)
        view = target
        target.fadeIn()
    }

    fun setActive(inside: Boolean) {
        view?.setActive(inside)
    }

    fun remove() {
        val target = view ?: return
        view = null
        target.animate().cancel()
        try {
            windowManager.removeViewImmediate(target)
        } catch (e: IllegalArgumentException) {
            // Already removed (the service is going away).
        }
    }

    /** The shown circle (tests). */
    @VisibleForTesting
    internal val targetView: DismissTargetView? get() = view
}
