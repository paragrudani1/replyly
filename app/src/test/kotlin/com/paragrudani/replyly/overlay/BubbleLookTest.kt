package com.paragrudani.replyly.overlay

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.settings.ReplylySettings
import org.junit.Test
import kotlin.math.ceil
import kotlin.random.Random

class BubbleLookTest {
    @Test
    fun `each size step maps to a circle of 44 dp times the step and a window of circle plus 12 dp`() {
        val dp =
            ReplylySettings.BUBBLE_SIZES.map { size ->
                val look = BubbleLook(size = size)
                look.circleDp to look.windowDp
            }

        listOf(37.4f, 44f, 50.6f, 57.2f).zip(dp.map { it.first }).forEach { (expected, actual) ->
            assertThat(actual).isWithin(1e-4f).of(expected)
        }
        // About 50 / 56 / 63 / 70 dp: the default (1.0x) keeps today's 56 dp window.
        assertThat(dp.map { Math.round(it.second) }).containsExactly(49, 56, 63, 69).inOrder()
        assertThat(BubbleLook().windowDp).isEqualTo(OverlayComponent.TOUCH_DP.toFloat())
        assertThat(BubbleLook().circleDp).isEqualTo(OverlayComponent.BUTTON_DP.toFloat())
    }

    @Test
    fun `pixel sizes at xhdpi and the S23's 3x`() {
        assertThat(BubbleLook(size = 0.85f).windowPx(2f)).isEqualTo(99)
        assertThat(BubbleLook(size = 1.0f).windowPx(2f)).isEqualTo(112)
        assertThat(BubbleLook(size = 1.0f).circlePx(2f)).isEqualTo(88)
        assertThat(BubbleLook(size = 1.3f).windowPx(3f)).isEqualTo(208)
    }

    @Test
    fun `an unknown stored size reads as 1,0 and a near step as that step`() {
        assertThat(ReplylySettings.bubbleSizeStep(2.0f)).isEqualTo(1.0f)
        assertThat(ReplylySettings.bubbleSizeStep(0.7f)).isEqualTo(1.0f)
        assertThat(ReplylySettings.bubbleSizeStep(Float.NaN)).isEqualTo(1.0f)
        assertThat(ReplylySettings.bubbleSizeStep(1.1500001f)).isEqualTo(1.15f)
        ReplylySettings.BUBBLE_SIZES.forEach { assertThat(ReplylySettings.bubbleSizeStep(it)).isEqualTo(it) }
    }

    @Test
    fun `opacity is clamped to 40-100 and rounded to a step of 20`() {
        val cases =
            mapOf(
                -5 to 40,
                0 to 40,
                39 to 40,
                40 to 40,
                49 to 40,
                50 to 60,
                65 to 60,
                70 to 80,
                89 to 80,
                90 to 100,
                100 to 100,
                250 to 100,
                Int.MIN_VALUE to 40,
                Int.MAX_VALUE to 100,
            )
        cases.forEach { (stored, step) ->
            assertWithMessage("stored $stored").that(ReplylySettings.bubbleOpacityStep(stored)).isEqualTo(step)
        }
    }

    @Test
    fun `alpha is the opacity, and busy dims relative to it`() {
        assertThat(BubbleLook().alpha(busy = false)).isEqualTo(1f)
        assertThat(BubbleLook().alpha(busy = true)).isEqualTo(OverlayButtonView.BUSY_ALPHA)
        assertThat(BubbleLook(opacityPercent = 40).alpha(busy = false)).isWithin(1e-6f).of(0.4f)
        assertThat(BubbleLook(opacityPercent = 40).alpha(busy = true)).isWithin(1e-6f).of(0.4f * 0.45f)
    }

    @Test
    fun `from normalises the stored settings`() {
        val look =
            BubbleLook.from(
                ReplylySettings(showBubble = false, bubbleSize = 3f, bubbleOpacity = 55, shrinkWhenIdle = true),
            )

        assertThat(look).isEqualTo(BubbleLook(show = false, size = 1.0f, opacityPercent = 60, shrinkWhenIdle = true))
        assertThat(BubbleLook.from(ReplylySettings())).isEqualTo(BubbleLook())
    }

    /**
     * Seeded property test over every size × opacity (stored, also out of range) × busy × shrunk ×
     * density. Invariants:
     * 1. the window is never below 48 dp;
     * 2. the circle (and the dot) fits in the window, with the 12 dp shadow room;
     * 3. alpha is in [0.4 × busy factor, 1], and busy is never brighter than idle;
     * 4. the look holds a valid step whatever was stored.
     */
    @Test
    fun `property - window at least 48 dp, circle fits, alpha in range`() {
        val random = Random(55)
        val densities = listOf(0.75f, 1f, 1.5f, 2f, 2.625f, 3f, 3.5f, 4f) + List(40) { 0.5f + random.nextFloat() * 4f }
        val storedSizes = ReplylySettings.BUBBLE_SIZES + List(40) { random.nextFloat() * 3f }
        val storedOpacities = (-20..140 step 5).toList() + List(40) { random.nextInt() }
        for (density in densities) {
            for (storedSize in storedSizes) {
                for (storedOpacity in storedOpacities) {
                    val look = BubbleLook.from(ReplylySettings(bubbleSize = storedSize, bubbleOpacity = storedOpacity))
                    val case = "density $density size $storedSize opacity $storedOpacity"
                    assertWithMessage(case).that(look.size).isIn(ReplylySettings.BUBBLE_SIZES)
                    assertWithMessage(case).that(look.opacityPercent).isIn(ReplylySettings.BUBBLE_OPACITIES)

                    val window = look.windowPx(density)
                    assertWithMessage(case).that(window).isAtLeast(ceil(48 * density).toInt())
                    for (shrunk in listOf(false, true)) {
                        val circle = look.circlePx(density) * (if (shrunk) BubbleLook.DOT_SCALE else 1f)
                        assertWithMessage(case).that(circle).isAtMost(window.toFloat())
                        assertWithMessage(case).that(window - circle).isAtLeast(12 * density - 1f)
                    }
                    val idle = look.alpha(busy = false)
                    val busy = look.alpha(busy = true)
                    for (alpha in listOf(idle, busy)) {
                        assertWithMessage(case).that(alpha).isAtLeast(0.4f * BubbleLook.BUSY_FACTOR - 1e-6f)
                        assertWithMessage(case).that(alpha).isAtMost(1f)
                    }
                    assertWithMessage(case).that(busy).isLessThan(idle)
                }
            }
        }
    }
}
