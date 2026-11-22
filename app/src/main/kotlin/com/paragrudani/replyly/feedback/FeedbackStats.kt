package com.paragrudani.replyly.feedback

/** What the stats need from one feedback entry. */
data class FeedbackStatsRow(
    val id: Long,
    val promptVersion: Int,
    /** Null for entries from before prompt v2: each counts as its own panel. */
    val panelId: String?,
    /** Text of the inserted suggestion, or null if nothing was inserted. */
    val insertedText: String?,
    /** The message the user sent afterwards, when history saw it. */
    val finalSentText: String?,
)

/** Suggestion quality for one prompt version. */
data class PromptVersionStats(
    val promptVersion: Int,
    /** Panel opens that showed suggestions. */
    val panels: Int,
    /** Of [panels], how many ended with a card inserted. */
    val panelsWithInsert: Int,
    /** Inserts whose sent message is known, so their edit amount could be measured. */
    val editsMeasured: Int,
    /** Mean normalized edit distance (0 = sent as inserted, 1 = fully rewritten); null without any. */
    val averageEdit: Double?,
) {
    /** [panelsWithInsert] / [panels], or null when there are no panels. */
    val insertRate: Double? get() = if (panels == 0) null else panelsWithInsert.toDouble() / panels
}

/** Insert rate and edit amount per prompt version (issue #26). Pure. */
object FeedbackStats {
    /** Longer texts are cut to this before the edit distance, which is quadratic. */
    const val MAX_EDIT_CHARS = 2_000

    /**
     * One entry per prompt version present, oldest version first.
     * - Insert rate: a panel is every entry with the same panel id (Regenerate and chips add
     *   entries to the same panel); it counts as inserted if any of its entries was inserted.
     * - Edit amount: the mean of [normalizedEditDistance] between the inserted suggestion and the
     *   message sent, over the entries where both are known.
     */
    fun compute(rows: List<FeedbackStatsRow>): List<PromptVersionStats> =
        rows
            .groupBy { it.promptVersion }
            .toSortedMap()
            .map { (version, entries) ->
                val panels = entries.groupBy { it.panelId ?: "entry-${it.id}" }.values
                val edits =
                    entries.mapNotNull { row ->
                        val inserted = row.insertedText ?: return@mapNotNull null
                        val sent = row.finalSentText ?: return@mapNotNull null
                        normalizedEditDistance(inserted, sent)
                    }
                PromptVersionStats(
                    promptVersion = version,
                    panels = panels.size,
                    panelsWithInsert = panels.count { panel -> panel.any { it.insertedText != null } },
                    editsMeasured = edits.size,
                    averageEdit = if (edits.isEmpty()) null else edits.average(),
                )
            }

    /**
     * Levenshtein distance over Unicode code points, divided by the longer text's length: 0 for
     * equal texts (also both empty), 1 when nothing is shared. Leading and trailing spaces are ignored.
     */
    fun normalizedEditDistance(
        a: String,
        b: String,
    ): Double {
        val x =
            a
                .trim()
                .codePoints()
                .limit(MAX_EDIT_CHARS.toLong())
                .toArray()
        val y =
            b
                .trim()
                .codePoints()
                .limit(MAX_EDIT_CHARS.toLong())
                .toArray()
        val longest = maxOf(x.size, y.size)
        if (longest == 0) return 0.0
        var previous = IntArray(y.size + 1) { it }
        var current = IntArray(y.size + 1)
        for (i in 1..x.size) {
            current[0] = i
            for (j in 1..y.size) {
                val substitution = previous[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[y.size].toDouble() / longest
    }
}
