package com.paragrudani.replyly.parser.generic

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.parser.whatsapp.WhatsAppParser
import com.paragrudani.replyly.screen.ScreenSnapshot
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Geometry rules in full mode, driven through the WhatsApp parser with no view ids. */
class GeometryEdgeCasesTest {
    private fun parse(snapshot: ScreenSnapshot) =
        WhatsAppParser(ZoneOffset.UTC).explain(snapshot).let { report ->
            assertThat(report.method).isEqualTo(WhatsAppParser.METHOD_GEOMETRY)
            checkNotNull(report.chat) { report.toString() }
        }

    private fun me(
        text: String,
        time: String?,
    ) = ChatMessage(sender = null, text = text, timeShown = time, isMe = true)

    private fun them(
        sender: String?,
        text: String,
        time: String?,
    ) = ChatMessage(sender = sender, text = text, timeShown = time, isMe = false)

    private val group = "Asha, Ravi, You"

    @Test
    fun `flat list - a time on its own row joins the bubble above, a name row names the bubble below`() {
        val chat =
            parse(
                chatScreen(
                    TargetApps.WHATSAPP,
                    "Weekend Trek",
                    group,
                    listOf(
                        flat(text(40, 400, "Ravi")),
                        flat(text(40, 700, "Who is driving?")),
                        flat(text(500, 640, "9:41 am")),
                        flat(text(700, 1040, "I can")),
                        flat(text(900, 1040, "9:42 am")),
                    ),
                ),
            )

        assertThat(chat.messages)
            .containsExactly(them("Ravi", "Who is driving?", "9:41 am"), me("I can", "9:42 am"))
            .inOrder()
    }

    @Test
    fun `a lone time far from any bubble is dropped, never read as text`() {
        val chat =
            parse(
                chatScreen(
                    TargetApps.WHATSAPP,
                    "Asha",
                    "online",
                    listOf(
                        row(bubble(40, 600, "See you", time = "6:00 pm")),
                        row(centered("6:05 pm")),
                        row(text(40, 300, "7:15 pm")),
                    ),
                ),
            )

        assertThat(chat.messages).containsExactly(them("Asha", "See you", "6:00 pm"))
    }

    @Test
    fun `date words in a bubble are a message, centred they are a separator`() {
        val chat =
            parse(
                chatScreen(
                    TargetApps.WHATSAPP,
                    "Asha",
                    "online",
                    listOf(
                        row(centered("Yesterday")),
                        row(bubble(40, 500, "When?", time = "6:00 pm")),
                        row(centered("21 November 2026")),
                        row(bubble(700, 1040, "Today", time = "6:01 pm", tick = true)),
                        row(bubble(40, 500, "Friday", time = "6:02 pm")),
                    ),
                ),
            )

        // Captured on 21 November 2026: each separator dates the bubbles below it.
        val yesterday = LocalDate.of(2026, 11, 20)
        val nov21 = LocalDate.of(2026, 11, 21)
        assertThat(chat.messages)
            .containsExactly(
                them("Asha", "When?", "6:00 pm").copy(date = yesterday),
                me("Today", "6:01 pm").copy(date = nov21),
                them("Asha", "Friday", "6:02 pm").copy(date = nov21),
            ).inOrder()
    }

    @Test
    fun `system notices are skipped even when not centred`() {
        val chat =
            parse(
                chatScreen(
                    TargetApps.WHATSAPP,
                    "Asha",
                    "online",
                    listOf(
                        row(text(40, 900, "Messages and calls are end-to-end encrypted. Tap to learn more.")),
                        row(text(0, 1000, "3 unread messages")),
                        row(centered("You blocked this contact")),
                        row(bubble(40, 500, "hi", time = "6:00 pm")),
                    ),
                ),
            )

        assertThat(chat.messages).containsExactly(them("Asha", "hi", "6:00 pm"))
    }

    @Test
    fun `a reply quote in a continuation bubble is not a sender label`() {
        val chat =
            parse(
                chatScreen(
                    TargetApps.WHATSAPP,
                    "Weekend Trek",
                    group,
                    listOf(
                        row(bubble(125, 700, "Ravi", "Sunday?", time = "9:00 am")),
                        row(quoteBubble(125, 800, "Asha", "Trek is on", "Count me in", time = "9:01 am")),
                    ),
                ),
            )

        assertThat(chat.messages)
            .containsExactly(them("Ravi", "Sunday?", "9:00 am"), them("Ravi", "Count me in", "9:01 am"))
            .inOrder()
    }

    @Test
    fun `a deleted message keeps the sender run going`() {
        val chat =
            parse(
                chatScreen(
                    TargetApps.WHATSAPP,
                    "Weekend Trek",
                    group,
                    listOf(
                        row(bubble(125, 700, "Asha", "This message was deleted", time = "9:00 am")),
                        row(bubble(125, 700, "sorry, wrong chat", time = "9:01 am")),
                    ),
                ),
            )

        assertThat(chat.messages).containsExactly(them("Asha", "sorry, wrong chat", "9:01 am"))
    }

    @Test
    fun `my message ends a sender run`() {
        val chat =
            parse(
                chatScreen(
                    TargetApps.WHATSAPP,
                    "Weekend Trek",
                    group,
                    listOf(
                        row(bubble(125, 700, "Asha", "Ready?", time = "9:00 am")),
                        row(bubble(700, 1040, "Yes", time = "9:01 am", tick = true)),
                        row(bubble(125, 700, "Leaving now", time = "9:02 am")),
                    ),
                ),
            )

        assertThat(chat.messages)
            .containsExactly(
                them("Asha", "Ready?", "9:00 am"),
                me("Yes", "9:01 am"),
                // Label not on screen (as after a scroll): unknown, not Asha.
                them(null, "Leaving now", "9:02 am"),
            ).inOrder()
    }

    @Test
    fun `in a known one to one chat an extra line is never a sender`() {
        val chat =
            parse(
                chatScreen(
                    TargetApps.WHATSAPP,
                    "Asha",
                    "last seen today at 5:00 pm",
                    listOf(row(bubble(40, 700, "Shopping list", "milk, eggs", time = "9:00 am"))),
                ),
            )

        assertThat(chat.isGroup).isFalse()
        assertThat(chat.messages).containsExactly(them("Asha", "milk, eggs", "9:00 am"))
    }
}
