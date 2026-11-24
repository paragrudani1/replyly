package com.paragrudani.replyly.overlay

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Seeded property test of the drag-to-snooze target over random drag paths (#56).
 *
 * Invariants, for every path (random density, start, target, timing and moves):
 * 1. a drag released before 120 ms never reveals the target and never releases inside;
 * 2. reveal is monotonic within a drag: once true, it stays true;
 * 3. reveal is true from 300 ms on, and before that only with the finger ≥ 72 dp below the start
 *    at some move after 120 ms;
 * 4. inside ⇔ revealed and the finger within radius + slack of the target centre;
 * 5. `entered` is true exactly on a move that goes from outside to inside;
 * 6. a release is inside ⇔ the last move was inside.
 */
class DismissTargetRulePropertyTest {
    private val random = Random(SEED)

    @Test
    fun `random drag paths keep the target's invariants`() {
        repeat(RUNS) { run ->
            val density = listOf(1f, 2f, 2.625f, 3f, 3.5f)[random.nextInt(5)]
            val rule = DismissTargetRule(density)
            val startX = random.nextFloat() * 1440
            val startY = random.nextFloat() * 3000
            val target = DismissTargetRule.Center(random.nextFloat() * 1440, random.nextFloat() * 3000)
            val drag = DismissTargetDrag(rule, startY, target)
            val quick = random.nextInt(4) == 0
            val endMs = if (quick) random.nextLong(0, 120) else random.nextLong(0, 1_500)
            var t = 0L
            var x = startX
            var y = startY
            var revealed = false
            var wasInside = false
            var lastInside = false
            var sawEarlyCondition = false
            while (t < endMs) {
                t = (t + random.nextLong(1, 40)).coerceAtMost(endMs)
                if (random.nextInt(5) == 0) {
                    // Jump onto or near the target, the interesting case for hits.
                    x = target.x + (random.nextFloat() - 0.5f) * 4 * rule.hitRadiusPx
                    y = target.y + (random.nextFloat() - 0.5f) * 4 * rule.hitRadiusPx
                } else {
                    x += (random.nextFloat() - 0.5f) * 200
                    y += (random.nextFloat() - 0.4f) * 200
                }
                val update = drag.onMove(t, x, y)
                val what = "run $run t=$t density=$density"
                if (t >= DismissTargetRule.EARLY_MIN_MS && y - startY >= DismissTargetRule.EARLY_DOWN_DP * density) {
                    sawEarlyCondition = true
                }
                assertWithMessage("monotonic: $what").that(!revealed || update.revealed).isTrue()
                assertWithMessage("reveal rule: $what")
                    .that(update.revealed)
                    .isEqualTo(t >= DismissTargetRule.REVEAL_MS || sawEarlyCondition)
                revealed = update.revealed
                val within = hypot(x - target.x, y - target.y) <= rule.hitRadiusPx
                assertWithMessage("hit: $what").that(update.inside).isEqualTo(revealed && within)
                assertWithMessage("entered: $what").that(update.entered).isEqualTo(update.inside && !wasInside)
                wasInside = update.inside
                lastInside = update.inside
            }
            if (endMs < DismissTargetRule.EARLY_MIN_MS) {
                assertWithMessage("quick drag: run $run").that(revealed).isFalse()
            }
            assertWithMessage("release: run $run").that(drag.releasedInside()).isEqualTo(lastInside)
        }
    }

    private companion object {
        const val SEED = 5600L
        const val RUNS = 3_000
    }
}
