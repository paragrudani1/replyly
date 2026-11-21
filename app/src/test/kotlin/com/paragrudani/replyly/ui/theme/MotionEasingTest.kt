package com.paragrudani.replyly.ui.theme

import androidx.compose.animation.core.LinearEasing
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The View interpolators follow the same curves as the Compose easings. */
@RunWith(RobolectricTestRunner::class)
class MotionEasingTest {
    @Test
    fun `view interpolators match the compose curves`() {
        for (easing in listOf(MotionEasing.Standard, MotionEasing.Enter, MotionEasing.Exit)) {
            val interpolator = MotionEasing.viewInterpolator(easing)
            for (step in 0..20) {
                val fraction = step / 20f
                assertThat(interpolator.getInterpolation(fraction))
                    .isWithin(0.01f)
                    .of(easing.transform(fraction))
            }
        }
    }

    @Test
    fun `only the motion curves have a view interpolator`() {
        assertThrows(IllegalArgumentException::class.java) { MotionEasing.viewInterpolator(LinearEasing) }
    }
}
