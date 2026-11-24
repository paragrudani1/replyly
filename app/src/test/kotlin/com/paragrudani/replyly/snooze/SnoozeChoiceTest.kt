package com.paragrudani.replyly.snooze

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class SnoozeChoiceTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(
        local: String,
        zone: ZoneId = kolkata,
    ): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun SnoozeChoice.until(
        local: String,
        zone: ZoneId = kolkata,
    ) = untilMillis(at(local, zone), zone)

    @Test
    fun `ten minutes and one hour are added to now`() {
        val now = at("2026-11-24T21:15")
        assertThat(SnoozeChoice.TEN_MINUTES.untilMillis(now, kolkata)).isEqualTo(now.toEpochMilli() + 600_000)
        assertThat(SnoozeChoice.ONE_HOUR.untilMillis(now, kolkata)).isEqualTo(now.toEpochMilli() + 3_600_000)
    }

    @Test
    fun `until tomorrow is the next local 6 AM`() {
        assertThat(SnoozeChoice.UNTIL_MORNING.until("2026-11-24T21:15"))
            .isEqualTo(at("2026-11-25T06:00").toEpochMilli())
    }

    @Test
    fun `before 6 AM it is 6 AM today`() {
        assertThat(SnoozeChoice.UNTIL_MORNING.until("2026-11-25T01:30"))
            .isEqualTo(at("2026-11-25T06:00").toEpochMilli())
    }

    @Test
    fun `at exactly 6 AM it is 6 AM tomorrow`() {
        assertThat(SnoozeChoice.UNTIL_MORNING.until("2026-11-25T06:00"))
            .isEqualTo(at("2026-11-26T06:00").toEpochMilli())
    }

    @Test
    fun `a DST gap over 6 AM gives the first valid instant after it`() {
        // Real gaps rarely cover 06:00, so check the rule on New York's 02:00 -> 03:00 jump.
        val zone = ZoneId.of("America/New_York")
        val now = LocalDateTime.parse("2026-03-08T00:30").atZone(zone).toInstant()
        val result = nextLocalTime(now, zone, hour = 2)
        // 02:00 does not exist that night (02:00 -> 03:00): the first valid instant after the gap is 03:00 EDT.
        assertThat(result).isEqualTo(
            LocalDateTime
                .parse("2026-03-08T03:00")
                .atZone(zone)
                .toInstant()
                .toEpochMilli(),
        )
    }

    @Test
    fun `across a DST change 6 AM stays local`() {
        // Berlin goes back an hour on 2026-10-25 at 03:00: the night is 25 hours long.
        assertThat(SnoozeChoice.UNTIL_MORNING.until("2026-10-24T23:00", berlin))
            .isEqualTo(at("2026-10-25T06:00", berlin).toEpochMilli())
    }

    @Test
    fun `until I turn it back on never ends`() {
        assertThat(SnoozeChoice.UNTIL_TURNED_ON.until("2026-11-24T21:15")).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `toasts name the end and the shake`() {
        val shake = " Shake your phone to bring it back."
        val now = at("2026-11-24T21:15")

        fun toast(
            choice: SnoozeChoice,
            is24: Boolean = false,
        ) = SnoozeText.toast(choice, choice.untilMillis(now, kolkata), kolkata, is24)

        assertThat(toast(SnoozeChoice.TEN_MINUTES)).isEqualTo("Snoozed for 10 minutes.$shake")
        assertThat(toast(SnoozeChoice.ONE_HOUR)).isEqualTo("Snoozed for 1 hour.$shake")
        assertThat(toast(SnoozeChoice.UNTIL_MORNING)).isEqualTo("Snoozed until 6:00 AM.$shake")
        assertThat(toast(SnoozeChoice.UNTIL_MORNING, is24 = true)).isEqualTo("Snoozed until 06:00.$shake")
        assertThat(toast(SnoozeChoice.UNTIL_TURNED_ON)).isEqualTo("Snoozed until you turn it back on.$shake")
    }

    @Test
    fun `the status line names the end time in the phone's format`() {
        val until = at("2026-11-24T21:25").toEpochMilli()
        assertThat(SnoozeText.status(until, kolkata, is24Hour = false)).isEqualTo("Bubble snoozed until 9:25 PM")
        assertThat(SnoozeText.status(until, kolkata, is24Hour = true)).isEqualTo("Bubble snoozed until 21:25")
        assertThat(SnoozeText.status(Long.MAX_VALUE, kolkata, is24Hour = false))
            .isEqualTo("Bubble snoozed until you turn it back on")
    }

    @Test
    fun `log names are the enum values`() {
        assertThat(SnoozeChoice.entries.map { it.name })
            .containsExactly("TEN_MINUTES", "ONE_HOUR", "UNTIL_MORNING", "UNTIL_TURNED_ON")
            .inOrder()
    }
}
