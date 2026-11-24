package com.paragrudani.replyly.snooze

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The four options of the snooze card (#56). Each turns "now" into a snoozed-until time in epoch
 * ms ([untilMillis]); [Long.MAX_VALUE] means "until I turn it back on". The enum names are the
 * log values (`snooze start choice=ONE_HOUR`).
 */
enum class SnoozeChoice {
    TEN_MINUTES,
    ONE_HOUR,

    /** "Until tomorrow (6 AM)": the next local 06:00 (today if now is before 06:00). */
    UNTIL_MORNING,
    UNTIL_TURNED_ON,
    ;

    fun untilMillis(
        now: Instant,
        zone: ZoneId,
    ): Long =
        when (this) {
            TEN_MINUTES -> now.plus(Duration.ofMinutes(10)).toEpochMilli()
            ONE_HOUR -> now.plus(Duration.ofHours(1)).toEpochMilli()
            UNTIL_MORNING -> nextLocalTime(now, zone, MORNING_HOUR)
            UNTIL_TURNED_ON -> Long.MAX_VALUE
        }

    companion object {
        const val MORNING_HOUR = 6
    }
}

/**
 * The first instant after [now] whose local time in [zone] is [hour]:00. When that time does not
 * exist on a day (a DST gap), the first valid instant after the gap, which is the transition's
 * instant (`atZone` would instead shift the time by the gap's length).
 */
internal fun nextLocalTime(
    now: Instant,
    zone: ZoneId,
    hour: Int,
): Long {
    val wanted = LocalTime.of(hour, 0)
    var day = now.atZone(zone).toLocalDate()
    while (true) {
        val local = day.atTime(wanted)
        val instant =
            if (zone.rules.getValidOffsets(local).isEmpty()) {
                // In a gap: the transition that created it is the first valid instant after it.
                zone.rules.getTransition(local).instant
            } else {
                // In an overlap (clocks went back), the earlier of the two 06:00s.
                local.atZone(zone).toInstant()
            }
        if (instant.isAfter(now)) return instant.toEpochMilli()
        day = day.plusDays(1)
    }
}

/** What the toast and the Settings card say about a snooze (#56). English only, like the app. */
object SnoozeText {
    const val SHAKE_HINT = "Shake your phone to bring it back."
    const val BACK = "Replyly is back"

    fun toast(
        choice: SnoozeChoice,
        untilMillis: Long,
        zone: ZoneId,
        is24Hour: Boolean,
    ): String {
        val head =
            when (choice) {
                SnoozeChoice.TEN_MINUTES -> "Snoozed for 10 minutes."
                SnoozeChoice.ONE_HOUR -> "Snoozed for 1 hour."
                SnoozeChoice.UNTIL_MORNING -> "Snoozed until ${time(untilMillis, zone, is24Hour)}."
                SnoozeChoice.UNTIL_TURNED_ON -> "Snoozed until you turn it back on."
            }
        return "$head $SHAKE_HINT"
    }

    /** The Settings card's line: "Bubble snoozed until 6:00 AM" / "… until you turn it back on". */
    fun status(
        untilMillis: Long,
        zone: ZoneId,
        is24Hour: Boolean,
    ): String =
        if (untilMillis == Long.MAX_VALUE) {
            "Bubble snoozed until you turn it back on"
        } else {
            "Bubble snoozed until ${time(untilMillis, zone, is24Hour)}"
        }

    /** "6:00 AM" or "06:00", like the phone's 12/24-hour setting. */
    fun time(
        millis: Long,
        zone: ZoneId,
        is24Hour: Boolean,
    ): String {
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        return DateTimeFormatter
            .ofPattern(pattern, Locale.US)
            .format(Instant.ofEpochMilli(millis).atZone(zone))
    }
}
