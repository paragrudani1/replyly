package com.paragrudani.replyly.overlay

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.ui.theme.ReplylyMotion
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The target's window draws above the button's (#56), so while the finger is on it the target
 * must let the dragged button show through (review r1 N4).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DismissTargetViewTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val view = DismissTargetView(activity, radiusPx = 100f, motion = ReplylyMotion(reduced = true))

    @Test
    fun `idle - an opaque circle with the snooze mark`() {
        assertThat(view.fillAlpha).isEqualTo(255)
        assertThat(view.drawsIcon).isTrue()
    }

    @Test
    fun `active - a see-through tint and no mark over the button`() {
        view.setActive(true)
        assertThat(view.fillAlpha).isAtMost(255 * 3 / 10)
        assertThat(view.drawsIcon).isFalse()

        view.setActive(false)
        assertThat(view.fillAlpha).isEqualTo(255)
        assertThat(view.drawsIcon).isTrue()
    }
}
