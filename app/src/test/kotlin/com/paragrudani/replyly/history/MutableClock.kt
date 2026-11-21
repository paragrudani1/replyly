package com.paragrudani.replyly.history

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock the test moves by hand. */
class MutableClock(
    var now: Instant = Instant.parse("2026-11-21T10:00:00Z"),
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneOffset = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    fun advance(duration: Duration) {
        now += duration
    }
}
