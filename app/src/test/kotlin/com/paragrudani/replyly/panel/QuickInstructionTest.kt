package com.paragrudani.replyly.panel

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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

    @Test
    fun `the field's room is what the chips leave of the limit`() {
        assertThat(instructionRoom(emptySet())).isEqualTo(MAX_INSTRUCTION_CHARS)
        val all = QuickInstruction.entries.toSet()
        val prefix = QuickInstruction.entries.joinToString(" ") { it.prompt }
        assertThat(instructionRoom(all)).isEqualTo(MAX_INSTRUCTION_CHARS - prefix.length - 1)
        assertThat(instructionRoom(setOf(QuickInstruction.Shorter)))
            .isEqualTo(MAX_INSTRUCTION_CHARS - QuickInstruction.Shorter.prompt.length - 1)
    }

    @Test
    fun `text that fills the room is sent whole with every chip on`() {
        val all = QuickInstruction.entries.toSet()
        val typed = "y".repeat(instructionRoom(all))

        assertThat(combineInstruction(typed, all)).endsWith(" $typed")
        assertThat(combineInstruction(typed, all)).hasLength(MAX_INSTRUCTION_CHARS)
    }

    @Test
    fun `cutting never splits an emoji, a skin tone, a ZWJ family, a flag or a Hindi vowel sign`() {
        val cases =
            listOf("😀", "👍🏽", "👨‍👩‍👧", "🇮🇳", "❤️", "हि")
        cases.forEach { emoji ->
            val text = "ab$emoji"
            for (max in 2 until text.length) {
                assertWithMessage("$emoji cut at $max").that(text.takeWholeCharacters(max)).isEqualTo("ab")
            }
            assertThat(text.takeWholeCharacters(text.length)).isEqualTo(text)
        }
        assertThat("abc".takeWholeCharacters(0)).isEmpty()
    }

    /**
     * Seeded property test. Text is built from tokens that are each one user-perceived character
     * (ASCII, Hindi with a vowel sign, emoji with skin tones, ZWJ families, flags, variation
     * selectors), so the true cut points are known. Invariants, for any typed text and chip set:
     * 1. [combineInstruction] is null exactly when the text is blank and no chip is selected;
     * 2. it is never longer than [MAX_INSTRUCTION_CHARS];
     * 3. it starts with every selected chip's prompt, in enum order, and contains no other chip's prompt;
     * 4. when the text fits [instructionRoom], the trimmed text appears whole at the end;
     * 5. the field's cut ([takeWholeCharacters] at [instructionRoom]) is a prefix of the text, ends
     *    on a true token boundary, and is the longest such prefix within the room (no character lost
     *    that would have fitted);
     * 6. what the field keeps is sent whole: the combined instruction ends with its trimmed text.
     */
    @Test
    fun `combined instructions and the field's cut keep their invariants`() {
        val random = Random(17)
        repeat(5_000) {
            val tokens = randomTokens(random)
            val text = tokens.joinToString("")
            val chips =
                QuickInstruction.entries
                    .filter { random.nextBoolean() }
                    .shuffled(random)
                    .toSet()
            val room = instructionRoom(chips)
            checkCombined(text, chips, room)

            val field = text.takeWholeCharacters(room)
            val boundaries = tokens.runningFold(0) { end, token -> end + token.length }
            assertThat(text).startsWith(field)
            assertWithMessage("cut of %s at %s", text, room).that(boundaries).contains(field.length)
            assertThat(field.length).isEqualTo(boundaries.last { it <= room })
            val trimmed = field.trim()
            val sent = combineInstruction(field, chips)
            if (trimmed.isNotEmpty()) assertThat(sent).endsWith(trimmed)
        }
    }

    private fun checkCombined(
        text: String,
        chips: Set<QuickInstruction>,
        room: Int,
    ) {
        val combined = combineInstruction(text, chips)
        if (text.isBlank() && chips.isEmpty()) {
            assertThat(combined).isNull()
            return
        }
        checkNotNull(combined)
        assertThat(combined.length).isAtMost(MAX_INSTRUCTION_CHARS)
        val prefix = QuickInstruction.entries.filter { it in chips }.joinToString(" ") { it.prompt }
        assertThat(combined).startsWith(prefix)
        QuickInstruction.entries.filter { it !in chips }.forEach { assertThat(combined).doesNotContain(it.prompt) }
        val trimmed = text.trim()
        if (trimmed.isNotEmpty() && trimmed.length <= room) assertThat(combined).endsWith(trimmed)
    }

    private fun randomTokens(random: Random): List<String> {
        val count = if (random.nextInt(3) == 0) random.nextInt(150, 320) else random.nextInt(0, 40)
        return List(count) { TOKENS[random.nextInt(TOKENS.size)] }
    }

    private companion object {
        /** Each one user-perceived character; no two of them merge when placed side by side. */
        val TOKENS =
            listOf(" ", " ", "a", "b", "x", "y", ".", ",", "!", "?", "\n", "हि", "😀", "👍🏽", "👨‍👩‍👧", "🇮🇳", "❤️")
    }
}
