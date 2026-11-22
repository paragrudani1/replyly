package com.paragrudani.replyly.parser

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.telegram.TelegramParser
import com.paragrudani.replyly.parser.whatsapp.WhatsAppParser
import com.paragrudani.replyly.testing.Fixtures
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Date separators on the synthetic date fixtures (issue #26): every message gets the date of the
 * separator above it, `null` above the first one. Times and senders are covered by the per-app
 * tests; here only (text, date, replies to me) are pinned.
 */
class MessageDatesFixtureTest {
    private val india = ZoneId.of("Asia/Kolkata")
    private val registry = Parsers.registry(india)

    private fun date(
        y: Int,
        m: Int,
        d: Int,
    ) = LocalDate.of(y, m, d)

    private fun report(
        fixture: String,
        parser: (com.paragrudani.replyly.screen.ScreenSnapshot) -> ParseReport = registry::explain,
    ): ParseReport = parser(Fixtures.snapshot(fixture)).also { checkNotNull(it.chat) { it.toString() } }

    private fun ParseReport.dates() = chat!!.messages.map { it.text to it.date }

    // Captured on Wednesday 2 December 2026, 11:30 in India.
    private val waDates =
        listOf(
            // "21 NOVEMBER 2026" at the top of the list.
            "Did the parcel arrive?" to date(2026, 11, 21),
            "Not yet, tomorrow maybe" to date(2026, 11, 21),
            // "THURSDAY": the most recent past Thursday.
            "It came! Thanks" to date(2026, 11, 26),
            // "YESTERDAY".
            "Are we still on for Friday dinner?" to date(2026, 12, 1),
            // "TODAY", inside the first row of the day.
            "Yes, 8 pm works" to date(2026, 12, 2),
            "Book a table for four" to date(2026, 12, 2),
        )

    @Test
    fun `whatsapp - separators at the top and mid-screen, by view ids`() {
        val report = report("whatsapp/synthetic-dates.json")

        assertThat(report.method).isEqualTo(WhatsAppParser.METHOD_VIEW_IDS)
        assertThat(report.dates()).containsExactlyElementsIn(waDates).inOrder()
        assertThat(report.notes).contains(
            "date separators: 4 (2026-11-21, 2026-11-26, 2026-12-01, 2026-12-02), 6 of 6 messages dated, 1 replies to me",
        )
    }

    @Test
    fun `whatsapp - the same separators by geometry when the view ids are gone`() {
        val report = report("whatsapp/synthetic-dates-no_ids.json")

        assertThat(report.method).isEqualTo(WhatsAppParser.METHOD_GEOMETRY)
        assertThat(report.dates()).containsExactlyElementsIn(waDates).inOrder()
    }

    @Test
    fun `whatsapp - a reply that quotes my message replies to me, by view ids and by geometry`() {
        listOf("whatsapp/synthetic-dates.json", "whatsapp/synthetic-dates-no_ids.json").forEach { fixture ->
            val messages = report(fixture).chat!!.messages
            assertThat(messages.filter { it.repliesToMe }.map { it.text }).containsExactly("Yes, 8 pm works")
        }
    }

    @Test
    fun `whatsapp - no separator on screen leaves every date unknown`() {
        val report = report("whatsapp/synthetic-no_separator.json")

        assertThat(report.dates())
            .containsExactly("Are you home?" to null, "Reaching in 10" to null, "ok, bring milk" to null)
            .inOrder()
    }

    @Test
    fun `whatsapp - midnight rollover resolves against the capture day in the phone's zone`() {
        // Captured at 00:05 on 22 November in India, which is still 21 November in UTC.
        val inIndia = report("whatsapp/synthetic-midnight.json")
        assertThat(inIndia.dates())
            .containsExactly(
                // Above the first separator: unknown, although it is surely 21 November.
                "Still up?" to null,
                "Yes, watching the match" to date(2026, 11, 21),
                "Happy birthday!! 🎂" to date(2026, 11, 22),
                "Thank you 😄" to date(2026, 11, 22),
            ).inOrder()

        val inUtc = report("whatsapp/synthetic-midnight.json", WhatsAppParser(ZoneOffset.UTC)::explain)
        assertThat(inUtc.dates().map { it.second })
            .containsExactly(null, date(2026, 11, 20), date(2026, 11, 21), date(2026, 11, 21))
            .inOrder()
    }

    @Test
    fun `whatsapp - a stale TODAY after midnight drops the screen's dates`() {
        // Captured at 00:05 on 22 November in India; the divider drawn on the 21st still says TODAY.
        val report = report("whatsapp/synthetic-stale_today.json")

        assertThat(report.dates())
            .containsExactly("Leaving the party now" to null, "Message when you reach" to null)
            .inOrder()
        assertThat(report.notes).contains(
            "date separators: 1 (2026-11-22), 0 of 2 messages dated, 0 replies to me, dates dropped: stale today",
        )
    }

    @Test
    fun `whatsapp - two TODAY dividers on one screen drop the screen's dates`() {
        // The stale TODAY above, and the new one WhatsApp added for a message after midnight.
        val report = report("whatsapp/synthetic-two_todays.json")

        assertThat(report.dates().map { it.second }).containsExactly(null, null, null)
        assertThat(report.notes.last()).endsWith("dates dropped: separators out of order")
    }

    @Test
    fun `whatsapp - the floating date pill over the same day's divider keeps the dates`() {
        // Captured at 23:45 on 21 November in India; the pill and the divider both say YESTERDAY,
        // with no message between them. Same by view ids and by geometry.
        listOf("whatsapp/synthetic-date_pill.json", "whatsapp/synthetic-date_pill-no_ids.json").forEach { fixture ->
            val report = report(fixture)

            assertThat(report.dates())
                .containsExactly(
                    "Did you book the cab?" to date(2026, 11, 20),
                    "Yes, 6 am pickup" to date(2026, 11, 20),
                    "Cab is here 🚕" to date(2026, 11, 21),
                ).inOrder()
            assertThat(report.notes.last()).isEqualTo(
                "date separators: 3 (2026-11-20, 2026-11-20, 2026-11-21), 3 of 3 messages dated, 0 replies to me",
            )
        }
    }

    @Test
    fun `whatsapp - a hinglish chat with hindi separators`() {
        // Captured on Saturday 21 November 2026 in India.
        assertThat(report("whatsapp/synthetic-dates-hindi.json").dates())
            .containsExactly(
                // "बुधवार" (Wednesday).
                "Kal milte hai na?" to date(2026, 11, 18),
                // "कल" (yesterday).
                "Haan pakka, 7 baje" to date(2026, 11, 20),
                // "आज" (today).
                "Main nikal gaya 🚗" to date(2026, 11, 21),
            ).inOrder()
    }

    @Test
    fun `whatsapp business - day-first numeric separators in a hinglish chat`() {
        val report = report("whatsapp_business/synthetic-dates-numeric.json")

        assertThat(report.chat!!.chatKey.packageName).isEqualTo(TargetApps.WHATSAPP_BUSINESS)
        assertThat(report.dates())
            .containsExactly(
                // "12/11/2026" is 12 November.
                "Order ka status kya hai?" to date(2026, 11, 12),
                "Kal tak dispatch ho jayega ji" to date(2026, 11, 12),
                "Bhaiya abhi tak nahi aaya 😕" to date(2026, 11, 21),
            ).inOrder()
    }

    @Test
    fun `whatsapp - the older fixtures date their messages from their dividers`() {
        assertThat(report("whatsapp/synthetic-one_to_one.json").dates().map { it.second }.distinct())
            .containsExactly(date(2026, 11, 20), date(2026, 11, 21))
            .inOrder()
        // The top bubble of the group sits above the "TODAY" divider of the row below it.
        assertThat(report("whatsapp/synthetic-group.json").dates().first().second).isNull()
    }

    @Test
    fun `telegram - every accessibility status line carries its own date`() {
        // Captured on Sunday 22 November 2026, 11:00 in India.
        val report = report("telegram/synthetic-dates.json")

        assertThat(report.method).isEqualTo(TelegramParser.METHOD_DESCRIPTIONS)
        assertThat(report.dates())
            .containsExactly(
                // "Received 21.11.25 at 6:02 PM": the dd.mm.yy form Telegram uses for other years.
                "Happy birthday Rohan!" to date(2025, 11, 21),
                // "Nov 19 at ...".
                "Did you get the tickets?" to date(2026, 11, 19),
                "Yes, 2 for Saturday" to date(2026, 11, 19),
                // "Sent yesterday at ...".
                "See you at the gate" to date(2026, 11, 21),
                // "Received today at ...".
                "Movie was great 🍿" to date(2026, 11, 22),
            ).inOrder()
        assertThat(report.notes).contains("5 cells dated by their own text")
    }

    @Test
    fun `telegram - a status line without a day takes the date header above it`() {
        assertThat(report("telegram/synthetic-dates-headers_only.json").dates())
            .containsExactly(
                "Top cell above any header" to null,
                // "Saturday".
                "Dinner at 9?" to date(2026, 11, 21),
                // "Today".
                "Done 👍" to date(2026, 11, 22),
            ).inOrder()
    }

    @Test
    fun `telegram - the geometry fallback reads the date header too`() {
        assertThat(report("telegram/synthetic-geometry_fallback.json").dates().map { it.second }.toSet())
            .containsExactly(date(2026, 11, 21))
    }

    @Test
    fun `generic apps never turn a date separator into a message`() {
        val chat = registry.parse(Fixtures.snapshot("generic/synthetic-instagram_dm.json"))!!

        assertThat(chat.messages.map { it.text }).doesNotContain("Today 6:40 PM")
        assertThat(chat.messages.map { it.date }.toSet()).containsExactly(null)
    }
}
