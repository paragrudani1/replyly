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
    ) = placement.slot(keyboard, input, 1080, 2340, topInsetPx = 110, savedLiftPx = lift)

    @Test
    fun `never dragged - the default spot, with a range from below the status bar to above the keyboard`() {
        val slot = slot()

        assertThat(slot.position).isEqualTo(placement.place(keyboard, input, 1080, 2340))
        assertThat(slot.minY).isEqualTo(122)
        assertThat(slot.maxY).isEqualTo(1440 - 12 - 156)
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
    fun `a negative lift stops just above the keyboard`() {
        assertThat(slot(lift = -300).position.y).isEqualTo(1440 - 12 - 156)
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
}
