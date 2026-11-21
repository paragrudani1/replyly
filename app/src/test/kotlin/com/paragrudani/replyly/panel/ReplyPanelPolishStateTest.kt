package com.paragrudani.replyly.panel

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.insert.InsertResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Quick-chips, long-press copy and the "one call per tap" rule (issue #17, items 4 and 5). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplyPanelPolishStateTest {
    private val assembler = FakeAssembler()
    private val inserter = FakeInserter()
    private val feedback = FakeFeedbackLog()
    private val clipboard = FakeClipboard()
    private var closed = 0

    private fun TestScope.panel(generator: FakeGenerator = FakeGenerator()): ReplyPanelState =
        ReplyPanelState(
            request = PanelRequest(whatsAppChat),
            deps = ReplyPanelDependencies(assembler, generator, inserter, feedback, FakeNoteStore(), FakeHistory()),
            scope = this,
            onClose = { closed++ },
            clipboard = clipboard,
        )

    private suspend fun TestScope.opened(generator: FakeGenerator = FakeGenerator()): ReplyPanelState =
        panel(generator).also {
            it.start()
            advanceUntilIdle()
        }

    // Quick-chips

    @Test
    fun `a chip tap selects it and makes exactly one call with its instruction`() =
        runTest {
            val generator = FakeGenerator()
            val panel = opened(generator)

            panel.toggleChip(QuickInstruction.Shorter)
            runCurrent()
            assertThat(panel.state.value.chips).containsExactly(QuickInstruction.Shorter)
            assertThat(panel.state.value.suggestions).isEqualTo(SuggestionsUi.Loading)
            advanceUntilIdle()

            assertThat(generator.calls).isEqualTo(2)
            assertThat(generator.requests.last().instruction).isEqualTo("Make the replies shorter.")
            assertThat(panel.state.value.suggestions).isInstanceOf(SuggestionsUi.Ready::class.java)
        }

    @Test
    fun `chips stack, combine with the typed text, and stay for the next regenerate`() =
        runTest {
            val generator = FakeGenerator()
            val panel = opened(generator)

            panel.toggleChip(QuickInstruction.InEnglish)
            advanceUntilIdle()
            panel.onInstructionChange("say I'll call at 6")
            panel.toggleChip(QuickInstruction.Shorter)
            advanceUntilIdle()
            assertThat(generator.requests.last().instruction)
                .isEqualTo("Make the replies shorter. Write them in English. say I'll call at 6")

            panel.regenerate()
            advanceUntilIdle()
            assertThat(generator.requests.last().instruction)
                .isEqualTo("Make the replies shorter. Write them in English. say I'll call at 6")
            assertThat(generator.calls).isEqualTo(4)
        }

    @Test
    fun `tapping a selected chip unselects it and regenerates without it`() =
        runTest {
            val generator = FakeGenerator()
            val panel = opened(generator)
            panel.toggleChip(QuickInstruction.MoreFormal)
            advanceUntilIdle()

            panel.toggleChip(QuickInstruction.MoreFormal)
            advanceUntilIdle()

            assertThat(panel.state.value.chips).isEmpty()
            assertThat(generator.requests.map { it.instruction })
                .containsExactly(null, "Make them more formal.", null)
                .inOrder()
        }

    @Test
    fun `chip taps while a call runs are ignored and change nothing`() =
        runTest {
            val generator = FakeGenerator()
            val panel = panel(generator)
            panel.start()
            runCurrent()

            panel.toggleChip(QuickInstruction.Shorter)
            panel.toggleChip(QuickInstruction.AddEmoji)
            advanceUntilIdle()

            assertThat(generator.calls).isEqualTo(1)
            assertThat(panel.state.value.chips).isEmpty()
        }

    @Test
    fun `rapid taps on chips and regenerate during the out-and-in animation make one call each round`() =
        runTest {
            val generator = FakeGenerator()
            val panel = opened(generator)

            // Cards animate out while the call runs: taps in that window are ignored.
            panel.regenerate()
            repeat(5) {
                advanceTimeBy(FakeGenerator.GENERATE_MILLIS / 10)
                panel.regenerate()
                panel.toggleChip(QuickInstruction.Shorter)
            }
            advanceUntilIdle()
            assertThat(generator.calls).isEqualTo(2)
            assertThat(panel.state.value.chips).isEmpty()

            // Cards animate back in after the result: the next tap is a new round, one call.
            panel.toggleChip(QuickInstruction.Shorter)
            panel.toggleChip(QuickInstruction.Shorter)
            advanceUntilIdle()
            assertThat(generator.calls).isEqualTo(3)
            assertThat(panel.state.value.chips).containsExactly(QuickInstruction.Shorter)
        }

    @Test
    fun `the generation counter tells a first load from a regenerate`() =
        runTest {
            val panel = panel()
            panel.start()
            runCurrent()
            assertThat(panel.state.value.generation).isEqualTo(1)
            advanceUntilIdle()

            panel.toggleChip(QuickInstruction.Shorter)
            runCurrent()
            assertThat(panel.state.value.generation).isEqualTo(2)
        }

    @Test
    fun `chips are ignored after a successful insert`() =
        runTest {
            val generator = FakeGenerator()
            val panel = opened(generator)
            panel.insert(0)
            advanceUntilIdle()

            panel.toggleChip(QuickInstruction.Shorter)
            panel.regenerate()
            advanceUntilIdle()

            assertThat(generator.calls).isEqualTo(1)
        }

    // Long-press copy

    @Test
    fun `long-press copies the card's text, shows Copied, and neither inserts nor marks nor closes`() =
        runTest {
            val panel = opened()

            panel.copy(2)
            runCurrent()

            assertThat(clipboard.copied).containsExactly("Kitne baje?")
            assertThat(panel.state.value.copied).isTrue()
            assertThat(inserter.inserted).isEmpty()
            assertThat(feedback.marked).isEmpty()
            assertThat(closed).isEqualTo(0)
        }

    @Test
    fun `the Copied confirmation goes away by itself, and a second copy restarts its timer`() =
        runTest {
            val panel = opened()

            panel.copy(0)
            advanceTimeBy(COPIED_VISIBLE_MILLIS - 200)
            panel.copy(1)
            advanceTimeBy(COPIED_VISIBLE_MILLIS - 200)
            assertThat(panel.state.value.copied).isTrue()

            advanceTimeBy(400)
            assertThat(panel.state.value.copied).isFalse()
            assertThat(clipboard.copied).hasSize(2)
        }

    @Test
    fun `a clipboard that fails or throws shows a fixed notice instead of Copied`() =
        runTest {
            val panel = opened()

            clipboard.result = false
            panel.copy(0)
            runCurrent()
            assertThat(panel.state.value.copied).isFalse()
            assertThat(panel.state.value.notice).isEqualTo(COPY_FAILED)

            clipboard.throwOnCopy = true
            panel.copy(0)
            runCurrent()
            assertThat(panel.state.value.copied).isFalse()
            assertThat(panel.state.value.notice).isEqualTo(COPY_FAILED)
        }

    @Test
    fun `copy does nothing while loading, while inserting, or after the panel finished`() =
        runTest {
            val panel = panel()
            panel.start()
            runCurrent()
            panel.copy(0)
            advanceUntilIdle()
            assertThat(clipboard.copied).isEmpty()

            panel.insert(0)
            advanceUntilIdle()
            panel.copy(0)
            advanceUntilIdle()
            assertThat(clipboard.copied).isEmpty()
        }

    @Test
    fun `copy with an index out of range does nothing`() =
        runTest {
            val panel = opened()
            panel.copy(7)
            advanceUntilIdle()
            assertThat(clipboard.copied).isEmpty()
        }

    // Insert keeps its order while the panel animates closed

    @Test
    fun `insert marks the tapped card, then closes once, and further taps are ignored`() =
        runTest {
            val panel = opened()

            panel.insert(1)
            runCurrent()
            assertThat(panel.state.value.insertingIndex).isEqualTo(1)
            advanceUntilIdle()
            assertThat(closed).isEqualTo(1)
            assertThat(panel.state.value.finished).isTrue()

            // The panel animates out after onClose: taps in that window must not insert again.
            panel.insert(0)
            panel.insert(2)
            advanceUntilIdle()
            assertThat(inserter.inserted).containsExactly("Kal nahi ho payega yaar")
            assertThat(feedback.marked).containsExactly(41L to 1)
            assertThat(closed).isEqualTo(1)
        }

    @Test
    fun `a failed insert clears the highlight so the user can pick again`() =
        runTest {
            inserter.result = InsertResult.NoFocusedField
            val panel = opened()

            panel.insert(0)
            advanceUntilIdle()

            assertThat(panel.state.value.insertingIndex).isNull()
            assertThat(panel.state.value.finished).isFalse()
        }
}

class FakeClipboard(
    var result: Boolean = true,
) : SuggestionClipboard {
    val copied = mutableListOf<String>()
    var throwOnCopy = false

    override fun copy(text: String): Boolean {
        if (throwOnCopy) throw SecurityException("not allowed")
        if (result) copied += text
        return result
    }
}
