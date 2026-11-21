package com.paragrudani.replyly.panel

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class QuickInstructionTest {
    @Test
    fun `no chips and no text is no instruction`() {
        assertThat(combineInstruction("", emptySet())).isNull()
        assertThat(combineInstruction("   ", emptySet())).isNull()
    }

    @Test
    fun `typed text alone is sent trimmed, as before`() {
        assertThat(combineInstruction("  say I'll call at 6 ", emptySet())).isEqualTo("say I'll call at 6")
    }

    @Test
    fun `chips alone are sent in a fixed order, whatever order they were tapped in`() {
        val chips = linkedSetOf(QuickInstruction.InEnglish, QuickInstruction.Shorter)

        assertThat(combineInstruction("", chips))
            .isEqualTo("Make the replies shorter. Write them in English.")
    }

    @Test
    fun `chips come first, then the typed text`() {
        assertThat(combineInstruction(" say I'll call at 6", setOf(QuickInstruction.AddEmoji)))
            .isEqualTo("Add a fitting emoji. say I'll call at 6")
    }

    @Test
    fun `the typed text is cut so the whole fits the limit, chip phrases are never cut`() {
        val all = QuickInstruction.entries.toSet()
        val combined = combineInstruction("x".repeat(MAX_INSTRUCTION_CHARS), all)!!

        assertThat(combined).hasLength(MAX_INSTRUCTION_CHARS)
        assertThat(combined).startsWith(QuickInstruction.entries.joinToString(" ") { it.prompt })
    }

    @Test
    fun `chip labels are short sentence-case words, prompts are full sentences`() {
        assertThat(QuickInstruction.entries.map { it.label })
            .containsExactly("Shorter", "More formal", "In English", "Add emoji")
            .inOrder()
        QuickInstruction.entries.forEach { assertThat(it.prompt).endsWith(".") }
    }

    /**
     * Seeded property test. Invariants, for any typed text and any chip set:
     * 1. the result is null exactly when the text is blank and no chip is selected;
     * 2. it is never longer than [MAX_INSTRUCTION_CHARS];
     * 3. it starts with every selected chip's prompt, in enum order, and contains no other chip's prompt
     *    unless the typed text does;
     * 4. when the text fits, the trimmed text appears whole at the end.
     */
    @Test
    fun `combined instructions keep their invariants`() {
        val random = Random(17)
        repeat(2_000) {
            val text = randomText(random)
            val chips =
                QuickInstruction.entries
                    .filter { random.nextBoolean() }
                    .shuffled(random)
                    .toSet()
            val combined = combineInstruction(text, chips)

            if (text.isBlank() && chips.isEmpty()) {
                assertThat(combined).isNull()
                return@repeat
            }
            checkNotNull(combined)
            assertThat(combined.length).isAtMost(MAX_INSTRUCTION_CHARS)
            val prefix = QuickInstruction.entries.filter { it in chips }.joinToString(" ") { it.prompt }
            assertThat(combined).startsWith(prefix)
            QuickInstruction.entries.filter { it !in chips && !text.contains(it.prompt) }.forEach {
                assertThat(combined).doesNotContain(it.prompt)
            }
            val trimmed = text.trim()
            val room = MAX_INSTRUCTION_CHARS - prefix.length - (if (prefix.isEmpty()) 0 else 1)
            if (trimmed.isNotEmpty() && trimmed.length <= room) assertThat(combined).endsWith(trimmed)
        }
    }

    private fun randomText(random: Random): String {
        val alphabet = " abcxyz.,!?हि😀\n\t"
        val length = if (random.nextInt(5) == 0) random.nextInt(250, 320) else random.nextInt(0, 40)
        return String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] })
    }
}
