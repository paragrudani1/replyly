package com.paragrudani.replyly.cost

import java.time.LocalDate
import java.time.YearMonth

/**
 * The persisted counters behind [CostGuard]. [day] and [month] are the local date and month the
 * counts belong to; [rolledTo] starts a fresh day at local midnight and a fresh month on the 1st.
 */
data class CostCounters(
    val day: LocalDate,
    val requests: Int,
    val month: YearMonth,
    val promptTokens: Long,
    val completionTokens: Long,
) {
    /**
     * The counters as seen on [today]. Only moves forward: a clock set back (or a trip across
     * time zones) keeps the current counts instead of granting a fresh allowance. A stored day more
     * than one day ahead (a clock that was wrong in the future) moves back to [today] with its
     * counts kept, so the counter does not stay frozen until that future date.
     */
    fun rolledTo(today: LocalDate): CostCounters {
        val thisMonth = YearMonth.from(today)
        if (day.isAfter(today.plusDays(1))) return copy(day = today, month = thisMonth)
        val newMonth = thisMonth.isAfter(month)
        val newDay = today.isAfter(day)
        if (!newDay && !newMonth) return this
        return copy(
            day = if (newDay) today else day,
            requests = if (newDay) 0 else requests,
            month = if (newMonth) thisMonth else month,
            promptTokens = if (newMonth) 0 else promptTokens,
            completionTokens = if (newMonth) 0 else completionTokens,
        )
    }

    fun toStatus(dailyRequestLimit: Int): CostStatus =
        CostStatus(requests, dailyRequestLimit, promptTokens, completionTokens)

    companion object {
        fun empty(today: LocalDate): CostCounters = CostCounters(today, 0, YearMonth.from(today), 0, 0)
    }
}
