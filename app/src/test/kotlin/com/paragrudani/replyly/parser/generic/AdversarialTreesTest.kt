package com.paragrudani.replyly.parser.generic

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParseReport
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.parser.telegram.TelegramParser
import com.paragrudani.replyly.parser.whatsapp.WhatsAppParser
import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.screen.ScreenNode
import com.paragrudani.replyly.screen.ScreenSnapshot
import org.junit.Test
import java.time.Instant

/**
 * The trees from the ticket #2 review that broke the first parser version (A to J), plus the
 * ones that held up and must keep holding. Screens are 1080 x 2340, message list [0,257][1080,2052].
 */
class AdversarialTreesTest {
    private fun me(
        text: String,
        time: String?,
    ) = ChatMessage(sender = null, text = text, timeShown = time, isMe = true)

    private fun them(
        sender: String?,
        text: String,
        time: String?,
    ) = ChatMessage(sender = sender, text = text, timeShown = time, isMe = false)

    private fun ParseReport.chatOrFail(): ParsedChat = checkNotNull(chat) { toString() }

    private fun tv(
        b: Bounds,
        text: String,
        id: String? = null,
    ) = ScreenNode(className = "android.widget.TextView", text = text, viewId = id, bounds = b)

    private fun screen(
        pkg: String,
        header: List<ScreenNode>,
        listChildren: List<ScreenNode>,
        listTop: Int = 257,
    ): ScreenSnapshot {
        val inputTop = 2075
        val list =
            ScreenNode(
                className = "androidx.recyclerview.widget.RecyclerView",
                viewId = if (pkg.startsWith("com.whatsapp")) "$pkg:id/list" else null,
                bounds = Bounds(0, listTop, 1080, inputTop - 23),
                scrollable = true,
                children = listChildren,
            )
        val input =
            ScreenNode(
                className = "android.widget.EditText",
                viewId = if (pkg.startsWith("com.whatsapp")) "$pkg:id/entry" else null,
                text = "draft: call me at 6:30",
                bounds = Bounds(140, inputTop, 760, inputTop + 115),
                editable = true,
                focused = true,
            )
        return ScreenSnapshot(
            packageName = pkg,
            capturedAt = Instant.parse("2026-11-21T10:15:30Z"),
            screenWidth = 1080,
            screenHeight = 2340,
            // Header after the list in tree order, like a toolbar drawn over a list that starts at y = 0.
            root = ScreenNode(bounds = Bounds(0, 0, 1080, 2340), children = listOf(list) + header + input),
        )
    }

    // ---- WhatsApp with view ids

    private val wa = TargetApps.WHATSAPP

    /** A WhatsApp row with ids: optional [label], bubble from [l] to [r]. */
    private fun waRow(
        top: Int,
        l: Int,
        r: Int,
        text: String,
        time: String,
        label: String? = null,
        tick: Boolean = false,
    ): ScreenNode {
        val kids = mutableListOf<ScreenNode>()
        var y = top + 10
        if (label != null) {
            kids += tv(Bounds(l + 30, y, l + 300, y + 45), label, "$wa:id/name_in_group_tv")
            y += 50
        }
        kids += tv(Bounds(l + 30, y, r - 30, y + 52), text, "$wa:id/message_text")
        y += 56
        kids += tv(Bounds(r - 200, y, r - 80, y + 40), time, "$wa:id/date")
        if (tick) {
            kids +=
                ScreenNode(
                    className = "android.widget.ImageView",
                    viewId = "$wa:id/status",
                    contentDescription = "Read",
                    bounds = Bounds(r - 75, y, r - 30, y + 40),
                )
        }
        y += 50
        val bubble =
            ScreenNode(className = "android.widget.LinearLayout", bounds = Bounds(l, top, r, y), children = kids)
        return ScreenNode(
            className = "android.view.ViewGroup",
            bounds = Bounds(0, top, 1080, y + 6),
            children = listOf(bubble),
        )
    }

    private fun waHeader(
        title: String?,
        subtitle: String?,
    ) = listOfNotNull(
        title?.let { tv(Bounds(220, 128, 700, 190), it, "$wa:id/conversation_contact_name") },
        subtitle?.let { tv(Bounds(220, 190, 700, 240), it, "$wa:id/conversation_contact_status") },
    )

