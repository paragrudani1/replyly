package com.paragrudani.replyly.snooze

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.zone.ZoneRulesProvider
import kotlin.random.Random

/**
 * Seeded property test for "Until tomorrow (6 AM)" over random instants and zones, DST included.
 *
 * Invariants, for every now and zone:
 * 1. the result is after now;
 * 2. it is at most 24 h + 1 h after now (a 25-hour night when clocks go back);
 * 3. its local time is 06:00, or, when 06:00 does not exist that day (a gap), it is the first
 *    valid instant after the gap, i.e. the instant the gap ends;
 * 4. there is no earlier 06:00 between now and the result (it is the *next* one).
 */
class SnoozeChoicePropertyTest {
    private val zones: List<ZoneId> =
        // Every zone with transitions plus a few without; sorted so the seed picks the same ones everywhere.
        ZoneRulesProvider.getAvailableZoneIds().sorted().map(ZoneId::of)

    @Test
    fun `next 6 AM holds its invariants`() {
        val random = Random(SEED)
        val from = Instant.parse("2000-01-01T00:00:00Z").epochSecond
        val to = Instant.parse("2040-01-01T00:00:00Z").epochSecond
        repeat(RUNS) {
            val zone = zones[random.nextInt(zones.size)]
            val now = Instant.ofEpochSecond(random.nextLong(from, to), random.nextLong(0, 1_000_000_000))
            check(now, zone)
        }
    }

    @Test
    fun `next 6 AM holds its invariants around every DST transition`() {
        // Instants just before and after real transitions, where the bugs live.
        val random = Random(SEED + 1)
        val start = Instant.parse("2020-01-01T00:00:00Z")
        zones.forEach { zone ->
            var transition = zone.rules.nextTransition(start)
            var count = 0
            while (transition != null && count < 6) {
                repeat(4) {
                    val offsetSeconds = random.nextLong(-30 * 3600L, 30 * 3600L)
                    check(transition.instant.plusSeconds(offsetSeconds), zone)
                }
                transition = zone.rules.nextTransition(transition.instant)
                count++
            }
        }
    }

    private fun check(
        now: Instant,
        zone: ZoneId,
    ) {
        val result = Instant.ofEpochMilli(SnoozeChoice.UNTIL_MORNING.untilMillis(now, zone))
        val what = "now=$now zone=$zone result=$result"
        assertWithMessage("after now: $what").that(result.isAfter(now)).isTrue()
        assertWithMessage("within 25 h: $what")
            .that(result.toEpochMilli() - now.toEpochMilli())
            .isAtMost(25 * 3_600_000L)
        val local = result.atZone(zone).toLocalDateTime()
        if (local.toLocalTime() != SIX) {
            // Only allowed when 06:00 that day falls in a gap, and then the result ends the gap.
            val wanted = local.toLocalDate().atTime(SIX)
            val gapped = zone.rules.getValidOffsets(wanted).isEmpty()
            val previousDay = local.toLocalDate().minusDays(1).atTime(SIX)
            val gappedBefore = zone.rules.getValidOffsets(previousDay).isEmpty()
            assertWithMessage("06:00 local or the end of a gap: $what").that(gapped || gappedBefore).isTrue()
            val transition = zone.rules.previousTransition(result.plusMillis(1))
            assertWithMessage("first valid instant after the gap: $what")
                .that(transition?.instant)
                .isEqualTo(result)
        }
        // No 06:00 strictly between now and the result.
        var day = now.atZone(zone).toLocalDate()
        while (true) {
            val wanted = day.atTime(SIX)
            if (zone.rules.getValidOffsets(wanted).isNotEmpty()) {
                val instant = wanted.atZone(zone).toInstant()
                if (instant.isAfter(result) || instant == result) break
                assertWithMessage("no earlier 06:00 after now: $what").that(instant.isAfter(now)).isFalse()
            }
            day = day.plusDays(1)
        }
    }

    private companion object {
        const val SEED = 56L
        const val RUNS = 5_000
        val SIX: LocalTime = LocalTime.of(6, 0)
    }
}
