package com.paragrudani.replyly.panel

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
 * The instruction sent with a generation: the selected chips' prompts in enum order (not tap
 * order), then the typed text. Null when there is neither.
 *
 * Chips first so they are never lost: the whole stays within [MAX_INSTRUCTION_CHARS] (the prompt's
 * cap) by cutting the typed text, never a chip prompt.
 *
 * Invariants (checked by `QuickInstructionTest`): null exactly when the text is blank and no chip
 * is selected; never longer than [MAX_INSTRUCTION_CHARS]; starts with the selected prompts in enum
 * order; the trimmed text appears whole at the end when it fits.
 */
fun combineInstruction(
    typed: String,
    chips: Set<QuickInstruction>,
): String? {
    val prefix = QuickInstruction.entries.filter { it in chips }.joinToString(" ") { it.prompt }
    val text = typed.trim()
    if (prefix.isEmpty()) return text.take(MAX_INSTRUCTION_CHARS).ifEmpty { null }
    if (text.isEmpty()) return prefix
    val room = MAX_INSTRUCTION_CHARS - prefix.length - 1
    if (room <= 0) return prefix
    val cut = text.takeWithoutSplittingSurrogates(room).trimEnd()
    return if (cut.isEmpty()) prefix else "$prefix $cut"
}

private fun String.takeWithoutSplittingSurrogates(max: Int): String {
    if (length <= max) return this
    val end = if (Character.isHighSurrogate(this[max - 1])) max - 1 else max
    return substring(0, end)
}
