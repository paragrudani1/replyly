package com.paragrudani.replyly.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DismissTargetRuleTest {
    // 1 px = 1 dp here, so the numbers read as dp.
    private val rule = DismissTargetRule(density = 1f)
    private val target = DismissTargetRule.Center(540f, 1400f)

    private fun drag() = DismissTargetDrag(rule, startY = 1300f, target = target)

    @Test
    fun `a drag shows the target after 300 ms`() {
        val drag = drag()
        assertThat(drag.onMove(299, 990f, 1290f).revealed).isFalse()
        assertThat(drag.onMove(300, 990f, 1290f).revealed).isTrue()
    }

    @Test
    fun `72 dp down shows it after 120 ms, not before`() {
        val drag = drag()
        assertThat(drag.onMove(80, 1000f, 1400f).revealed).isFalse()
        assertThat(drag.onMove(119, 1000f, 1400f).revealed).isFalse()
        assertThat(drag.onMove(120, 1000f, 1372f).revealed).isTrue()
    }

    @Test
    fun `less than 72 dp down waits for 300 ms`() {
        val drag = drag()
        assertThat(drag.onMove(200, 1000f, 1371f).revealed).isFalse()
    }

    @Test
    fun `moving up never reveals early`() {
        val drag = drag()
        assertThat(drag.onMove(200, 1000f, 1000f).revealed).isFalse()
    }

    @Test
    fun `once shown it stays shown for the rest of the drag`() {
        val drag = drag()
        drag.onMove(150, 1000f, 1400f)
        assertThat(drag.onMove(160, 1000f, 1300f).revealed).isTrue()
    }

    @Test
    fun `hit is the finger within radius plus slack`() {
        val reach = rule.hitRadiusPx
        assertThat(reach).isEqualTo(DismissTargetRule.RADIUS_DP + DismissTargetRule.SLACK_DP)
        val drag = drag()
        drag.onMove(400, 900f, 1350f)
        assertThat(drag.onMove(410, target.x + reach, target.y).inside).isTrue()
        assertThat(drag.onMove(420, target.x + reach + 1, target.y).inside).isFalse()
        assertThat(drag.onMove(430, target.x + 30f, target.y - 30f).inside).isTrue()
    }

    @Test
    fun `entering fires once per entry`() {
        val drag = drag()
        drag.onMove(400, 900f, 1350f)
        assertThat(drag.onMove(410, target.x, target.y).entered).isTrue()
        assertThat(drag.onMove(420, target.x + 5, target.y).entered).isFalse()
        assertThat(drag.onMove(430, 900f, 1100f).entered).isFalse()
        assertThat(drag.onMove(440, target.x, target.y).entered).isTrue()
    }

    @Test
    fun `a hidden target cannot be hit`() {
        val drag = drag()
        // Fast: at the target within 100 ms, before any reveal.
        val update = drag.onMove(100, target.x, target.y)
        assertThat(update.inside).isFalse()
        assertThat(drag.releasedInside()).isFalse()
    }

    @Test
    fun `release inside only when the finger is inside at the end`() {
        val drag = drag()
        drag.onMove(400, target.x, target.y)
        assertThat(drag.releasedInside()).isTrue()
        drag.onMove(500, 900f, 1100f)
        assertThat(drag.releasedInside()).isFalse()
    }
}