    @Test
    fun `A - a group whose subtitle says someone is typing is a group, with no title as sender`() {
        val s =
            screen(
                wa,
                waHeader("Weekend Trek", "Asha is typing…"),
                listOf(
                    waRow(300, 150, 700, "Also bring a torch", "6:10 pm"),
                    waRow(500, 150, 600, "and snacks", "6:11 pm"),
                    waRow(700, 700, 1040, "ok", "6:12 pm", tick = true),
                ),
            )

        val chat = WhatsAppParser().explain(s).chatOrFail()

        assertThat(chat.isGroup).isTrue()
        assertThat(chat.messages)
            .containsExactly(
                them(null, "Also bring a torch", "6:10 pm"),
                them(null, "and snacks", "6:11 pm"),
                me("ok", "6:12 pm"),
            ).inOrder()
    }

    @Test
    fun `B - wide incoming group bubbles pushed right by the avatar column stay incoming`() {
        val s =
            screen(
                wa,
                waHeader("Weekend Trek", "tap here for group info"),
                listOf(
                    waRow(300, 150, 1000, "A long message from Asha that fills the bubble", "6:10 pm", label = "Asha"),
                    waRow(500, 150, 995, "Another long message from Asha filling the width", "6:11 pm"),
                ),
            )

        val chat = WhatsAppParser().explain(s).chatOrFail()

        assertThat(chat.messages)
            .containsExactly(
                them("Asha", "A long message from Asha that fills the bubble", "6:10 pm"),
                them("Asha", "Another long message from Asha filling the width", "6:11 pm"),
            ).inOrder()
    }

    @Test
    fun `B2 - a single wide incoming bubble in a one to one chat is incoming`() {
        val s =
            screen(
                wa,
                waHeader("Asha", "online"),
                listOf(waRow(300, 150, 1000, "A long message from Asha that fills the bubble", "6:10 pm")),
            )

        val chat = WhatsAppParser().explain(s).chatOrFail()

        assertThat(chat.messages)
            .containsExactly(them("Asha", "A long message from Asha that fills the bubble", "6:10 pm"))
    }

    @Test
    fun `B3 - a tick decides mine even for a bubble that does not reach the right edge`() {
        val s =
            screen(
                wa,
                waHeader("Asha", "online"),
                listOf(
                    waRow(300, 40, 600, "hi", "6:10 pm"),
                    waRow(500, 300, 960, "hello, on my way", "6:11 pm", tick = true),
                ),
            )

        val chat = WhatsAppParser().explain(s).chatOrFail()

        assertThat(chat.messages)
            .containsExactly(them("Asha", "hi", "6:10 pm"), me("hello, on my way", "6:11 pm"))
            .inOrder()
    }

    @Test
    fun `C2 - a missing title id with only a status above the list gives no chat`() {
        val header = listOf(tv(Bounds(220, 190, 700, 240), "online", "$wa:id/conversation_contact_status"))

        val report = WhatsAppParser().explain(screen(wa, header, listOf(waRow(300, 40, 600, "hi", "6:10 pm"))))

        assertThat(report.chat).isNull()
    }

    // ---- WhatsApp geometry fallback

    @Test
    fun `C - an unread badge on the back button is never the title`() {
        val header =
            listOf(
                tv(Bounds(60, 120, 110, 170), "12"),
                tv(Bounds(220, 128, 700, 190), "Asha Patel"),
                tv(Bounds(220, 190, 700, 240), "online"),
            )
        val rows = listOf(bubble(40, 600, "hi", time = "6:10 pm").build(300).single())

        val report = WhatsAppParser().explain(screen(wa, header, rows))
        val chat = report.chatOrFail()

        assertThat(report.method).isEqualTo(WhatsAppParser.METHOD_GEOMETRY)
        assertThat(chat.chatKey.encoded).isEqualTo("com.whatsapp:Asha Patel")
        assertThat(chat.messages).containsExactly(them("Asha Patel", "hi", "6:10 pm"))
    }

    @Test
    fun `C3 - a short word left of the subtitle is not the title either`() {
        val header =
            listOf(
                tv(Bounds(20, 120, 110, 170), "Back"),
                tv(Bounds(220, 128, 700, 190), "Asha Patel"),
                tv(Bounds(220, 190, 700, 240), "online"),
            )
        val rows = listOf(bubble(40, 600, "hi", time = "6:10 pm").build(300).single())

        val chat = WhatsAppParser().explain(screen(wa, header, rows)).chatOrFail()

        assertThat(chat.title).isEqualTo("Asha Patel")
    }

