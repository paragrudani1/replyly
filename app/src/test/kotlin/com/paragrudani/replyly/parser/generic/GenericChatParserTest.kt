package com.paragrudani.replyly.parser.generic

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.Parsers
import com.paragrudani.replyly.testing.Fixtures
import org.junit.Test

class GenericChatParserTest {
    private val parser = GenericChatParser()

    private fun visible(
        text: String,
        isMe: Boolean,
        time: String? = null,
    ) = ChatMessage(sender = null, text = text, timeShown = time, isMe = isMe)

    @Test
    fun `an instagram dm gives visible text with sides and no history`() {
        val chat = Parsers.registry().parse(Fixtures.snapshot("generic/synthetic-instagram_dm.json"))!!

        assertThat(chat.chatKey.encoded).isEqualTo("com.instagram.android:asha.patel")
        assertThat(chat.historyApplies).isFalse()
        assertThat(chat.isGroup).isFalse()
        // The centred "Today 6:40 PM" header and the "Seen" label are not messages.
        assertThat(chat.messages)
            .containsExactly(
                visible("hey are you around?", isMe = false),
                visible("yes, what's up", isMe = true),
                visible("can you send the notes", isMe = false),
                visible("sending now", isMe = true),
            ).inOrder()
    }

    @Test
    fun `a screen without a message input is not a chat`() {
        assertThat(parser.parse(Fixtures.snapshot("generic/synthetic-not_a_chat.json"))).isNull()
    }

    @Test
    fun `a flat list of plain text views still splits by side`() {
        val chat = parser.parse(Fixtures.snapshot("sample/minimal_chat.json"))!!

        assertThat(chat.title).isEqualTo("Asha")
        assertThat(chat.messages)
            .containsExactly(visible("Kal milte hai?", isMe = false), visible("haan, 6 baje", isMe = true))
            .inOrder()
    }

    @Test
    fun `a generic bubble keeps all its lines but not its time`() {
        val snapshot =
            chatScreen(
                "com.slack",
                title = "#trek",
                rows =
                    listOf(
                        row(bubble(40, 700, "Ravi", "Who is driving?", time = "9:41 AM")),
                        row(bubble(380, 1040, "Me too", time = "9:42 AM")),
                    ),
            )

        assertThat(parser.parse(snapshot)!!.messages)
            .containsExactly(
                visible("Ravi\nWho is driving?", isMe = false, time = "9:41 AM"),
                visible("Me too", isMe = true, time = "9:42 AM"),
            ).inOrder()
    }

    @Test
    fun `the keyboard pushing the input above the middle of the screen still leaves a chat`() {
        // S23 with Gboard open: about 900 px of keyboard plus the navigation bar under the input.
        val snapshot =
            chatScreen(
                "com.linkedin.android",
                title = "Asha Patel",
                rows = listOf(row(bubble(40, 600, "Can we talk tomorrow?", time = "9:41 AM"))),
                inputTop = 1100,
            )

        assertThat(parser.parse(snapshot)!!.messages)
            .containsExactly(visible("Can we talk tomorrow?", isMe = false, time = "9:41 AM"))
    }
}
