package com.paragrudani.replyly.ui.debug

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParseReport
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.parser.Parsers
import com.paragrudani.replyly.parser.explain
import com.paragrudani.replyly.testing.Fixtures
import org.junit.Test

class DumpSummaryTest {
    @Test
    fun `a parsed chat shows title, key, kind and each message with its side and sender`() {
        val chat =
            ParsedChat(
                packageName = "com.whatsapp",
                title = "Weekend Trek",
                isGroup = true,
                messages =
                    listOf(
                        ChatMessage(sender = null, text = "Also bring a torch", timeShown = "6:10 pm", isMe = false),
                        ChatMessage(sender = "Asha", text = "Two\nlines", timeShown = null, isMe = false),
                        ChatMessage(sender = null, text = "ok", timeShown = "8:22 am", isMe = true),
                    ),
                historyApplies = true,
            )

        val lines = DumpSummary.lines(ParseReport(chat, "whatsapp:view-ids", listOf("list found")))

        assertThat(lines)
            .containsExactly(
                "Parser: whatsapp:view-ids",
                "Title: Weekend Trek",
                "Chat key: com.whatsapp:Weekend Trek",
                "Group: yes · History: yes · 3 messages",
                "Notes: list found",
                "← ? [6:10 pm] Also bring a torch",
                "← Asha Two⏎lines",
                "→ me [8:22 am] ok",
            ).inOrder()
    }

    @Test
    fun `a screen that is not a chat says so, with the parser notes`() {
        val report = Parsers.registry().explain(Fixtures.snapshot("whatsapp/synthetic-chat_list.json"))

        assertThat(DumpSummary.lines(report))
            .containsExactly(
                "Parser: whatsapp:geometry",
                "Not recognised as a chat.",
                "Notes: no ids and no input field: not a chat",
            ).inOrder()
    }

    @Test
    fun `a parser crash shows the exception class only`() {
        assertThat(DumpSummary.crashed(IllegalStateException("private text")))
            .containsExactly("Parser crashed: IllegalStateException")
    }

    @Test
    fun `an X dump names the mode and counts the posts`() {
        val report = Parsers.registry().explain(Fixtures.snapshot("x/synthetic-reply_thread.json"))

        val lines = DumpSummary.lines(report)

        assertThat(lines).contains("Parser: x:view-ids")
        assertThat(lines).contains("Group: no · History: no · 3 messages")
        assertThat(lines).contains("X: reply · 2 parents + target · 1 with a quoted post · my handle from none")
        assertThat(
            lines.single { it.startsWith("Notes: ") },
        ).startsWith("Notes: mode: reply; 3 posts: 2 parents + target")
    }
}
