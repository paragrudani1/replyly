package com.paragrudani.replyly.panel

import java.text.BreakIterator
import java.util.Locale

/**
 * The quick-chips under the instruction field. [label] is what the chip shows; [prompt] is the
 * fixed sentence sent to the model.
 *
 * Tapping a chip toggles it and regenerates once (one LLM call per tap). Selected chips stay
 * selected, so a later Regenerate keeps them, until the user taps them again.
 */
enum class QuickInstruction(
    val label: String,
    val prompt: String,
) {
    Shorter("Shorter", "Make the replies shorter."),
    MoreFormal("More formal", "Make them more formal."),
    InEnglish("In English", "Write them in English."),
    AddEmoji("Add emoji", "Add a fitting emoji."),
}

/**
 * How many characters the instruction field may hold with [chips] selected: what is left of
 * [MAX_INSTRUCTION_CHARS] after the chips' prompts and a space. The field stops there, so
 * [combineInstruction] never has to cut typed text (decision #5: nothing is cut silently).
 */
fun instructionRoom(chips: Set<QuickInstruction>): Int {
    val prefix = chipPrefix(chips)
    return if (prefix.isEmpty()) MAX_INSTRUCTION_CHARS else (MAX_INSTRUCTION_CHARS - prefix.length - 1).coerceAtLeast(0)
}

/** The counter under the instruction field shows from this many characters before the limit. */
const val INSTRUCTION_COUNTER_MARGIN = 30

/**
 * The instruction sent with a generation: the selected chips' prompts in enum order (not tap
 * order), then the typed text. Null when there is neither.
 *
 * Chips first so they are never lost. The field holds at most [instructionRoom] characters, so the
 * typed text normally fits whole; if it does not, it is cut (at a character boundary, never inside
 * an emoji), never a chip prompt.
 *
 * Invariants (checked by `QuickInstructionTest`): null exactly when the text is blank and no chip
 * is selected; never longer than [MAX_INSTRUCTION_CHARS]; starts with the selected prompts in enum
 * order; the trimmed text appears whole at the end when it fits [instructionRoom].
 */
fun combineInstruction(
    typed: String,
    chips: Set<QuickInstruction>,
): String? {
    val prefix = chipPrefix(chips)
    val text = typed.trim()
    if (prefix.isEmpty()) return text.takeWholeCharacters(MAX_INSTRUCTION_CHARS).trimEnd().ifEmpty { null }
    if (text.isEmpty()) return prefix
    val room = instructionRoom(chips)
    if (room <= 0) return prefix
    val cut = text.takeWholeCharacters(room).trimEnd()
    return if (cut.isEmpty()) prefix else "$prefix $cut"
}

private fun chipPrefix(chips: Set<QuickInstruction>): String =
    QuickInstruction.entries.filter { it in chips }.joinToString(" ") { it.prompt }

/**
 * At most [max] UTF-16 units of this string, cut only between user-perceived characters (grapheme
 * clusters): never inside a surrogate pair, an emoji with a skin tone or variation selector, a
 * ZWJ sequence, a flag, or a letter with its combining marks.
 */
fun String.takeWholeCharacters(max: Int): String {
    if (length <= max) return this
    if (max <= 0) return ""
    val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT)
    boundaries.setText(this)
    val end = if (boundaries.isBoundary(max)) max else boundaries.preceding(max)
    return substring(0, end.coerceAtLeast(0))
}
