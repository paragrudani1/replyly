package com.paragrudani.replyly.parser.generic

import java.text.Normalizer
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Date-separator text ("Today", "SATURDAY", "21 November 2026", "21/11/2026"...) to a local date.
 *
 * Pure: relative words resolve against the `today` the caller passes (a parser takes it from the
 * snapshot's capture time, never from the wall clock).
 * - "Today"/"Yesterday" (Hindi "आज"/"कल").
 * - A weekday name: the most recent past day with that name, within the last 7 days (today's own
 *   name means a week ago: a separator says "Today" for today).
 * - Day and month names in either order, an optional weekday in front, an optional year. Without
 *   a year: the most recent past occurrence.
 * - Numbers: `d/m/y`, `d.m.y`, `d-m-y` (2- or 4-digit year) and `y-m-d`. Day first, as in Indian
 *   English; month first only when the day-first reading is not a date ("11/21/2026").
 * Month and day names are English and Hindi, from `java.time` plus common spellings. Any Unicode
 * digits count ("२१ नवंबर २०२६"). Every format is unverified on the phone: DEVICE_CHECKLIST.md 13.
 */
object ChatDates {
    /** What a separator says, before it is resolved against a day. */
    sealed interface Phrase {
        fun resolve(today: LocalDate): LocalDate?
    }

    private data class DaysAgo(
        val days: Long,
    ) : Phrase {
        override fun resolve(today: LocalDate): LocalDate = today.minusDays(days)
    }

    private data class Weekday(
        val day: DayOfWeek,
    ) : Phrase {
        override fun resolve(today: LocalDate): LocalDate = (1L..7L).map(today::minusDays).first { it.dayOfWeek == day }
    }

    private data class DayOfMonth(
        val month: Int,
        val day: Int,
        val year: Int?,
    ) : Phrase {
        override fun resolve(today: LocalDate): LocalDate? {
            if (year != null) return dateOrNull(year, month, day)
            // Eight years back always reaches a leap year for 29 February.
            return (today.year downTo today.year - 8)
                .firstNotNullOfOrNull { y -> dateOrNull(y, month, day)?.takeIf { !it.isAfter(today) } }
        }
    }

    private val locales = listOf(Locale.ENGLISH, Locale.forLanguageTag("en-IN"), Locale.forLanguageTag("hi-IN"))
    private val styles = listOf(TextStyle.FULL, TextStyle.SHORT, TextStyle.FULL_STANDALONE, TextStyle.SHORT_STANDALONE)

    private val relative =
        mapOf("today" to 0L, "yesterday" to 1L, normalize("आज") to 0L, normalize("कल") to 1L)

    private val weekdays: Map<String, DayOfWeek> =
        buildMap {
            for (day in DayOfWeek.entries) {
                for (locale in locales) for (style in styles) put(word(day.getDisplayName(style, locale)), day)
            }
        }

    private val months: Map<String, Int> =
        buildMap {
            for (month in Month.entries) {
                for (locale in locales) {
                    for (style in styles) {
                        put(
                            word(month.getDisplayName(style, locale)),
                            month.value,
                        )
                    }
                }
            }
            // Spellings that differ between Android's ICU data and the JDK, or in everyday use.
            listOf(
                "sept" to 9,
                "फरवरी" to 2,
                "सितम्बर" to 9,
                "अक्टूबर" to 10,
                "अक्तूबर" to 10,
                "नवम्बर" to 11,
                "दिसम्बर" to 12,
            ).forEach { (name, value) -> put(word(name), value) }
        }

    private val numeric = Regex("""^(\d{1,4})([./-])(\d{1,2})\2(\d{1,4})$""")
    private val statusDate = Regex("""^(?:sent|received) (?:on )?(.+?) at \d{1,2}:\d{2}""")

    /** What [text] says as a separator, or `null` if it is not a date. */
    fun parse(text: String): Phrase? {
        val s = normalize(text)
        if (s.isEmpty()) return null
        relative[s]?.let { return DaysAgo(it) }
        numeric.matchEntire(s)?.let { return numericDate(it) }
        var tokens =
            s
                .replace(',', ' ')
                .split(' ')
                .filter { it.isNotEmpty() }
                .map(::word)
        if (tokens.size == 1) return weekdays[tokens[0]]?.let(::Weekday)
        if (tokens.isNotEmpty() && tokens[0] in weekdays) tokens = tokens.drop(1)
        if (tokens.size !in 2..3) return null
        val year =
            tokens.getOrNull(2)?.let { y -> if (y.length == 4) y.toIntOrNull() ?: return null else return null }
        val (dayToken, monthToken) =
            when {
                tokens[1] in months -> tokens[0] to tokens[1]
                tokens[0] in months -> tokens[1] to tokens[0]
                else -> return null
            }
        val day = dayToken.takeIf { it.length <= 2 }?.toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        val month = months.getValue(monthToken)
        // The day must exist in some year (31 February never does).
        if (dateOrNull(2024, month, day) == null) return null
        return DayOfMonth(month, day, year).takeIf { year == null || it.resolve(LocalDate.MIN) != null }
    }

    /** [text] as a separator resolved against [today], or `null` if it is not a date. */
    fun resolve(
        text: String,
        today: LocalDate,
    ): LocalDate? = parse(text)?.resolve(today)

    /**
     * The date in the last line of a Telegram cell: "Received today at 6:42 PM", "Sent yesterday
     * at 9:15 PM, Read", "Received Nov 19 at 6:42 PM", "Sent 21.11.25 at 18:02". `null` without one.
     */
    fun fromStatusLine(
        line: String,
        today: LocalDate,
    ): LocalDate? = statusDate.find(normalize(line))?.let { resolve(it.groupValues[1], today) }

    private fun numericDate(match: MatchResult): Phrase? {
        val (a, _, b, c) = match.destructured
        if (a.length == 4) {
            return c
                .takeIf { it.length <= 2 }
                ?.let { DayOfMonth(b.toInt(), it.toInt(), a.toInt()) }
                ?.takeIf { it.resolve(LocalDate.MIN) != null }
        }
        if (c.length != 2 && c.length != 4) return null
        val year = c.toInt().let { if (c.length == 2) 2000 + it else it }
        val dayFirst = DayOfMonth(b.toInt(), a.toInt(), year)
        val monthFirst = DayOfMonth(a.toInt(), b.toInt(), year)
        return listOf(dayFirst, monthFirst).firstOrNull { it.resolve(LocalDate.MIN) != null }
    }

    /** Single spaces, lower case, composed Unicode, ASCII digits. */
    private fun normalize(text: String): String {
        val composed = Normalizer.normalize(ChatText.squash(text), Normalizer.Form.NFC).lowercase(Locale.ROOT)
        return buildString(composed.length) {
            for (c in composed) {
                val digit = if (c.isDigit()) Character.digit(c, 10) else -1
                append(if (digit >= 0) '0' + digit else c)
            }
        }
    }

    /** A name token as matched: normalized, without a trailing abbreviation dot ("Nov.", "नव॰"). */
    private fun word(name: String): String = normalize(name).trimEnd('.', '॰')

    private fun dateOrNull(
        year: Int,
        month: Int,
        day: Int,
    ): LocalDate? =
        try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            null
        }
}
