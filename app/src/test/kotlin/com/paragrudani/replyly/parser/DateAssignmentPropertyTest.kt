package com.paragrudani.replyly.parser

import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.parser.generic.ChatAssembler
import com.paragrudani.replyly.parser.generic.ChatDates
import com.paragrudani.replyly.parser.generic.bubble
import com.paragrudani.replyly.parser.generic.centered
import com.paragrudani.replyly.parser.generic.chatScreen
import com.paragrudani.replyly.parser.generic.row
import com.paragrudani.replyly.parser.telegram.TelegramParser
import com.paragrudani.replyly.parser.whatsapp.WhatsAppParser
import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.screen.ScreenNode
import com.paragrudani.replyly.screen.ScreenSnapshot
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/**
 * Seeded property test for date assignment in the parsers. A simulator writes a true chat over
 * several days, then shows a random window of it as a screen: a date separator above the first
 * message of each day (the one of the window's top day is sometimes scrolled away), in a random
 * form ("TODAY", "Thursday", "21 NOVEMBER 2026", "21/11/2026", Hindi...), and messages whose text
 * is sometimes a date word ("Today", "Monday") on a bubble's side. The capture time is a random
 * moment of the capture day in India, after the last message of that day. With `unreadable`, a
 * quarter of the separators use a format [ChatDates] cannot read ("21th November", "21 Nov '26").
 * With `nearToday`, the capture is instead 10 minutes before to 5 minutes after the last message
 * on screen dated the capture day, as for a screen drawn before midnight and read after it.
 *
 * Invariants, for every screen:
 * 1. The dates of the on-screen messages never decrease from top to bottom, and (WhatsApp, with
 *    every separator readable) once a message has a date, every message below it has one.
 * 2. Every message below a readable separator gets that separator's (true) date.
 * 3. A message with no separator above it, or whose nearest separator above is unreadable, gets
 *    no date (history then marks it approx): an unreadable separator ends the date above it. For
 *    a Telegram cell, its own status line counts as a separator.
 * 4. A date word in a bubble stays a message: the message count is the true one.
 * 5. (`nearToday`) A message dated the capture day whose time is more than
 *    [ChatAssembler.STALE_TODAY_MINUTES] after the capture drops every date on the screen (a stale
 *    "Today"); up to that many minutes after it, the dates stay. Both sides are seen.
 *
 * Routes: WhatsApp by view ids, WhatsApp by geometry (no ids), Telegram cell descriptions.
 */
class DateAssignmentPropertyTest {
    private val india = ZoneId.of("Asia/Kolkata")

    private class TrueMessage(
        val isMe: Boolean,
        val text: String,
        val date: LocalDate,
        val time: LocalTime,
    )

    private sealed interface Item

    private class Separator(
        val text: String,
        val readable: Boolean = true,
    ) : Item

    private class Message(
        val truth: TrueMessage,
        /** The date the parser must give it: the separator's above it on screen, or null. */
        val expected: LocalDate?,
        /** Telegram only: the status line names the day. */
        val ownDate: Boolean = false,
    ) : Item

    private class Screen(
        val today: LocalDate,
        val items: List<Item>,
        /** The time of the chat's last message when it is from [today]: the capture comes after it. */
        val lastTimeToday: LocalTime?,
        /**
         * `nearToday`: the capture's minutes after the last message on screen dated [today]
         * (negative: before it); `null` for a capture after the chat's last message.
         */
        val nearTodayOffset: Int? = null,
    ) {
        val messages: List<Message> get() = items.filterIsInstance<Message>()

        /** The time of the last message on screen that the parser must date [today]. */
        val lastShownToday: LocalTime? get() = messages.filter { it.expected == today }.maxOfOrNull { it.truth.time }
    }

    private val dateWords = listOf("Today", "Yesterday", "Monday", "21 November", "kal", "ok", "Friday?")

