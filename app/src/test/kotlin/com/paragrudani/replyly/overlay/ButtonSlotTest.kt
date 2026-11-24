package com.paragrudani.replyly.overlay

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.screen.Bounds
import org.junit.Test

/** S23-like 1080x2340 px; button window 156 px, margin 12 px, status bar 110 px. */
class ButtonSlotTest {
    private val placement = ButtonPlacement(buttonSizePx = 156, marginPx = 12, maxInputLiftPx = 600)
    private val keyboard = Bounds(0, 1440, 1080, 2340)
    private val input = Bounds(30, 1300, 900, 1420)

    private fun slot(
        lift: Int? = null,
        keyboard: Bounds = this.keyboard,
        side: ButtonSide = ButtonSide.RIGHT,
    ) = placement.slot(keyboard, input, 1080, 2340, topInsetPx = 110, savedLiftPx = lift, savedSide = side)

    @Test
    fun `never dragged - the default spot, with a range from below the status bar to above the input bar`() {
        val slot = slot()

        assertThat(slot.position).isEqualTo(placement.place(keyboard, input, 1080, 2340))
        assertThat(slot.minY).isEqualTo(122)
        // The input bar rests on the keyboard: never lower than the default spot (send button).
        assertThat(slot.maxY).isEqualTo(1300 - 12 - 156)
        assertThat(slot.maxY).isEqualTo(slot.position.y)
    }

    @Test
    fun `without an input bar on the keyboard the range goes down to just above the keyboard`() {
        val slot = placement.slot(keyboard, input = null, 1080, 2340, topInsetPx = 110)

        assertThat(slot.maxY).isEqualTo(1440 - 12 - 156)
    }

    @Test
    fun `a spot saved before the input bar grew is clamped above the taller bar`() {
        val saved = placement.liftOf(1132, keyboard.top) // Just above the one-line bar.
        val threeLines = input.copy(top = 1100)

        val slot = placement.slot(keyboard, threeLines, 1080, 2340, topInsetPx = 110, savedLiftPx = saved)

        assertThat(slot.position.y).isEqualTo(1100 - 12 - 156)
    }

    @Test
    fun `a remembered lift puts the button that far above the keyboard`() {
        assertThat(slot(lift = 400).position.y).isEqualTo(1440 - 156 - 400)
    }

    @Test
    fun `the lift follows the keyboard when it gets taller`() {
        val taller = Bounds(0, 1200, 1080, 2340)

        assertThat(slot(lift = 400, keyboard = taller).position.y).isEqualTo(1200 - 156 - 400)
    }

    @Test
    fun `a lift too big for a tall keyboard stops under the status bar`() {
        assertThat(slot(lift = 5000).position.y).isEqualTo(122)
    }

    @Test
    fun `a negative lift stops just above the input bar`() {
        assertThat(slot(lift = -300).position.y).isEqualTo(1300 - 12 - 156)
    }

    @Test
    fun `liftOf and slot round-trip`() {
        val y = 800
        assertThat(slot(lift = placement.liftOf(y, keyboard.top)).position.y).isEqualTo(y)
    }

    @Test
    fun `no room at all - the range collapses to one spot under the status bar`() {
        val slot = slot(keyboard = Bounds(0, 200, 1080, 2340))

        assertThat(slot.minY).isEqualTo(slot.maxY)
        assertThat(slot.position.y).isEqualTo(122)
    }

    @Test
    fun `clampY keeps a drag inside the range`() {
        val slot = slot()
        assertThat(slot.clampY(-10)).isEqualTo(slot.minY)
        assertThat(slot.clampY(9999)).isEqualTo(slot.maxY)
        assertThat(slot.clampY(700)).isEqualTo(700)
    }

    // Sides (#54) --------------------------------------------------------------------------------

    @Test
    fun `the right edge is the keyboard's right minus margin, the left edge its left plus margin`() {
        val slot = slot()

        assertThat(slot.rightX).isEqualTo(1080 - 12 - 156)
        assertThat(slot.leftX).isEqualTo(12)
        assertThat(slot.minX).isEqualTo(12)
        assertThat(slot.maxX).isEqualTo(1080 - 12 - 156)
    }

    @Test
    fun `never dragged and an old lift-only spot both sit on the right`() {
        assertThat(slot().side).isEqualTo(ButtonSide.RIGHT)
        assertThat(slot().position.x).isEqualTo(912)
        assertThat(slot(lift = 400).side).isEqualTo(ButtonSide.RIGHT)
        assertThat(slot(lift = 400).position.x).isEqualTo(912)
    }