    @Test
    fun `D - a label fused with the time is the time, not the message`() {
        val s =
            chatScreen(
                wa,
                "Weekend Trek",
                "tap here for group info",
                listOf(
                    row(bubble(40, 700, "Asha", "see you at 6", time = "edited 6:42 pm")),
                    row(bubble(500, 1040, "ok", time = "6:43 pm", tick = true)),
                ),
            )

        val chat = WhatsAppParser().explain(s).chatOrFail()

        assertThat(chat.messages)
            .containsExactly(them("Asha", "see you at 6", "6:42 pm"), me("ok", "6:43 pm"))
            .inOrder()
    }

    @Test
    fun `D2 - 24 hour times, emoji wrapped in direction marks, a centred weekday date`() {
        val s =
            chatScreen(
                wa,
                "Asha",
                "online",
                listOf(
                    row(bubble(40, 700, "kal milte", time = "18:42")),
                    row(bubble(500, 1040, "‏👍‏", time = "18:43", tick = true)),
                    row(centered("Sat, 21 Nov")),
                    row(bubble(40, 300, "😂", time = "18:44")),
                ),
            )

        val chat = WhatsAppParser().explain(s).chatOrFail()

        assertThat(chat.messages)
            .containsExactly(them("Asha", "kal milte", "18:42"), me("👍", "18:43"), them("Asha", "😂", "18:44"))
            .inOrder()
    }

    @Test
    fun `E - a reply composer preview above the input is not a message`() {
        val s =
            chatScreen(
                wa,
                "Asha",
                "online",
                listOf(row(bubble(40, 700, "are you coming?", time = "6:10 pm"))),
                inputTop = 1150,
            )
        val preview =
            ScreenNode(
                className = "android.widget.LinearLayout",
                bounds = Bounds(20, 1000, 1060, 1140),
                children =
                    listOf(
                        tv(Bounds(60, 1010, 600, 1060), "Asha"),
                        tv(Bounds(60, 1070, 900, 1120), "are you coming?"),
                    ),
            )
        val withPreview = s.copy(root = s.root.copy(children = s.root.children + preview))

        val chat = WhatsAppParser().explain(withPreview).chatOrFail()

        assertThat(chat.messages).containsExactly(them("Asha", "are you coming?", "6:10 pm"))
    }

    // ---- Telegram

    private val tg = TargetApps.TELEGRAM

    private fun tgCell(
        top: Int,
        desc: String,
    ) = ScreenNode(
        className = "android.view.ViewGroup",
        contentDescription = desc,
        bounds =
            Bounds(
                0,
                top,
                1080,
                top + 150,
            ),
    )

    private fun tgHeader(
        title: String?,
        sub: String,
    ) = listOfNotNull(title?.let { tv(Bounds(220, 128, 700, 190), it) }, tv(Bounds(220, 190, 700, 240), sub))

    @Test
    fun `F - a telegram group while someone types keeps names out of the text`() {
        val s =
            screen(
                tg,
                tgHeader("Weekend Trek", "Asha is typing…"),
                listOf(
                    tgCell(300, "Asha\nGood morning\nReceived today at 8:02 AM"),
                    tgCell(460, "Ravi\nok\nReceived today at 8:03 AM"),
                ),
            )

        val chat = TelegramParser().explain(s).chatOrFail()

        assertThat(chat.isGroup).isTrue()
        assertThat(chat.messages)
            .containsExactly(them("Asha", "Good morning", "8:02 AM"), them("Ravi", "ok", "8:03 AM"))
            .inOrder()
    }

    @Test
    fun `G - telegram cells come out in screen order whatever the tree order`() {
        val s =
            screen(
                tg,
                tgHeader("Asha", "online"),
                listOf(
                    tgCell(620, "third\nReceived today at 8:04 AM"),
                    tgCell(460, "second\nSent today at 8:03 AM, Read"),
                    tgCell(300, "first\nReceived today at 8:02 AM"),
                ),
            )

        val chat = TelegramParser().explain(s).chatOrFail()

        assertThat(chat.messages)
            .containsExactly(
                them("Asha", "first", "8:02 AM"),
                me("second", "8:03 AM"),
                them("Asha", "third", "8:04 AM"),
            ).inOrder()
    }

