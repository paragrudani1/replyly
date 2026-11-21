package com.paragrudani.replyly.panel

import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.insert.InsertResult
import com.paragrudani.replyly.insert.ReplyInserter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random

/**
 * Seeded fuzzer for the "one call per tap" rule (AGENTS.md rule 4) while inserts, calls and taps
 * overlap. Each seed opens a panel and plays random taps (Regenerate, a chip, insert a card, copy a
 * card) at random gaps of 0–300 ms. A call takes [FakeGenerator.GENERATE_MILLIS]; an insert takes
 * 0–400 ms and fails one time in three. The panel is never disposed, so a late call would show.
 *
 * Invariants, for every seed:
 * 1. no LLM call starts after a tap whose insert went on to succeed;
 * 2. at most one call per user tap: only the open, Regenerate and chip taps may add a call, each at
 *    most one; insert and copy taps and the time between taps add none;
 * 3. the panel closes at most once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplyPanelTapFuzzTest {
    @Test
    fun `random taps during slow and failing inserts never call after a successful insert, nor twice per tap`() {
        val stats = Stats()
        repeat(SEEDS) { seed -> runSeed(seed, stats) }
        // In the test report (system-out), for the PR's fuzzer stats.
        println("ReplyPanelTapFuzzTest: $stats")
    }

    private class Stats {
        var seeds = 0
        var taps = 0
        var callTaps = 0
        var calls = 0
        var insertsOk = 0
        var insertsFailed = 0
        var tapsAfterInsertOk = 0

        override fun toString() =
            "seeds=$seeds taps=$taps callTaps=$callTaps calls=$calls insertsOk=$insertsOk " +
                "insertsFailed=$insertsFailed tapsAfterSuccessfulInsert=$tapsAfterInsertOk"
    }

    /** 0–400 ms per insert; fails (no field, or failed) one time in three. */
    private class RandomInserter(
        private val random: Random,
        private val onResult: (InsertResult) -> Unit,
    ) : ReplyInserter {
        override suspend fun insert(text: String): InsertResult {
            delay(random.nextLong(0, 401))
            val result =
                when (random.nextInt(6)) {
                    0 -> InsertResult.NoFocusedField
                    1 -> InsertResult.Failed("fuzz")
                    else -> InsertResult.Inserted
                }
            onResult(result)
            return result
        }
    }

    private fun runSeed(
        seed: Int,
        stats: Stats,
    ) = runTest {
        val random = Random(seed)
        val generator = FakeGenerator()
        val callTimes = mutableListOf<Long>()
        var acceptedInsertAt = -1L
        var successfulInsertTapAt = Long.MAX_VALUE
        var closes = 0
        val inserter =
            RandomInserter(random) { result ->
                if (result == InsertResult.Inserted) {
                    successfulInsertTapAt = acceptedInsertAt
                    stats.insertsOk++
                } else {
                    stats.insertsFailed++
                }
            }
        val panel =
            ReplyPanelState(
                request = PanelRequest(whatsAppChat),
                deps =
                    ReplyPanelDependencies(
                        FakeAssembler(),
                        generator,
                        inserter,
                        FakeFeedbackLog(),
                        FakeNoteStore(),
                        FakeHistory(),
                    ),
                scope = this,
                onClose = { closes++ },
                clipboard = FakeClipboard(),
            )

        fun TestScope.recordCalls() {
            while (callTimes.size < generator.calls) callTimes += currentTime
        }

        fun TestScope.tap(
            mayCall: Boolean,
            action: () -> Unit,
        ) {
            val before = generator.calls
            action()
            runCurrent()
            recordCalls()
            val added = generator.calls - before
            stats.taps++
            if (mayCall) stats.callTaps++
            assertWithMessage("seed $seed: calls added by one tap at t=$currentTime")
                .that(added)
                .isAtMost(if (mayCall) 1 else 0)
        }

        tap(mayCall = true) { panel.start() }
        repeat(TAPS_PER_SEED) {
            val gapBefore = generator.calls
            advanceTimeBy(random.nextLong(0, 301))
            runCurrent()
            assertWithMessage("seed $seed: a call started between taps at t=$currentTime")
                .that(generator.calls)
                .isEqualTo(gapBefore)
            if (successfulInsertTapAt != Long.MAX_VALUE) stats.tapsAfterInsertOk++
            when (random.nextInt(4)) {
                0 -> {
                    tap(mayCall = true) { panel.regenerate() }
                }

                1 -> {
                    val chip = QuickInstruction.entries.random(random)
                    tap(mayCall = true) { panel.toggleChip(chip) }
                }

                2 -> {
                    val index = random.nextInt(4)
                    tap(mayCall = false) {
                        val wasInserting = panel.state.value.inserting
                        panel.insert(index)
                        if (!wasInserting && panel.state.value.inserting) acceptedInsertAt = currentTime
                    }
                }

                else -> {
                    val index = random.nextInt(3)
                    tap(mayCall = false) { panel.copy(index) }
                }
            }
        }
        advanceUntilIdle()
        recordCalls()

        val late = callTimes.filter { it > successfulInsertTapAt }
        assertWithMessage("seed $seed: calls after the successful insert tap at t=$successfulInsertTapAt")
            .that(late)
            .isEmpty()
        assertWithMessage("seed $seed: closes").that(closes).isAtMost(1)
        stats.seeds++
        stats.calls += generator.calls
        panel.dispose()
    }

    private companion object {
        const val SEEDS = 2_000
        const val TAPS_PER_SEED = 30
    }
}
