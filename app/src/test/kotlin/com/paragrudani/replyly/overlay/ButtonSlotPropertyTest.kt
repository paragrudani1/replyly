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
 * 5. Send button: when the input bar rests on the keyboard (bottom at most `margin` below the
 *    keyboard top, top at most `maxInputLift` above it), `maxY <= max(minY, default y)`, so neither
 *    a drag nor a saved spot goes below the default spot; when the space allows it
 *    (`topInset + margin + size + margin <= input.top`), the button is fully above `input.top - margin`.
 * 6. A multi-line input bar that grows between saving a spot and restoring it still keeps the
 *    restored button off the bar (invariant 5 for the taller bar, with the old saved lift).
 */
class ButtonSlotPropertyTest {
    @Test
    fun `invariants hold for random layouts`() {
        val random = Random(SEED)
        repeat(RUNS) { run ->
            val size = random.nextInt(60, 200)
            val margin = random.nextInt(0, 40)
            val maxLift = random.nextInt(100, 700)
            val placement = ButtonPlacement(size, margin, maxInputLiftPx = maxLift)
            val width = random.nextInt(600, 2400)
            val height = random.nextInt(600, 2400)
            val kbTop = random.nextInt(0, height)
            val kbLeft = random.nextInt(0, width / 2)
            val kbRight = random.nextInt(kbLeft + 1, width + 200)
            val keyboard = Bounds(kbLeft, kbTop, kbRight, random.nextInt(kbTop + 1, height + 1))
            val input =
                when (random.nextInt(3)) {
                    0 -> {
                        val top = random.nextInt(0, height)
                        Bounds(random.nextInt(0, width / 2), top, width, (top + random.nextInt(1, 300)))
                    }

                    1 -> {
                        barOnKeyboard(random, keyboard, width, margin, maxLift)
                    }

                    else -> {
                        null
                    }
                }
            val topInset = random.nextInt(0, 200)
            val lift = if (random.nextBoolean()) random.nextInt(-500, 3000) else null
            val slot = placement.slot(keyboard, input, width, height, topInset, lift)
            val what =
                "run $run: kb=$keyboard input=$input inset=$topInset lift=$lift size=$size m=$margin " +
                    "maxLift=$maxLift -> $slot"

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

            val default = placement.place(keyboard, input, width, height)
            assertWithMessage(what).that(slot.position.x).isEqualTo(default.x)

            if (input != null && onKeyboard(input, keyboard, margin, maxLift)) {
                assertWithMessage(what).that(slot.maxY).isAtMost(maxOf(slot.minY, default.y))
                if (topInset + margin + size + margin <= input.top) {
                    assertWithMessage(what).that(slot.position.y + size).isAtMost(input.top - margin)
                    assertWithMessage(what).that(slot.maxY + size).isAtMost(input.top - margin)
                }
            }
        }
    }

    @Test
    fun `a saved spot stays off an input bar that grew before restore`() {
        val random = Random(SEED + 1)
        var checked = 0
        repeat(RUNS) { run ->
            val size = random.nextInt(60, 200)
            val margin = random.nextInt(0, 40)
            val maxLift = random.nextInt(100, 700)
            val placement = ButtonPlacement(size, margin, maxInputLiftPx = maxLift)
            val width = random.nextInt(600, 2400)
            val height = random.nextInt(1200, 2400)
            val kbTop = random.nextInt(height / 3, height)
            val keyboard = Bounds(0, kbTop, width, random.nextInt(kbTop + 1, height + 1))
            val topInset = random.nextInt(0, 200)

            // One-line bar on the keyboard; the user drags and the spot is saved as a lift.
            val before = barOnKeyboard(random, keyboard, width, margin, maxLift)
            val saveSlot = placement.slot(keyboard, before, width, height, topInset)
            val y = random.nextInt(saveSlot.minY, saveSlot.maxY + 1)
            val lift = placement.liftOf(y, keyboard.top)

            // More lines typed: the bar grows upwards, its bottom stays on the keyboard.
            val headroom = maxLift - (keyboard.top - before.top)
            val grown = before.copy(top = before.top - random.nextInt(0, headroom + 1))
            val restored = placement.slot(keyboard, grown, width, height, topInset, lift)
            val what =
                "run $run: kb=$keyboard before=$before grown=$grown inset=$topInset y=$y " +
                    "size=$size m=$margin -> $restored"

            assertWithMessage(what).that(onKeyboard(grown, keyboard, margin, maxLift)).isTrue()
            assertWithMessage(what).that(restored.position.y).isIn(restored.minY..restored.maxY)
            if (topInset + margin + size + margin <= grown.top) {
                checked++
                assertWithMessage(what).that(restored.position.y + size).isAtMost(grown.top - margin)
            }
        }
        // The generator must actually exercise the case, not skip it for lack of room.
        assertWithMessage("runs with room above the grown bar").that(checked).isGreaterThan(RUNS / 2)
    }

    /** The simulated truth for "the input bar rests on the keyboard" (spec 7, send button). */
    private fun onKeyboard(
        input: Bounds,
        keyboard: Bounds,
        margin: Int,
        maxLift: Int,
    ) = input.bottom <= keyboard.top + margin && keyboard.top - input.top in 0..maxLift

    /** An input bar whose bottom sits on the keyboard top (within [margin]) and is at most [maxLift] tall above it. */
    private fun barOnKeyboard(
        random: Random,
        keyboard: Bounds,
        width: Int,
        margin: Int,
        maxLift: Int,
    ): Bounds {
        val bottom = keyboard.top + random.nextInt(-minOf(margin, maxLift - 1), margin + 1)
        // At least 1 px tall, top never below the keyboard top.
        val top = keyboard.top - random.nextInt(maxOf(0, keyboard.top - bottom + 1), maxLift + 1)
        return Bounds(random.nextInt(0, width / 2), top, width, bottom)
    }

    private companion object {
        const val SEED = 20261121L
        const val RUNS = 5_000
    }
}
