package com.paragrudani.replyly.overlay

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.screen.Bounds
import org.junit.Test

/** Pixel numbers from an S23-like 1080x2340 screen; button 120 px, margin 24 px, max lift 400 px. */
class ButtonPlacementTest {
    private val placement = ButtonPlacement(buttonSizePx = 120, marginPx = 24, maxInputLiftPx = 400)

    @Test
    fun `portrait Gboard - right side, just above the chat's input bar`() {
        val position =
            placement.place(
                keyboard = Bounds(0, 1440, 1080, 2340),
                input = Bounds(30, 1300, 900, 1420),
                screenWidth = 1080,
                screenHeight = 2340,
            )

        // Above the input bar (top 1300), so it covers neither the send button nor the keyboard.
        assertThat(position).isEqualTo(ButtonPosition(x = 936, y = 1156))
    }

    @Test
    fun `input far above the keyboard - sits on the keyboard's top edge instead`() {
        val position =
            placement.place(
                keyboard = Bounds(0, 1440, 1080, 2340),
                input = Bounds(30, 200, 900, 320),
                screenWidth = 1080,
                screenHeight = 2340,
            )

        assertThat(position).isEqualTo(ButtonPosition(x = 936, y = 1296))
    }

    @Test
    fun `no input bounds - sits on the keyboard's top edge`() {
        val position =
            placement.place(
                Bounds(0, 1440, 1080, 2340),
                input = null,
                screenWidth = 1080,
                screenHeight = 2340,
            )

        assertThat(position).isEqualTo(ButtonPosition(x = 936, y = 1296))
    }

    @Test
    fun `landscape - right side above the input bar`() {
        val position =
            placement.place(
                keyboard = Bounds(0, 560, 2340, 1080),
                input = Bounds(100, 460, 2000, 550),
                screenWidth = 2340,
                screenHeight = 1080,
            )

        assertThat(position).isEqualTo(ButtonPosition(x = 2196, y = 316))
    }

    @Test
    fun `floating keyboard - above its own right corner, input below it is ignored`() {
        val position =
            placement.place(
                keyboard = Bounds(200, 1200, 880, 1800),
                input = Bounds(30, 2200, 900, 2300),
                screenWidth = 1080,
                screenHeight = 2340,
            )

        assertThat(position).isEqualTo(ButtonPosition(x = 736, y = 1056))
    }

    @Test
    fun `stays on screen when the keyboard is nearly full height`() {
        val position = placement.place(Bounds(0, 50, 1080, 2340), input = null, screenWidth = 1080, screenHeight = 2340)

        assertThat(position).isEqualTo(ButtonPosition(x = 936, y = 24))
    }

    @Test
    fun `stays on screen when a floating keyboard hangs off the right edge`() {
        val position =
            placement.place(Bounds(600, 1200, 1200, 1800), input = null, screenWidth = 1080, screenHeight = 2340)

        assertThat(position).isEqualTo(ButtonPosition(x = 936, y = 1056))
    }
}
