package com.paragrudani.replyly.ui.theme

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.spring as springSpec
import androidx.compose.animation.core.tween as tweenSpec

/** Durations in ms. Short for feedback, medium for small things appearing, long for sheets. */
object MotionDurations {
    /** Press feedback, highlight on tap. */
    const val SHORT = 100

    /** The floating button's scale and fade in/out (issue #17 asks for ~150 ms). */
    const val MEDIUM = 150

    /** Cards entering, the panel's content changing. */
    const val LONG = 250

    /** Page transitions (onboarding). */
    const val EXTRA_LONG = 400

    /** Delay between staggered items (the 3 suggestion cards). */
    const val STAGGER = 40
}

/** Easing curves (Material 3 "emphasized" family). */
object MotionEasing {
    /** Things that move or resize on screen. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Things entering: fast start, gentle stop. */
    val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Things leaving: gentle start, fast finish. */
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/** Spring defaults: a soft settle with a hint of bounce, never wobbly. */
object MotionSprings {
    const val DAMPING = 0.85f
    const val STIFFNESS = Spring.StiffnessMediumLow
}

/**
 * Motion that honours the system "Remove animations" setting. Get it with [ReplylyTheme.motion]
 * and build every animation spec through it: when [reduced] is true each spec snaps to its end
 * value, staggers are 0, and loops (shimmer) should not start at all.
 *
 * ```
 * val motion = ReplylyTheme.motion
 * val alpha by animateFloatAsState(if (shown) 1f else 0f, motion.tween(MotionDurations.MEDIUM))
 * if (!motion.reduced) { /* start the shimmer */ }
 * ```
 */
@Immutable
class ReplylyMotion(
    val reduced: Boolean,
) {
    fun <T> tween(
        durationMillis: Int = MotionDurations.LONG,
        easing: Easing = MotionEasing.Standard,
        delayMillis: Int = 0,
    ): FiniteAnimationSpec<T> = if (reduced) snap() else tweenSpec(durationMillis, delayMillis, easing)

    fun <T> spring(
        dampingRatio: Float = MotionSprings.DAMPING,
        stiffness: Float = MotionSprings.STIFFNESS,
        visibilityThreshold: T? = null,
    ): FiniteAnimationSpec<T> = if (reduced) snap() else springSpec(dampingRatio, stiffness, visibilityThreshold)

    /** Start delay of the item at [index] in a staggered entrance. */
    fun staggerDelay(index: Int): Int = if (reduced) 0 else index * MotionDurations.STAGGER

    /** Duration for View animations (`animate().setDuration(...)`), 0 when reduced. */
    fun viewDuration(durationMillis: Int): Long = if (reduced) 0L else durationMillis.toLong()

    override fun equals(other: Any?): Boolean = other is ReplylyMotion && other.reduced == reduced

    override fun hashCode(): Int = reduced.hashCode()

    override fun toString(): String = "ReplylyMotion(reduced=$reduced)"

    companion object {
        /** For View code outside Compose (the floating button). Reads the setting now. */
        fun forContext(context: Context): ReplylyMotion = ReplylyMotion(isReducedMotion(context))
    }
}

/**
 * True when the user turned animations off: Developer options "Animator duration scale: off" or
 * Accessibility "Remove animations" (both set `ANIMATOR_DURATION_SCALE` to 0).
 */
fun isReducedMotion(context: Context): Boolean {
    val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return isReducedMotion(scale) || !ValueAnimator.areAnimatorsEnabled()
}

internal fun isReducedMotion(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

/** [isReducedMotion] that recomposes when the user changes the setting. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember(context) { mutableStateOf(isReducedMotion(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    reduced = isReducedMotion(context)
                }
            }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