    @Test
    fun `a saved left side puts the button on the left edge at the same height`() {
        val right = slot(lift = 400)
        val left = slot(lift = 400, side = ButtonSide.LEFT)

        assertThat(left.side).isEqualTo(ButtonSide.LEFT)
        assertThat(left.position).isEqualTo(ButtonPosition(12, right.position.y))
        assertThat(left.minY).isEqualTo(right.minY)
        assertThat(left.maxY).isEqualTo(right.maxY)
    }

    @Test
    fun `on the left the button never goes below the default spot either`() {
        val slot = slot(lift = -300, side = ButtonSide.LEFT)

        assertThat(slot.position.y).isEqualTo(1300 - 12 - 156)
        assertThat(slot.maxY).isEqualTo(1300 - 12 - 156)
    }

    @Test
    fun `snap picks the edge nearer the button's centre, a tie goes right`() {
        val slot = slot()

        // Keyboard centre 540: button centre 461 + 78 = 539 is left of it, 462 + 78 = 540 a tie.
        assertThat(slot.snap(461)).isEqualTo(ButtonSide.LEFT)
        assertThat(slot.snap(462)).isEqualTo(ButtonSide.RIGHT)
        assertThat(slot.snap(12)).isEqualTo(ButtonSide.LEFT)
        assertThat(slot.snap(912)).isEqualTo(ButtonSide.RIGHT)
    }

    @Test
    fun `onSide moves the slot to that edge and keeps the height`() {
        val slot = slot(lift = 400)

        val left = slot.onSide(ButtonSide.LEFT)

        assertThat(left.side).isEqualTo(ButtonSide.LEFT)
        assertThat(left.position).isEqualTo(ButtonPosition(12, slot.position.y))
        assertThat(left.onSide(ButtonSide.RIGHT)).isEqualTo(slot)
    }

    @Test
    fun `clampX keeps a drag fully on screen`() {
        val slot = slot()

        assertThat(slot.clampX(-500)).isEqualTo(12)
        assertThat(slot.clampX(5000)).isEqualTo(912)
        assertThat(slot.clampX(400)).isEqualTo(400)
    }

    @Test
    fun `a floating keyboard - both edges hug its rectangle`() {
        val floating = Bounds(200, 1200, 880, 1800)

        val slot = placement.slot(floating, input = null, 1080, 2340, 110, savedSide = ButtonSide.LEFT)

        assertThat(slot.leftX).isEqualTo(212)
        assertThat(slot.rightX).isEqualTo(880 - 12 - 156)
        assertThat(slot.position.x).isEqualTo(212)
        // The drag range is the screen, not the keyboard.
        assertThat(slot.minX).isEqualTo(12)
        assertThat(slot.maxX).isEqualTo(912)
        // Keyboard centre 540: same tie rule.
        assertThat(slot.snap(462)).isEqualTo(ButtonSide.RIGHT)
        assertThat(slot.snap(461)).isEqualTo(ButtonSide.LEFT)
    }

    @Test
    fun `a floating keyboard hanging off the left edge keeps the left spot on screen`() {
        val slot = placement.slot(Bounds(-200, 1200, 500, 1800), null, 1080, 2340, 110, savedSide = ButtonSide.LEFT)

        assertThat(slot.position.x).isEqualTo(12)
        assertThat(slot.rightX).isEqualTo(500 - 12 - 156)
    }

    @Test
    fun `a keyboard narrower than the button puts both edges on one spot`() {
        val slot = placement.slot(Bounds(400, 1200, 500, 1800), null, 1080, 2340, 110, savedSide = ButtonSide.LEFT)

        assertThat(slot.leftX).isEqualTo(slot.rightX)
        assertThat(slot.position.x).isEqualTo(slot.rightX)
    }

    @Test
    fun `a slot built without sides has both edges at its x`() {
        val slot = ButtonSlot(ButtonPosition(900, 1000), minY = 400, maxY = 1200)

        assertThat(slot.side).isEqualTo(ButtonSide.RIGHT)
        assertThat(slot.leftX).isEqualTo(900)
        assertThat(slot.rightX).isEqualTo(900)
        assertThat(slot.snap(0)).isEqualTo(ButtonSide.RIGHT)
    }
}
