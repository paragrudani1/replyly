package com.paragrudani.replyly.parser.generic

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.generic.Side.LEFT
import com.paragrudani.replyly.parser.generic.Side.RIGHT
import com.paragrudani.replyly.screen.Bounds
import org.junit.Test

class SidesTest {
    private val list = Bounds(0, 257, 1080, 2052)

    private fun box(
        left: Int,
        right: Int,
    ) = Bounds(left, 500, right, 600)

    private fun sides(
        vararg boxes: Bounds,
        ticks: List<Boolean> = boxes.map { false },
    ) = Sides.resolve(boxes.toList(), ticks, list)

    @Test
    fun `a bubble is on the side it is closer to`() {
        assertThat(sides(box(40, 400), box(700, 1040))).containsExactly(LEFT, RIGHT).inOrder()
    }

    @Test
    fun `a wide incoming bubble pushed right by the avatar column stays left`() {
        // Group: incoming bubbles start at 125. The wide one ends nearer the right edge than it starts.
        val sides = sides(box(125, 500), box(125, 1010), box(700, 1040))

        assertThat(sides).containsExactly(LEFT, LEFT, RIGHT).inOrder()
    }

    @Test
    fun `a nearly centred bubble with ticks is mine`() {
        val sides = sides(box(120, 960), box(40, 300), ticks = listOf(true, false))

        assertThat(sides).containsExactly(RIGHT, LEFT).inOrder()
    }

    @Test
    fun `once ticks are on screen a bubble without one is not mine`() {
        // Both bubbles are wide; only the tick tells them apart.
        val sides = sides(box(60, 1040), box(60, 1040), ticks = listOf(true, false))

        assertThat(sides).containsExactly(RIGHT, LEFT).inOrder()
    }

    @Test
    fun `centred boxes are recognised`() {
        assertThat(Sides.isCentered(box(450, 630), list)).isTrue()
        assertThat(Sides.isCentered(box(0, 1080), list)).isTrue()
        assertThat(Sides.isCentered(box(40, 400), list)).isFalse()
    }
}
