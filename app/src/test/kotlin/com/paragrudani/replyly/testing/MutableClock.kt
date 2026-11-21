package com.paragrudani.replyly.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A settable clock for rollover tests. */
class MutableClock(
    var now: Instant,
    private val zone: ZoneId,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }

    fun setLocal(dateTime: LocalDateTime) {
        now = dateTime.atZone(zone).toInstant()
    }

    companion object {
        fun atLocal(
            dateTime: LocalDateTime,
            zone: ZoneId = ZoneId.of("Asia/Kolkata"),
        ): MutableClock = MutableClock(dateTime.atZone(zone).toInstant(), zone)
    }
}
