package com.paragrudani.replyly.feedback

/** What the stats need from one feedback entry. */
data class FeedbackStatsRow(
    val id: Long,
    val promptVersion: Int,
    /** Null for entries from before prompt v2: their panels are estimated ([FeedbackStats.V1_PANEL_GAP]). */
    val panelId: String?,
    /** Text of the inserted suggestion, or null if nothing was inserted. */
    val insertedText: String?,
    /** The message the user sent afterwards, when history saw it. */
    val finalSentText: String?,
    /** Encoded chat key, for estimating the panels of rows without [panelId]; null: its own panel. */
    val chatKey: String? = null,
    /** Epoch millis, for estimating the panels of rows without [panelId]. */
    val createdAtMillis: Long = 0,
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
    /** True when some entries had no panel id (before prompt v2), so [panels] is an estimate. */
    val panelsEstimated: Boolean = false,
) {
    /** [panelsWithInsert] / [panels], or null when there are no panels. */
    val insertRate: Double? get() = if (panels == 0) null else panelsWithInsert.toDouble() / panels
}

/** Insert rate and edit amount per prompt version (issue #26). Pure. */
object FeedbackStats {
    /** Longer texts are cut to this before the edit distance, which is quadratic. */
    const val MAX_EDIT_CHARS = 2_000

    /**
     * Entries from before prompt v2 have no panel id: an entry of the same chat created within
     * this time of the previous one counts as the same panel (a Regenerate or a chip tap).
     */
    const val V1_PANEL_GAP_MILLIS: Long = 2 * 60 * 1000

    /**
     * One entry per prompt version present, oldest version first.
     * - Insert rate: a panel is every entry with the same panel id (Regenerate and chips add
     *   entries to the same panel); it counts as inserted if any of its entries was inserted.
     *   Entries without a panel id are grouped by [estimatedPanels].
     * - Edit amount: the mean of [normalizedEditDistance] between the inserted suggestion and the
     *   message sent, over the entries where both are known.
     */
    fun compute(rows: List<FeedbackStatsRow>): List<PromptVersionStats> =
        rows
            .groupBy { it.promptVersion }
            .toSortedMap()
            .map { (version, entries) ->
                val (withId, withoutId) = entries.partition { it.panelId != null }
                val panels = withId.groupBy { it.panelId }.values + estimatedPanels(withoutId)
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
                    panelsEstimated = withoutId.isNotEmpty(),
                )
            }

    /**
     * Panels for entries without a panel id: per chat, oldest first, an entry starts a new panel
     * unless it was created within [V1_PANEL_GAP_MILLIS] of the chat's previous entry. Entries
     * without a chat key are each their own panel.
     */
    fun estimatedPanels(rows: List<FeedbackStatsRow>): List<List<FeedbackStatsRow>> {
        val (keyed, unkeyed) = rows.partition { it.chatKey != null }
        val panels = mutableListOf<List<FeedbackStatsRow>>()
        keyed.groupBy { it.chatKey }.values.forEach { chat ->
            var panel = mutableListOf<FeedbackStatsRow>()
            for (row in chat.sortedWith(compareBy({ it.createdAtMillis }, { it.id }))) {
                val previous = panel.lastOrNull()
                if (previous != null && row.createdAtMillis - previous.createdAtMillis > V1_PANEL_GAP_MILLIS) {
                    panels += panel
                    panel = mutableListOf()
                }
                panel += row
            }
            if (panel.isNotEmpty()) panels += panel
        }
        return panels + unkeyed.map { listOf(it) }
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