    private fun separatorText(
        random: Random,
        date: LocalDate,
        today: LocalDate,
    ): String {
        val ago = today.toEpochDay() - date.toEpochDay()
        val hindi = random.nextDouble() < 0.15
        val locale = if (hindi) Locale.forLanguageTag("hi-IN") else Locale.ENGLISH
        val upper = random.nextBoolean()
        val text =
            when {
                ago == 0L -> {
                    if (hindi) "आज" else "Today"
                }

                ago == 1L -> {
                    if (hindi) "कल" else "Yesterday"
                }

                ago < 7 -> {
                    date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                }

                else -> {
                    // WhatsApp leaves the year out only within this year.
                    when (random.nextInt(if (date.year == today.year) 4 else 3)) {
                        0 -> DateTimeFormatter.ofPattern("d MMMM yyyy", locale).format(date)
                        1 -> DateTimeFormatter.ofPattern("dd/MM/yyyy").format(date)
                        2 -> DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH).format(date)
                        else -> DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(date)
                    }
                }
            }
        return if (upper) text.uppercase(Locale.ENGLISH) else text
    }

    /** A separator text that [ChatDates] cannot read, for [date]. */
    private fun unreadableText(
        random: Random,
        date: LocalDate,
    ): String {
        val text =
            when (random.nextInt(3)) {
                0 -> "${date.dayOfMonth}th " + date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                1 -> DateTimeFormatter.ofPattern("d MMM ''yy", Locale.ENGLISH).format(date)
                else -> "📅 " + DateTimeFormatter.ofPattern("dd/MM/yyyy").format(date)
            }
        check(ChatDates.parse(text) == null) { "readable: $text" }
        return text
    }

    /** A true chat over several days and a window of at most [maxItems] screen items. */
    private fun screen(
        random: Random,
        maxItems: Int,
        telegram: Boolean,
        unreadable: Boolean = false,
        nearToday: Boolean = false,
    ): Screen {
        var date = LocalDate.of(2026, 1, 1).plusDays(random.nextLong(700))
        var time = LocalTime.of(8, 0)
        val chat =
            List(random.nextInt(maxItems, 40)) {
                // Some messages start a new day, some days are skipped (more than a week too).
                if (random.nextDouble() < 0.3) {
                    date =
                        date.plusDays(if (random.nextDouble() < 0.8) random.nextLong(1, 3) else random.nextLong(3, 12))
                    time = LocalTime.of(random.nextInt(0, 12), random.nextInt(60))
                } else {
                    time = minOf(time.plusMinutes(random.nextLong(0, 40)), LocalTime.of(23, 59))
                }
                val text =
                    if (random.nextDouble() <
                        0.2
                    ) {
                        dateWords.random(random)
                    } else {
                        "message ${random.nextInt(1000)}"
                    }
                TrueMessage(random.nextDouble() < 0.4, text, date, time)
            }
        val today = chat.last().date.plusDays(if (random.nextDouble() < 0.7) 0 else random.nextLong(1, 3))
        val from = random.nextInt(chat.size)
        val items = mutableListOf<Item>()
        var shownDate: LocalDate? = null
        for (k in from until chat.size) {
            val m = chat[k]
            val firstOfDay = k == 0 || chat[k - 1].date != m.date
            // The top day's separator may sit above the screen.
            if (firstOfDay && (k > from || random.nextBoolean())) {
                if (unreadable && random.nextDouble() < 0.25) {
                    items += Separator(unreadableText(random, m.date), readable = false)
                    shownDate = null
                } else {
                    items += Separator(separatorText(random, m.date, today))
                    shownDate = m.date
                }
            }
            val ownDate = telegram && random.nextDouble() < 0.5
            items += Message(m, if (ownDate) m.date else shownDate, ownDate)
            if (items.size >= maxItems) break
        }
        val offset = if (nearToday) random.nextInt(-10, 6) else null
        return Screen(today, items, chat.last().time.takeIf { chat.last().date == today }, offset)
    }

    /** The capture instant: a random moment of the screen's day in India, after its last message. */
    private fun capturedAt(
        random: Random,
        screen: Screen,
    ): java.time.Instant {
        val near = screen.lastShownToday?.takeIf { screen.nearTodayOffset != null }
        val minute =
            if (near != null) {
                (near.toSecondOfDay() / 60 + screen.nearTodayOffset!!).coerceIn(0, 24 * 60 - 1)
            } else {
                val earliest = screen.lastTimeToday?.toSecondOfDay()?.div(60) ?: 0
                random.nextInt(earliest, 24 * 60)
            }
        return screen.today
            .atTime(minute / 60, minute % 60)
            .atZone(india)
            .toInstant()
    }

    private fun clock(t: LocalTime) = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(t).lowercase()

    // ---- WhatsApp by view ids

    private fun waIds(
        screen: Screen,
        random: Random,
    ): ScreenSnapshot {
        val pkg = TargetApps.WHATSAPP
        var y = 270
        val rows = mutableListOf<ScreenNode>()
        var pendingDivider: String? = null

        fun tv(
            b: Bounds,
            text: String,
            id: String,
        ) = ScreenNode(className = "android.widget.TextView", text = text, viewId = "$pkg:id/$id", bounds = b)
        for (item in screen.items) {
            when (item) {
                is Separator -> {
                    // Its own row, or the top of the next message's row.
                    if (random.nextBoolean()) {
                        pendingDivider = item.text
                    } else {
                        rows +=
                            ScreenNode(
                                className = "android.view.ViewGroup",
                                bounds = Bounds(0, y, 1080, y + 86),
                                children =
                                    listOf(
                                        tv(Bounds(400, y + 8, 680, y + 78), item.text, "conversation_row_date_divider"),
                                    ),
                            )
                        y += 86
                    }
                }

                is Message -> {
                    val top = y
                    val kids = mutableListOf<ScreenNode>()
                    pendingDivider?.let {
                        kids += tv(Bounds(400, y + 6, 680, y + 76), it, "conversation_row_date_divider")
                        y += 90
                    }
                    pendingDivider = null
                    val (l, r) = if (item.truth.isMe) 600 to 1040 else 40 to 520
                    val bubbleKids =
                        mutableListOf(
                            tv(Bounds(l + 30, y + 15, r - 30, y + 71), item.truth.text, "message_text"),
                            tv(Bounds(r - 200, y + 75, r - 80, y + 115), clock(item.truth.time), "date"),
                        )
                    if (item.truth.isMe) {
                        bubbleKids +=
                            ScreenNode(
                                className = "android.widget.ImageView",
                                contentDescription = "Read",
                                viewId = "$pkg:id/status",
                                bounds = Bounds(r - 75, y + 75, r - 30, y + 115),
                            )
                    }
                    kids +=
                        ScreenNode(
                            className = "android.widget.LinearLayout",
                            bounds = Bounds(l, y, r, y + 125),
                            children = bubbleKids,
                        )
                    y += 135
                    rows +=
                        ScreenNode(
                            className = "android.view.ViewGroup",
                            bounds = Bounds(0, top, 1080, y),
                            children = kids,
                        )
                }
            }
        }
        check(y <= 2052) { "screen overflows: $y" }
        val title =
            ScreenNode(
                className = "android.widget.TextView",
                text = "Asha",
                viewId = "$pkg:id/conversation_contact_name",
                bounds = Bounds(220, 128, 700, 190),
            )
        val list =
            ScreenNode(
                className = "android.widget.ListView",
                viewId = "android:id/list",
                bounds = Bounds(0, 257, 1080, 2052),
                scrollable = true,
                children = rows,
            )
        val input =
            ScreenNode(
                className = "android.widget.EditText",
                viewId = "$pkg:id/entry",
                hint = "Message",
                bounds = Bounds(140, 2075, 760, 2190),
                editable = true,
                focused = true,
            )
        return ScreenSnapshot(
            packageName = pkg,
            capturedAt = capturedAt(random, screen),
            screenWidth = 1080,
            screenHeight = 2340,
            root = ScreenNode(bounds = Bounds(0, 0, 1080, 2340), children = listOf(title, list, input)),
        )
    }

    // ---- WhatsApp without ids (geometry)

    private fun waGeometry(
        screen: Screen,
        random: Random,
    ): ScreenSnapshot {
        val rows =
            screen.items.map { item ->
                when (item) {
                    is Separator -> {
                        row(centered(item.text))
                    }

                    is Message -> {
                        if (item.truth.isMe) {
                            row(bubble(600, 1040, item.truth.text, time = clock(item.truth.time), tick = true))
                        } else {
                            row(bubble(40, 520, item.truth.text, time = clock(item.truth.time)))
                        }
                    }
                }
            }
        return chatScreen(
            TargetApps.WHATSAPP,
            "Asha",
            "online",
            rows,
        ).copy(capturedAt = capturedAt(random, screen))
    }

    // ---- Telegram cells

    private fun telegram(
        screen: Screen,
        random: Random,
    ): ScreenSnapshot {
        var y = 262
        val cells =
            screen.items.map { item ->
                val node =
                    when (item) {
                        is Separator -> {
                            ScreenNode(
                                className = "android.view.View",
                                text = item.text,
                                bounds =
                                    Bounds(
                                        0,
                                        y,
                                        1080,
                                        y + 90,
                                    ),
                            )
                        }

                        is Message -> {
                            val t = item.truth
                            val day =
                                if (item.ownDate) {
                                    when (screen.today.toEpochDay() - t.date.toEpochDay()) {
                                        0L -> {
                                            "today "
                                        }

                                        1L -> {
                                            "yesterday "
                                        }

                                        else -> {
                                            DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH).format(t.date) +
                                                " "
                                        }
                                    }.let {
                                        if (t.date.year !=
                                            screen.today.year
                                        ) {
                                            DateTimeFormatter.ofPattern("dd.MM.yy").format(t.date) + " "
                                        } else {
                                            it
                                        }
                                    }
                                } else {
                                    ""
                                }
                            val status =
                                if (t.isMe) {
                                    "Sent ${day}at ${clock(
                                        t.time,
                                    )}, Read"
                                } else {
                                    "Received ${day}at ${clock(t.time)}"
                                }
                            ScreenNode(
                                className = "android.view.ViewGroup",
                                contentDescription = "${t.text}\n$status",
                                bounds =
                                    Bounds(
                                        0,
                                        y,
                                        1080,
                                        y + 116,
                                    ),
                            )
                        }
                    }
                y = node.bounds.bottom
                node
            }
        check(y <= 2060) { "screen overflows: $y" }
        val header =
            ScreenNode(
                bounds = Bounds(0, 110, 1080, 257),
                children =
                    listOf(
                        ScreenNode(className = "android.view.View", text = "Asha", bounds = Bounds(260, 130, 850, 190)),
                        ScreenNode(
                            className = "android.view.View",
                            text = "online",
                            bounds = Bounds(260, 192, 850, 240),
                        ),
                    ),
            )
        val list =
            ScreenNode(
                className = "androidx.recyclerview.widget.RecyclerView",
                bounds = Bounds(0, 257, 1080, 2060),
                scrollable = true,
                children = cells,
            )
        val input =
            ScreenNode(
                className = "android.widget.EditText",
                hint = "Message",
                bounds = Bounds(130, 2075, 820, 2195),
                editable = true,
                focused = true,
            )
        return ScreenSnapshot(
            packageName = TargetApps.TELEGRAM,
            capturedAt = capturedAt(random, screen),
            screenWidth = 1080,
            screenHeight = 2340,
            root = ScreenNode(bounds = Bounds(0, 0, 1080, 2340), children = listOf(header, list, input)),
        )
    }

    private fun check(
        route: String,
        seeds: LongRange,
        maxItems: Int,
        telegram: Boolean = false,
        unreadable: Boolean = false,
        nearToday: Boolean = false,
        render: (Screen, Random) -> ScreenSnapshot,
        parser: ChatParser,
    ) {
        var checked = 0
        var dropped = 0
        var keptNear = 0
        for (seed in seeds) {
            val random = Random(seed)
            val screen = screen(random, maxItems, telegram, unreadable, nearToday)
            val snapshot = render(screen, random)
            val chat = parser.parse(snapshot)
            val what = "$route seed $seed"
            // Invariant 5: a "Today" message more than the tolerance after the capture is stale.
            val captured = snapshot.capturedAt.atZone(india).toLocalTime()
            val limit = captured.plusMinutes(ChatAssembler.STALE_TODAY_MINUTES)
            val stale =
                screen.lastShownToday?.let { last -> !limit.isBefore(captured) && last.isAfter(limit) } == true
            if (nearToday && screen.lastShownToday != null) {
                if (stale) {
                    dropped++
                } else {
                    keptNear++
                }
            }
            val expected = screen.messages.map { if (stale) null else it.expected }
            assertWithMessage("$what: not read as a chat").that(chat).isNotNull()
            val dates = chat!!.messages.map { it.date }
            // Invariant 4.
            assertWithMessage("$what: message count")
                .that(chat.messages.map { it.text })
                .containsExactlyElementsIn(screen.messages.map { it.truth.text })
                .inOrder()
            // Invariants 2 and 3.
            assertWithMessage(
                "$what: dates",
            ).that(dates).containsExactlyElementsIn(expected).inOrder()
            // Invariant 1.
            val firstDated = dates.indexOfFirst { it != null }
            if (firstDated >= 0) {
                val below = dates.drop(firstDated)
                // A Telegram cell dated by its own line may sit above an undated one, and an
                // unreadable separator ends the date above it.
                val allReadable = screen.items.none { it is Separator && !it.readable }
                if (!telegram && allReadable) {
                    assertWithMessage(
                        "$what: a dated message above an undated one",
                    ).that(below).doesNotContain(null)
                }
                val known = below.filterNotNull()
                assertWithMessage("$what: dates decrease")
                    .that(
                        known.zipWithNext().all { (a, b) ->
                            !b.isBefore(a)
                        },
                    ).isTrue()
            }
            checked++
        }
        println(
            "DateAssignmentPropertyTest $route: $checked screens" +
                if (nearToday) ", $dropped stale (dates dropped), $keptNear kept within the tolerance" else "",
        )
        if (nearToday) {
            assertWithMessage("$route: both sides of the tolerance").that(minOf(dropped, keptNear)).isGreaterThan(0)
        }
    }

    @Test
    fun `whatsapp by view ids keeps the date invariants`() =
        check("whatsapp:view-ids", 1L..400L, maxItems = 11, render = ::waIds, parser = WhatsAppParser(india))

    @Test
    fun `whatsapp by geometry keeps the date invariants`() =
        check("whatsapp:geometry", 1001L..1400L, maxItems = 11, render = ::waGeometry, parser = WhatsAppParser(india))

    @Test
    fun `an unreadable separator ends the date above it on every route`() {
        check(
            "whatsapp:view-ids unreadable",
            3001L..3400L,
            maxItems = 11,
            unreadable = true,
            render = ::waIds,
            parser = WhatsAppParser(india),
        )
        check(
            "whatsapp:geometry unreadable",
            4001L..4400L,
            maxItems = 11,
            unreadable = true,
            render = ::waGeometry,
            parser = WhatsAppParser(india),
        )
        check(
            "telegram unreadable",
            5001L..5400L,
            maxItems = 14,
            telegram = true,
            unreadable = true,
            render = ::telegram,
            parser = TelegramParser(india),
        )
    }

    @Test
    fun `a capture just before or after a Today message drops the dates only past the tolerance`() {
        check(
            "whatsapp:view-ids near today",
            6001L..6400L,
            11,
            nearToday = true,
            render = ::waIds,
            parser = WhatsAppParser(india),
        )
        check(
            "whatsapp:geometry near today",
            7001L..7400L,
            11,
            nearToday = true,
            render = ::waGeometry,
            parser = WhatsAppParser(india),
        )
        check(
            "telegram near today",
            8001L..8400L,
            14,
            telegram = true,
            nearToday = true,
            render = ::telegram,
            parser = TelegramParser(india),
        )
    }

    @Test
    fun `telegram cells keep the date invariants`() =
        check(
            "telegram",
            2001L..2400L,
            maxItems = 14,
            telegram = true,
            render = ::telegram,
            parser = TelegramParser(india),
        )
}
