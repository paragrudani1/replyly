package com.paragrudani.replyly.overlay

import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.screen.Bounds
import org.junit.Test
import kotlin.random.Random

/**
 * Seeded property test for [ButtonPlacement.slot] over random screens, keyboards (docked,
 * floating, nearly full height), inputs, status bars and saved lifts. Invariants:
 *
 * 1. `minY <= position.y <= maxY`, and `clampY` maps every value into that range.
 * 2. When the space allows it (`topInset + margin + size + margin <= keyboard.top`), the button is
 *    fully below the status bar plus margin and fully above the keyboard minus margin.
 * 3. Round trip: for every `y` in range, `slot(savedLift = liftOf(y))` gives back `y`.
 * 4. Dragging never changes `x` (vertical only): `x` equals the default placement's `x`.
 */
class ButtonSlotPropertyTest {
    @Test
    fun `invariants hold for random layouts`() {
        val random = Random(SEED)
        repeat(RUNS) { run ->
            val size = random.nextInt(60, 200)
            val margin = random.nextInt(0, 40)
            val placement = ButtonPlacement(size, margin, maxInputLiftPx = random.nextInt(100, 700))
            val width = random.nextInt(600, 2400)
            val height = random.nextInt(600, 2400)
            val kbTop = random.nextInt(0, height)
            val kbLeft = random.nextInt(0, width / 2)
            val kbRight = random.nextInt(kbLeft + 1, width + 200)
            val keyboard = Bounds(kbLeft, kbTop, kbRight, random.nextInt(kbTop + 1, height + 1))
            val input =
                if (random.nextBoolean()) {
                    val top = random.nextInt(0, height)
                    Bounds(random.nextInt(0, width / 2), top, width, (top + random.nextInt(1, 300)))
                } else {
                    null
                }
            val topInset = random.nextInt(0, 200)
            val lift = if (random.nextBoolean()) random.nextInt(-500, 3000) else null
            val slot = placement.slot(keyboard, input, width, height, topInset, lift)
            val what = "run $run: kb=$keyboard input=$input inset=$topInset lift=$lift size=$size m=$margin -> $slot"

            assertWithMessage(what).that(slot.minY).isAtMost(slot.maxY)
            assertWithMessage(what).that(slot.position.y).isIn(slot.minY..slot.maxY)
            val probe = random.nextInt(-5000, 5000)
            assertWithMessage(what).that(slot.clampY(probe)).isIn(slot.minY..slot.maxY)

            if (topInset + margin + size + margin <= keyboard.top) {
                assertWithMessage(what).that(slot.position.y).isAtLeast(topInset + margin)
                assertWithMessage(what).that(slot.position.y + size).isAtMost(keyboard.top - margin)
            }

            val y = random.nextInt(slot.minY, slot.maxY + 1)
            val back = placement.slot(keyboard, input, width, height, topInset, placement.liftOf(y, keyboard.top))
            assertWithMessage(what).that(back.position.y).isEqualTo(y)

            assertWithMessage(what).that(slot.position.x).isEqualTo(placement.place(keyboard, input, width, height).x)
        }
    }

    private companion object {
        const val SEED = 20261121L
        const val RUNS = 5_000
    }
}
