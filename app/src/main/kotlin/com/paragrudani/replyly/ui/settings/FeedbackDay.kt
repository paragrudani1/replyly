package com.paragrudani.replyly.ui.settings

import com.paragrudani.replyly.feedback.FeedbackEntry
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One day of the feedback timeline: [label] is "Today", "Yesterday" or a short date. */
data class FeedbackDay(
    val date: LocalDate,
    val label: String,
    /** Newest first. */
    val entries: List<FeedbackEntry>,
)

/**
 * Groups [entries] by their local date in [zone], newest day first and newest entry first inside
 * a day, whatever order they come in. [today] names the first two days; other days read
 * "Tue, Nov 3", with the year added when it is not [today]'s year.
 */
fun groupFeedbackByDay(
    entries: List<FeedbackEntry>,
    zone: ZoneId,
    today: LocalDate,
    locale: Locale = Locale.getDefault(),
): List<FeedbackDay> {
    val sameYear = DateTimeFormatter.ofPattern("EEE, MMM d", locale)
    val otherYear = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", locale)
    return entries
        .sortedByDescending { it.createdAt }
        .groupBy { it.createdAt.atZone(zone).toLocalDate() }
        .map { (date, dayEntries) ->
            val label =
                when {
                    date == today -> "Today"
                    date == today.minusDays(1) -> "Yesterday"
                    date.year == today.year -> sameYear.format(date)
                    else -> otherYear.format(date)
                }
            FeedbackDay(date, label, dayEntries)
        }
}
