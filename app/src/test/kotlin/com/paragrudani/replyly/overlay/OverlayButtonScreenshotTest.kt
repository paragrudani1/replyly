package com.paragrudani.replyly.overlay

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.paragrudani.replyly.screen.Bounds
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
 * The floating button over a WhatsApp-like chat background, light and dark: idle, pressed,
 * busy (a tap is being read), and dragged up above the keyboard (lifted look while dragging).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotTest.DEVICE)
class OverlayButtonScreenshotTest {
    private val noop =
        object : OverlayButtonView.Listener {
            override fun onTap() = Unit
        }

    @Test
    fun idle() = shoot("overlay/button") {}

    @Test
    fun pressed() = shoot("overlay/button_pressed") { it.setPressedLook(true) }

    @Test
    fun busy() = shoot("overlay/button_busy") { it.setBusy(true) }

    /** A 200 x 320 dp corner of the screen: chat, input bar, keyboard; the button dragged up. */
    @Test
    fun dragged() {
        for (variant in ShotVariant.LIGHT_DARK) {
            val activity = activity(variant)
            val density = activity.resources.displayMetrics.density

            fun dp(value: Int) = (value * density).roundToInt()
            val width = dp(200)
            val height = dp(320)
            val keyboard = Bounds(0, dp(200), width, height)
            val input = Bounds(dp(8), dp(152), width - dp(8), dp(196))
            val placement =
                ButtonPlacement(
                    dp(OverlayComponent.TOUCH_DP),
                    dp(OverlayComponent.MARGIN_DP),
                    dp(OverlayComponent.MAX_LIFT_DP),
                )
            val slot = placement.slot(keyboard, input, width, height, topInsetPx = dp(24), savedLiftPx = dp(72))
            val frame =
                FrameLayout(activity).apply {
                    setBackgroundColor(chatBackground(variant))
                    addView(block(activity, inputBar(variant)), rect(input))
                    addView(block(activity, keyboardColor(variant)), rect(keyboard))
                    val button =
                        OverlayButtonView(
                            activity,
                            noop,
                            ReplylyMotion(reduced = true),
                        ).apply { setDragging(true) }
                    val size = dp(OverlayComponent.TOUCH_DP)
                    addView(
                        button,
                        FrameLayout.LayoutParams(size, size).apply {
                            leftMargin = slot.position.x
                            topMargin = slot.position.y
                        },
                    )
                }
            activity.setContentView(frame, FrameLayout.LayoutParams(width, height))
            frame.captureShot("overlay/button_dragged", variant)
        }
    }

    private fun shoot(
        name: String,
        state: (OverlayButtonView) -> Unit,
    ) {
        for (variant in ShotVariant.LIGHT_DARK) {
            val activity = activity(variant)
            val density = activity.resources.displayMetrics.density
            val size = (OverlayComponent.TOUCH_DP * density).roundToInt()
            val frame =
                FrameLayout(activity).apply {
                    setBackgroundColor(chatBackground(variant))
                    val button = OverlayButtonView(activity, noop, ReplylyMotion(reduced = true)).also(state)
                    addView(button, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                }
            activity.setContentView(frame, FrameLayout.LayoutParams((96 * density).toInt(), (96 * density).toInt()))
            frame.captureShot(name, variant)
        }
    }

    private fun activity(variant: ShotVariant): Activity {
        RuntimeEnvironment.setQualifiers(if (variant.dark) "+night" else "+notnight")
        return Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    private fun block(
        activity: Activity,
        color: Int,
    ) = View(activity).apply { setBackgroundColor(color) }

    private fun rect(bounds: Bounds) =
        FrameLayout.LayoutParams(bounds.width, bounds.height).apply {
            leftMargin = bounds.left
            topMargin = bounds.top
        }

    // WhatsApp's chat wallpaper, input bar and Gboard colours, light and dark.
    private fun chatBackground(variant: ShotVariant) =
        if (variant.dark) Color.rgb(0x0B, 0x14, 0x1A) else Color.rgb(0xEF, 0xEA, 0xE2)

    private fun inputBar(variant: ShotVariant) = if (variant.dark) Color.rgb(0x1F, 0x2C, 0x34) else Color.WHITE

    private fun keyboardColor(variant: ShotVariant) =
        if (variant.dark) Color.rgb(0x20, 0x21, 0x24) else Color.rgb(0xE8, 0xEA, 0xED)
}
