package com.paragrudani.replyly.llm

import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Turns an X post's time as shown ([com.paragrudani.replyly.parser.x.XPost.timeShown]) into an
 * approximate age against the prompt's Now (issue #30 §4, the v2 time context). Pure.
 *
 * Understood (English UI, as DEVICE_CHECKLIST 15 requires):
 * - relative: `now`, `45s`, `12m`, `2h`, `3d`;
 * - a date: `Nov 19`, `19 Nov` (this year, or last year when that day is still ahead),
 *   `Nov 19, 2025`, `19 Nov 2025`;
 * - the focal post's line: `6:42 PM · 21 Nov 26`, `6:42 PM · Nov 21, 2026`, `18:42 · 21 Nov 26`,
 *   with anything after a further `·` (the view count) ignored.
 *
 * Anything else, an impossible date or a time after Now gives `null`: the prompt then shows the
 * time exactly as shown, with no age.
 */
object XPostAge {
    /**
     * "about 2 hours ago", "3 days ago, Thu 19 Nov", "about 3 hours ago, Sun 22 Nov 8:05 am", or
     * `null` when [shown] is not a known format.
     */
    fun describe(
        shown: String,
        now: ZonedDateTime,
        is24Hour: Boolean = false,
    ): String? {
        val text = shown.trim()
        if (text.isEmpty()) return null
        if (text.equals("now", ignoreCase = true)) return "just now"
        RELATIVE.matchEntire(text)?.let { return relative(it.groupValues[1].toLong(), it.groupValues[2], now) }
        ABSOLUTE.matchEntire(text)?.let { return absolute(it, now, is24Hour) }
        return dateOnly(text, now.toLocalDate())?.let { date -> dayAge(date, now.toLocalDate()) }
    }

    private val RELATIVE = Regex("""(\d{1,3})\s?([smhd])""")
    private val ABSOLUTE = Regex("""(\d{1,2}):(\d{2})\s*([AaPp][Mm])?\s*·\s*([^·]+?)\s*(?:·.*)?""")
    private val MONTH_DAY = Regex("""([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:,?\s+(\d{2}|\d{4}))?""")
    private val DAY_MONTH = Regex("""(\d{1,2})\s+([A-Za-z]{3,9})\.?(?:,?\s+(\d{2}|\d{4}))?""")

    private val DAY_LABEL = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val DAY_LABEL_YEAR = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
    private val TIME_12H = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val TIME_24H = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    private fun relative(
        amount: Long,
        unit: String,
        now: ZonedDateTime,
    ): String =
        when (unit) {
            "s" -> "under a minute ago"
            "m" -> if (amount == 1L) "1 minute ago" else "$amount minutes ago"
            "h" -> if (amount == 1L) "about 1 hour ago" else "about $amount hours ago"
            else -> dayAge(now.toLocalDate().minusDays(amount), now.toLocalDate())
        }

    private fun absolute(
        match: MatchResult,
        now: ZonedDateTime,
        is24Hour: Boolean,
    ): String? {
        val (h, m, meridiem, datePart) = match.destructured
        val time = time(h.toInt(), m.toInt(), meridiem) ?: return null
        val date = dateOnly(datePart, now.toLocalDate(), requireYear = true) ?: return null
        val posted = LocalDateTime.of(date, time)
        val minutes = Duration.between(posted, now.toLocalDateTime()).toMinutes()
        if (minutes < 0) return null
        val clock =
            if (is24Hour) TIME_24H.format(time) else TIME_12H.format(time).lowercase(Locale.ENGLISH)
        val label = "${dayLabel(date, now.toLocalDate())} $clock"
        return when {
            minutes < 1 -> "just now, $label"
            minutes < 60 -> "${plural(minutes, "minute")} ago, $label"
            minutes < 24 * 60 -> "about ${plural(minutes / 60, "hour")} ago, $label"
            else -> "${dayWords(ChronoUnit.DAYS.between(date, now.toLocalDate()))}, $label"
        }
    }

    private fun time(
        hour: Int,
        minute: Int,
        meridiem: String,
    ): LocalTime? {
        if (minute > 59) return null
        val h =
            when (meridiem.lowercase()) {
                "" -> hour.takeIf { it in 0..23 }
                "am" -> hour.takeIf { it in 1..12 }?.let { it % 12 }
                else -> hour.takeIf { it in 1..12 }?.let { it % 12 + 12 }
            } ?: return null
        return LocalTime.of(h, minute)
    }

    /** A date as shown; without a year it is this year's, or last year's when still ahead. */
    private fun dateOnly(
        text: String,
        today: LocalDate,
        requireYear: Boolean = false,
    ): LocalDate? {
        val (monthText, dayText, yearText) =
            MONTH_DAY.matchEntire(text)?.destructured?.let { (mo, d, y) -> Triple(mo, d, y) }
                ?: DAY_MONTH.matchEntire(text)?.destructured?.let { (d, mo, y) -> Triple(mo, d, y) }
                ?: return null
        val month = month(monthText) ?: return null
        if (requireYear && yearText.isEmpty()) return null
        return try {
            if (yearText.isEmpty()) {
                val thisYear = LocalDate.of(today.year, month, dayText.toInt())
                if (thisYear.isAfter(today)) LocalDate.of(today.year - 1, month, dayText.toInt()) else thisYear
            } else {
                val year = yearText.toInt().let { if (yearText.length == 2) 2000 + it else it }
                LocalDate.of(year, month, dayText.toInt()).takeUnless { it.isAfter(today) }
            }
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun month(text: String): Month? {
        val lower = text.lowercase(Locale.ENGLISH)
        return Month.entries.firstOrNull { month ->
            val name = month.name.lowercase(Locale.ENGLISH)
            (lower.length >= 3 && name.startsWith(lower)) || (lower == "sept" && month == Month.SEPTEMBER)
        }
    }

    private fun dayAge(
        date: LocalDate,
        today: LocalDate,
    ): String = "${dayWords(ChronoUnit.DAYS.between(date, today))}, ${dayLabel(date, today)}"

    private fun dayWords(days: Long): String =
        when {
            days <= 0 -> "today"
            days == 1L -> "yesterday"
            days < 14 -> "$days days ago"
            days < 60 -> "${days / 7} weeks ago"
            days < 365 -> "about ${days / 30} months ago"
            else -> "over a year ago"
        }

    private fun dayLabel(
        date: LocalDate,
        today: LocalDate,
    ): String = if (date.year == today.year) DAY_LABEL.format(date) else DAY_LABEL_YEAR.format(date)

    private fun plural(
        n: Long,
        unit: String,
    ) = if (n == 1L) "1 $unit" else "$n ${unit}s"
}
