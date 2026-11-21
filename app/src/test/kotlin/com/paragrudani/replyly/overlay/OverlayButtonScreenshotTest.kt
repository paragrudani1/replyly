package com.paragrudani.replyly.overlay

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import com.paragrudani.replyly.testing.ScreenshotTest
import com.paragrudani.replyly.testing.ShotVariant
import com.paragrudani.replyly.testing.captureShot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The floating button over a WhatsApp-like chat background, in light and dark mode. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotTest.DEVICE)
class OverlayButtonScreenshotTest {
    @Test
    fun button() {
        for (variant in ShotVariant.LIGHT_DARK) {
            RuntimeEnvironment.setQualifiers(if (variant.dark) "+night" else "+notnight")
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val density = activity.resources.displayMetrics.density
            val size = (OverlayComponent.BUTTON_DP * density).toInt()
            val frame =
                FrameLayout(activity).apply {
                    // WhatsApp's chat wallpaper colours, light and dark.
                    setBackgroundColor(if (variant.dark) Color.rgb(0x0B, 0x14, 0x1A) else Color.rgb(0xEF, 0xEA, 0xE2))
                    val button = createOverlayButton(activity, onTap = {}).apply { alpha = 1f }
                    addView(button, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                }
            activity.setContentView(frame, FrameLayout.LayoutParams((96 * density).toInt(), (96 * density).toInt()))
            frame.captureShot("overlay/button", variant)
        }
    }
}
