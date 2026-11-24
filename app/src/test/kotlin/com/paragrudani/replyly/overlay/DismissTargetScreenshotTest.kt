package com.paragrudani.replyly.overlay

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import com.paragrudani.replyly.testing.ScreenshotTest
import com.paragrudani.replyly.testing.ShotVariant
import com.paragrudani.replyly.testing.captureShot
import com.paragrudani.replyly.ui.theme.ReplylyMotion
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The drag-to-snooze target (#56) over the bottom of a WhatsApp-like chat, just above the
 * keyboard: idle, and active (the finger is on it, the dragged button under the target's window,
 * which must show through it; review r1 N4).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotTest.DEVICE)
class DismissTargetScreenshotTest {
    @Test
    fun idle() = shoot("overlay/dismiss_target", active = false)

    @Test
    fun active() = shoot("overlay/dismiss_target_active", active = true)

    private fun shoot(
        name: String,
        active: Boolean,
    ) {
        for (variant in ShotVariant.LIGHT_DARK) {
            RuntimeEnvironment.setQualifiers(if (variant.dark) "+night" else "+notnight")
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val density = activity.resources.displayMetrics.density

            fun dp(value: Int) = (value * density).roundToInt()
            val width = dp(200)
            val height = dp(160)
            val rule = DismissTargetRule(density)
            val size = DismissTargetView.sizePx(rule.radiusPx)
            val keyboardTop = dp(110)
            val frame =
                FrameLayout(activity).apply {
                    setBackgroundColor(if (variant.dark) Color.rgb(0x0B, 0x14, 0x1A) else Color.rgb(0xEF, 0xEA, 0xE2))
                    addView(
                        View(activity).apply {
                            setBackgroundColor(
                                if (variant.dark) Color.rgb(0x20, 0x21, 0x24) else Color.rgb(0xE8, 0xEA, 0xED),
                            )
                        },
                        FrameLayout.LayoutParams(width, height - keyboardTop).apply { topMargin = keyboardTop },
                    )
                    if (active) {
                        // The button window is below the target window: drawn first here too.
                        val button =
                            OverlayButtonView(
                                activity,
                                object : OverlayButtonView.Listener {
                                    override fun onTap() = Unit
                                },
                                ReplylyMotion(reduced = true),
                            )
                        val buttonSize = dp(OverlayComponent.TOUCH_DP)
                        addView(
                            button,
                            FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
                                leftMargin = (width - buttonSize) / 2
                                topMargin = keyboardTop - dp(8) - size / 2 - buttonSize / 2
                            },
                        )
                    }
                    val target = DismissTargetView(activity, rule.radiusPx, ReplylyMotion(reduced = true))
                    target.setActive(active)
                    addView(
                        target,
                        FrameLayout.LayoutParams(size, size).apply {
                            leftMargin = (width - size) / 2
                            topMargin = keyboardTop - dp(8) - size
                        },
                    )
                }
            activity.setContentView(frame, FrameLayout.LayoutParams(width, height))
            frame.captureShot(name, variant)
        }
    }
}