    @Test
    fun `H - a multi-line telegram follow-up without a name keeps its first line as text`() {
        val s =
            screen(
                tg,
                tgHeader("Weekend Trek", "5 members"),
                listOf(
                    tgCell(300, "Asha\nGood morning\nReceived today at 8:02 AM"),
                    tgCell(460, "Bring water\nand snacks\nReceived today at 8:03 AM"),
                    // A lower-case name is still a sender once it has been seen on another cell.
                    tgCell(620, "rahul_k\nhi\nReceived today at 8:04 AM"),
                    tgCell(780, "rahul_k\nwhere are we meeting\nReceived today at 8:05 AM"),
                    // "Forwarded from ..." is never the sender.
                    tgCell(940, "Forwarded from Trek Club\nRoute map\nReceived today at 8:06 AM"),
                ),
            )

        val chat = TelegramParser().explain(s).chatOrFail()

        assertThat(chat.messages)
            .containsExactly(
                them("Asha", "Good morning", "8:02 AM"),
                them("Asha", "Bring water\nand snacks", "8:03 AM"),
                them("rahul_k", "hi", "8:04 AM"),
                them("rahul_k", "where are we meeting", "8:05 AM"),
                them("rahul_k", "Route map", "8:06 AM"),
            ).inOrder()
    }

    @Test
    fun `I - 24 hour telegram times parse, other languages are not cells`() {
        val s =
            screen(
                tg,
                tgHeader("Asha", "last seen recently"),
                listOf(
                    tgCell(300, "hi\nReceived today at 18:02"),
                    tgCell(460, "Reply to Asha: hi\nhello\nSent today at 18:03, Read"),
                    tgCell(620, "ok\nSent today at 18:04, edited, Read"),
                    tgCell(780, "hola\nGesendet heute um 18:05"),
                ),
            )

        val chat = TelegramParser().explain(s).chatOrFail()

        // The reply line's format is unknown (device checklist step 20): only sides and times are pinned.
        assertThat(chat.messages.map { it.isMe to it.timeShown })
            .containsExactly(false to "18:02", true to "18:03", true to "18:04")
            .inOrder()
        assertThat(chat.messages.first().text).isEqualTo("hi")
        assertThat(chat.messages.last().text).isEqualTo("ok")
    }

    @Test
    fun `J - a pinned bar never becomes the title when the title is missing`() {
        val header = tgHeader(null, "online") + tv(Bounds(40, 245, 1000, 255), "Pinned message: my bank OTP is 4421")

        val report = TelegramParser().explain(screen(tg, header, listOf(tgCell(300, "hi\nReceived today at 8:02 AM"))))

        assertThat(report.chat).isNull()
    }

    @Test
    fun `J2 - a two-line pinned bar is skipped as a whole`() {
        val pinned =
            ScreenNode(
                className = "android.widget.FrameLayout",
                bounds = Bounds(0, 247, 1080, 360),
                children =
                    listOf(
                        tv(Bounds(40, 255, 600, 300), "Pinned message"),
                        tv(Bounds(40, 305, 1000, 350), "my bank OTP is 4421"),
                    ),
            )
        val noTitle =
            screen(
                tg,
                tgHeader(null, "online") + pinned,
                listOf(tgCell(400, "hi\nReceived today at 8:02 AM")),
                listTop = 0,
            )
        val withTitle =
            screen(
                tg,
                tgHeader("Asha", "online") + pinned,
                listOf(tgCell(400, "hi\nReceived today at 8:02 AM")),
                listTop = 0,
            )

        assertThat(TelegramParser().explain(noTitle).chat).isNull()
        assertThat(TelegramParser().explain(withTitle).chatOrFail().title).isEqualTo("Asha")
    }

    @Test
    fun `J3 - a telegram list that starts at the top of the screen still has a title above its first row`() {
        val s =
            screen(
                tg,
                tgHeader("Asha", "online"),
                listOf(tgCell(300, "hi\nReceived today at 8:02 AM")),
                listTop = 0,
            )

        val chat = TelegramParser().explain(s).chatOrFail()

        assertThat(chat.title).isEqualTo("Asha")
        assertThat(chat.isGroup).isFalse()
        assertThat(chat.messages).containsExactly(them("Asha", "hi", "8:02 AM"))
    }
}
