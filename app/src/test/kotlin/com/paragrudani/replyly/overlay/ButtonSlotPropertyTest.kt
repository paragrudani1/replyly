package com.paragrudani.replyly.overlay

import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.screen.Bounds
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Seeded property test for [ButtonPlacement.slot] and the edge snap over random screens,
 * keyboards (docked, floating, one-handed, hanging off either edge, nearly full height), inputs,
 * status bars, saved (side, lift) and drags. Every invariant is checked on both sides:
 *
 * 1. `minY <= position.y <= maxY`, and `clampY` maps every value into that range.
 * 2. When the space allows it (`topInset + margin + size + margin <= keyboard.top`), the button is
 *    fully below the status bar plus margin and fully above the keyboard minus margin.
 * 3. Round trip: for every `y` in range and either side, `slot(savedLift = liftOf(y), savedSide =
 *    side)` gives back `y` on that side's edge.
 * 4. Sides (#54, replaces "dragging never changes x"): at rest `x` is the left or the right edge,
 *    the one of the saved side; `leftX <= rightX`, the right edge is the default placement's `x`;
 *    when the screen is wide enough (`margin + size + margin <= width`) both edges and the whole
 *    drag range `minX..maxX` keep the window fully on screen, `margin` from the sides, and
 *    `clampX` maps every value into that range; `snap(x)` returns an edge at least as near as the
 *    other one (a tie goes right), and when the keyboard is fully on screen and wide enough that
 *    the edges are apart and nothing is clamped, the side the button's centre is on relative to the keyboard's centre.
 * 5. Send button: when the input bar rests on the keyboard (bottom at most `margin` below the
 *    keyboard top, top at most `maxInputLift` above it), `maxY <= max(minY, default y)`, so neither
 *    a drag nor a saved spot goes below the default spot; when the space allows it
 *    (`topInset + margin + size + margin <= input.top`), the button is fully above `input.top - margin`.
 * 6. A multi-line input bar that grows between saving a spot and restoring it still keeps the
 *    restored button off the bar (invariant 5 for the taller bar, with the old saved lift).
 * 7. Release: a random drag from the resting spot, clamped and snapped like the window does it,
 *    ends inside the slot (1, 2, 5 hold for it) with `x` on an edge, and saving it as (side,
 *    lift) restores exactly that spot.
 * 8. Layout change mid-drag (generator from review pr57-r1): the drag starts at rest in slot A, the
 *    layout changes while the finger is held (rotation; a one-handed or floating keyboard resized
 *    or moved; the keyboard height changed) and slot B arrives. The held window moves into B at
 *    once (1, 2, 5 hold for it in B); the release snaps inside B with `x` on an edge, and the side
 *    the window glides to is the one the controller records; the spot saved as dp (`liftOf /
 *    density`, densities 1 to 4) and restored as `(dp * density).roundToInt()` gives back exactly
 *    that slot in B (else a refresh would cut the glide short); back in layout A the side, its
 *    edge and 1, 2, 5 still hold.
 */
class ButtonSlotPropertyTest {
    @Test
    fun `invariants hold for random layouts`() {
        val random = Random(SEED)
        var centreChecks = 0
        var leftReleases = 0
        repeat(RUNS) { run ->
            val size = random.nextInt(60, 200)
            val margin = random.nextInt(0, 40)
            val maxLift = random.nextInt(100, 700)
            val placement = ButtonPlacement(size, margin, maxInputLiftPx = maxLift)
            val width = random.nextInt(600, 2400)
            val height = random.nextInt(600, 2400)
            val kbTop = random.nextInt(0, height)
            val kbLeft = if (random.nextInt(4) == 0) random.nextInt(-300, 0) else random.nextInt(0, width / 2)
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
            val side = if (random.nextBoolean()) ButtonSide.LEFT else ButtonSide.RIGHT
            val slot = placement.slot(keyboard, input, width, height, topInset, lift, side)
            val what =
                "run $run: screen=${width}x$height kb=$keyboard input=$input inset=$topInset lift=$lift " +
                    "side=$side size=$size m=$margin maxLift=$maxLift -> $slot"
            val default = placement.place(keyboard, input, width, height)
            val barOnKeyboard = input != null && onKeyboard(input, keyboard, margin, maxLift)

            // 1, 2, 5 for the resting spot.
            checkVertical(what, slot, slot.position.y, keyboard, input, barOnKeyboard, default, topInset, size, margin)
            val probe = random.nextInt(-5000, 5000)
            assertWithMessage(what).that(slot.clampY(probe)).isIn(slot.minY..slot.maxY)

            // 3: round trip on both sides.
            val y = random.nextInt(slot.minY, slot.maxY + 1)
            for (s in ButtonSide.entries) {
                val back =
                    placement.slot(
                        keyboard,
                        input,
                        width,
                        height,
                        topInset,
                        placement.liftOf(y, keyboard.top),
                        s,
                    )
                assertWithMessage("$what back side=$s").that(back.position).isEqualTo(ButtonPosition(back.edgeX(s), y))
                assertWithMessage("$what back side=$s").that(back.side).isEqualTo(s)
            }

            // 4: sides and the horizontal range.
            assertWithMessage(what).that(slot.side).isEqualTo(side)
            assertWithMessage(what).that(slot.position.x).isEqualTo(
                if (side ==
                    ButtonSide.LEFT
                ) {
                    slot.leftX
                } else {
                    slot.rightX
                },
            )
            assertWithMessage(what).that(slot.rightX).isEqualTo(default.x)
            assertWithMessage(what).that(slot.leftX).isAtMost(slot.rightX)
            assertWithMessage(what).that(slot.minX).isAtMost(slot.leftX)
            assertWithMessage(what).that(slot.rightX).isAtMost(slot.maxX)
            if (margin + size + margin <= width) {
                for (x in listOf(slot.leftX, slot.rightX, slot.minX, slot.maxX, slot.clampX(probe))) {
                    assertWithMessage("$what x=$x").that(x).isAtLeast(margin)
                    assertWithMessage("$what x=$x").that(x + size).isAtMost(width - margin)
                }
            }
            val anyX = random.nextInt(-3000, 5000)
            val snapped = slot.snap(anyX)
            val other = if (snapped == ButtonSide.LEFT) ButtonSide.RIGHT else ButtonSide.LEFT
            val dSnapped = kotlin.math.abs(anyX - slot.edgeX(snapped))
            val dOther = kotlin.math.abs(anyX - slot.edgeX(other))
            assertWithMessage("$what x=$anyX").that(dSnapped).isAtMost(dOther)
            if (dSnapped == dOther) assertWithMessage("$what tie x=$anyX").that(snapped).isEqualTo(ButtonSide.RIGHT)
            val unclamped =
                keyboard.left >= 0 && keyboard.right <= width && keyboard.right - keyboard.left > 2 * margin + size
            if (unclamped) {
                centreChecks++
                // The spec's own words: the button's centre against the keyboard's centre, a tie right.
                val centreLeft = 2 * anyX + size < keyboard.left + keyboard.right
                assertWithMessage("$what centre x=$anyX")
                    .that(snapped)
                    .isEqualTo(if (centreLeft) ButtonSide.LEFT else ButtonSide.RIGHT)
            }

            // 7: a drag from rest, released: clamped and snapped like OverlayWindows does it.
            val dx = random.nextInt(-3000, 3000)
            val dy = random.nextInt(-3000, 3000)
            val dropX = slot.clampX(slot.position.x + dx)
            val dropY = slot.clampY(slot.position.y + dy)
            val released = slot.onSide(slot.snap(dropX)).let { it.copy(position = it.position.copy(y = dropY)) }
            val rwhat = "$what drag=($dx,$dy) -> $released"
            assertWithMessage(rwhat).that(released.position.x).isAnyOf(released.leftX, released.rightX)
            assertWithMessage(rwhat).that(released.position.x).isEqualTo(released.edgeX(released.side))
            checkVertical(rwhat, released, dropY, keyboard, input, barOnKeyboard, default, topInset, size, margin)
            val restored =
                placement.slot(
                    keyboard,
                    input,
                    width,
                    height,
                    topInset,
                    placement.liftOf(dropY, keyboard.top),
                    released.side,
                )
            assertWithMessage(rwhat).that(restored).isEqualTo(released)
            if (released.side == ButtonSide.LEFT) leftReleases++
        }
        // The generator must exercise both rules, not skip them.
        assertWithMessage("runs checked against the keyboard centre").that(centreChecks).isGreaterThan(RUNS / 4)
        assertWithMessage("releases on the left").that(leftReleases).isGreaterThan(RUNS / 4)
    }

    @Test
    fun `a layout change in the middle of a drag keeps every rule`() {
        val random = Random(SEED + 2)
        var leftReleases = 0
        repeat(RUNS) { run -> if (midDragRun(random, "run $run") == ButtonSide.LEFT) leftReleases++ }
        assertWithMessage("releases on the left").that(leftReleases).isGreaterThan(RUNS / 4)
    }

    private data class Layout(
        val width: Int,
        val height: Int,
        val keyboard: Bounds,
        val input: Bounds?,
        val topInset: Int,
    )

    /** One run of invariant 8; returns the side the button was released to. */
    private fun midDragRun(
        random: Random,
        label: String,
    ): ButtonSide {
        val size = random.nextInt(60, 200)
        val margin = random.nextInt(0, 40)
        val maxLift = random.nextInt(100, 700)
        val placement = ButtonPlacement(size, margin, maxInputLiftPx = maxLift)
        val density =
            if (random.nextBoolean()) {
                DENSITIES[random.nextInt(DENSITIES.size)]
            } else {
                1f +
                    random.nextFloat() * 3f
            }
        val l1 = randomLayout(random, random.nextInt(600, 2400), random.nextInt(600, 2400), margin, maxLift)
        val l2 =
            when (random.nextInt(3)) {
                0 -> randomLayout(random, l1.height, l1.width, margin, maxLift)

                // rotation
                1 -> resized(random, l1, margin, maxLift)

                // one-handed / floating, resized or moved
                else -> l1.copy(keyboard = l1.keyboard.copy(top = random.nextInt(0, l1.keyboard.bottom)))
            }
        val savedLift = if (random.nextBoolean()) random.nextInt(-500, 3000) else null
        val savedSide = if (random.nextBoolean()) ButtonSide.LEFT else ButtonSide.RIGHT
        val a = l1.slot(placement, savedLift, savedSide)
        val b = l2.slot(placement, savedLift, savedSide)
        val what =
            "$label: size=$size m=$margin maxLift=$maxLift d=$density l1=$l1 l2=$l2 " +
                "saved=($savedSide,$savedLift)"

        // Held in A, then B arrives: the window moves into B at once (OverlayWindows.showButton).
        val heldX = b.clampX(a.clampX(a.position.x + random.nextInt(-3000, 3000)))
        val heldY = b.clampY(a.clampY(a.position.y + random.nextInt(-3000, 3000)))
        assertWithMessage("$what held=($heldX,$heldY)").that(heldX).isIn(b.minX..b.maxX)
        l2.checkVertical("$what held=($heldX,$heldY)", placement, b, heldY, size, margin, maxLift)

        // Later moves clamp to B; the release glides to B's nearer edge, the controller records it.
        val x = b.clampX(a.position.x + random.nextInt(-3000, 3000))
        val y = b.clampY(a.position.y + random.nextInt(-3000, 3000))
        val target = b.edgeX(b.snap(x))
        val side = b.snap(b.clampX(target))
        val shownAt = b.onSide(side).let { it.copy(position = it.position.copy(y = b.clampY(y))) }
        val rwhat = "$what drop=($x,$y) target=$target side=$side -> $shownAt"
        assertWithMessage(rwhat).that(b.edgeX(side)).isEqualTo(target)
        assertWithMessage(rwhat).that(shownAt.position.y).isEqualTo(y)
        l2.checkVertical(rwhat, placement, b, y, size, margin, maxLift)

        // Saved as OverlayComponent does it, then the next refresh in layout 2.
        val liftDp = placement.liftOf(y, l2.keyboard.top) / density
        val restoredPx = (liftDp * density).roundToInt()
        assertWithMessage("$rwhat liftDp=$liftDp").that(l2.slot(placement, restoredPx, side)).isEqualTo(shownAt)

        // Back to layout 1 (rotated back, keyboard back): side, edge and the vertical rules hold.
        val back = l1.slot(placement, restoredPx, side)
        assertWithMessage("$rwhat back=$back").that(back.side).isEqualTo(side)
        assertWithMessage("$rwhat back=$back").that(back.position.x).isEqualTo(back.edgeX(side))
        l1.checkVertical("$rwhat back=$back", placement, back, back.position.y, size, margin, maxLift)
        return side
    }

    private fun Layout.slot(
        placement: ButtonPlacement,
        lift: Int?,
        side: ButtonSide,
    ) = placement.slot(keyboard, input, width, height, topInset, lift, side)

    /** Invariants 1, 2 and 5 at [y] in [slot] for this layout, plus the window on screen. */
    private fun Layout.checkVertical(
        what: String,
        placement: ButtonPlacement,
        slot: ButtonSlot,
        y: Int,
        size: Int,
        margin: Int,
        maxLift: Int,
    ) {
        val input = input
        val barOnKeyboard = input != null && onKeyboard(input, keyboard, margin, maxLift)
        val default = placement.place(keyboard, input, width, height)
        checkVertical(what, slot, y, keyboard, input, barOnKeyboard, default, topInset, size, margin)
        if (margin + size + margin <= width) {
            assertWithMessage(what).that(slot.position.x).isAtLeast(margin)
            assertWithMessage(what).that(slot.position.x + size).isAtMost(width - margin)
        }
    }

    private fun randomLayout(
        random: Random,
        width: Int,
        height: Int,
        margin: Int,
        maxLift: Int,
    ): Layout {
        val kbTop = random.nextInt(0, height)
        val kbLeft = if (random.nextInt(4) == 0) random.nextInt(-300, 0) else random.nextInt(0, width / 2)
        val kbRight = random.nextInt(kbLeft + 1, width + 200)
        val keyboard = Bounds(kbLeft, kbTop, kbRight, random.nextInt(kbTop + 1, height + 1))
        return Layout(
            width,
            height,
            keyboard,
            randomInput(random, keyboard, width, height, margin, maxLift),
            random.nextInt(0, 200),
        )
    }

    /** Same screen; the keyboard gets narrower or wider, or moves sideways (one-handed, floating). */
    private fun resized(
        random: Random,
        l: Layout,
        margin: Int,
        maxLift: Int,
    ): Layout {
        val left = l.keyboard.left + random.nextInt(-400, 400)
        val right = maxOf(left + 1, l.keyboard.right + random.nextInt(-400, 400))
        val keyboard = l.keyboard.copy(left = left, right = right)
        val input =
            if (random.nextBoolean()) {
                l.input
            } else {
                randomInput(
                    random,
                    keyboard,
                    l.width,
                    l.height,
                    margin,
                    maxLift,
                )
            }
        return l.copy(keyboard = keyboard, input = input)
    }

    private fun randomInput(
        random: Random,
        keyboard: Bounds,
        width: Int,
        height: Int,
        margin: Int,
        maxLift: Int,
    ): Bounds? =
        when (random.nextInt(3)) {
            0 -> {
                val top = random.nextInt(0, height)
                Bounds(random.nextInt(0, width / 2), top, width, top + random.nextInt(1, 300))
            }

            1 -> {
                barOnKeyboard(random, keyboard, width, margin, maxLift)
            }

            else -> {
                null
            }
        }

    /** Invariants 1, 2 and 5 for a button whose window top is at [y] in [slot]. */
    private fun checkVertical(
        what: String,
        slot: ButtonSlot,
        y: Int,
        keyboard: Bounds,
        input: Bounds?,
        barOnKeyboard: Boolean,
        default: ButtonPosition,
        topInset: Int,
        size: Int,
        margin: Int,
    ) {
        assertWithMessage(what).that(slot.minY).isAtMost(slot.maxY)
        assertWithMessage(what).that(y).isIn(slot.minY..slot.maxY)
        if (topInset + margin + size + margin <= keyboard.top) {
            assertWithMessage(what).that(y).isAtLeast(topInset + margin)
            assertWithMessage(what).that(y + size).isAtMost(keyboard.top - margin)
        }
        if (input != null && barOnKeyboard) {
            assertWithMessage(what).that(slot.maxY).isAtMost(maxOf(slot.minY, default.y))
            assertWithMessage(what).that(y).isAtMost(maxOf(slot.minY, default.y))
            if (topInset + margin + size + margin <= input.top) {
                assertWithMessage(what).that(y + size).isAtMost(input.top - margin)
                assertWithMessage(what).that(slot.maxY + size).isAtMost(input.top - margin)
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
            val side = if (random.nextBoolean()) ButtonSide.LEFT else ButtonSide.RIGHT

            // More lines typed: the bar grows upwards, its bottom stays on the keyboard.
            val headroom = maxLift - (keyboard.top - before.top)
            val grown = before.copy(top = before.top - random.nextInt(0, headroom + 1))
            val restored = placement.slot(keyboard, grown, width, height, topInset, lift, side)
            val what =
                "run $run: kb=$keyboard before=$before grown=$grown inset=$topInset y=$y side=$side " +
                    "size=$size m=$margin -> $restored"

            assertWithMessage(what).that(onKeyboard(grown, keyboard, margin, maxLift)).isTrue()
            assertWithMessage(what).that(restored.position.y).isIn(restored.minY..restored.maxY)
            assertWithMessage(what).that(restored.position.x).isEqualTo(restored.edgeX(side))
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
        const val SEED = 20261124L
        const val RUNS = 5_000
        val DENSITIES = floatArrayOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)
    }
}
