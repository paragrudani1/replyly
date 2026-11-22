package com.paragrudani.replyly.parser.telegram

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.Parsers
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.testing.Fixtures
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TelegramParserTest {
    private val india = ZoneId.of("Asia/Kolkata")
    private val parser = TelegramParser(india)

    // The older fixtures are captured on Saturday 21 November 2026 in India; their cells say "today".
    private val nov21 = LocalDate.of(2026, 11, 21)

    private fun me(
        text: String,
        time: String,
        date: LocalDate? = nov21,
    ) = ChatMessage(sender = null, text = text, timeShown = time, isMe = true, date = date)

    private fun them(
        sender: String?,
        text: String,
        time: String,
        date: LocalDate? = nov21,
    ) = ChatMessage(sender = sender, text = text, timeShown = time, isMe = false, date = date)

    private fun read(fixture: String) =
        parser.explain(Fixtures.snapshot("telegram/$fixture")).let { report ->
            checkNotNull(report.chat) { report.toString() } to report.method
        }

    @Test
    fun `one to one chat from cell descriptions`() {
        val (chat, method) = read("synthetic-one_to_one.json")

        assertThat(method).isEqualTo(TelegramParser.METHOD_DESCRIPTIONS)
        assertThat(chat.chatKey.encoded).isEqualTo("org.telegram.messenger:Asha Patel")
        assertThat(chat.isGroup).isFalse()
        assertThat(chat.historyApplies).isTrue()
        // No "Today" header, no "joined Telegram" service cell, no unread banner.
        assertThat(chat.messages)
            .containsExactly(
                them("Asha Patel", "Are you coming to Riya's birthday on Saturday?", "9:15 PM"),
                me("Yes! What time does it start?", "9:20 PM"),
                them("Asha Patel", "Today", "10:02 AM"),
                them("Asha Patel", "7 pm at her place", "10:02 AM"),
                me("10:30", "10:06 AM"),
                // In a 1:1 chat the first line of a multi-line message is text, not a sender.
                them("Asha Patel", "Can you also pick up candles?\nAnd a card", "10:08 AM"),
            ).inOrder()
    }

    @Test
    fun `group chat takes the sender from the first line`() {
        val (chat, _) = read("synthetic-group.json")

        assertThat(chat.title).isEqualTo("Weekend Trek")
        assertThat(chat.isGroup).isTrue()
        assertThat(chat.messages)
            .containsExactly(
                them("Asha", "Good morning all ☀️", "8:02 AM"),
                them("Asha", "Trek is on for Sunday?", "8:03 AM"),
                them("Meera", "Hi everyone!\nThanks for adding me", "8:10 AM"),
                me("Welcome Meera! 🎉", "8:12 AM"),
                // "Forwarded from Trek Club" is not text.
                them("Ravi", "Trek checklist: water, shoes, cap", "8:20 AM"),
                them("Ravi", "ok", "8:21 AM"),
                me("ok", "8:22 AM"),
            ).inOrder()
    }

    @Test
    fun `the same text sent twice stays twice`() {
        val (chat, _) = read("synthetic-same_text_twice.json")

        assertThat(chat.messages)
            .containsExactly(
                them("Dad", "Reached?", "9:00 PM"),
                me("ok", "9:02 PM"),
                me("ok", "9:02 PM"),
                them("Dad", "ok", "9:03 PM"),
                them("Dad", "ok", "9:03 PM"),
            ).inOrder()
    }

    @Test
    fun `hinglish text is kept as written`() {
        val (chat, _) = read("synthetic-hinglish.json")

        assertThat(chat.title).isEqualTo("Rohan Bhai")
        assertThat(chat.messages)
            .containsExactly(
                them("Rohan Bhai", "Bhai kal ka plan kya hai? 😅", "11:40 AM"),
                // The time comes from the status line, not from "6:30" in the text.
                me("Kal 6:30 tak aa jaunga, tension mat le", "11:42 AM"),
                them("Rohan Bhai", "Acha theek hai 👍\nAur haan, Mummy ne bola ghar pe khana khake jana", "11:43 AM"),
                me("Haan haan pakka 🙏🏽", "11:45 AM"),
                them("Rohan Bhai", "Kem cho? Majama?", "11:50 AM"),
                me("Majama! Tame kaho", "11:51 AM"),
            ).inOrder()
    }

    @Test
    fun `geometry takes over when cells carry no sent or received line`() {
        val (chat, method) = read("synthetic-geometry_fallback.json")

        assertThat(method).isEqualTo(TelegramParser.METHOD_GEOMETRY)
        assertThat(chat.title).isEqualTo("Asha Patel")
        assertThat(chat.messages)
            .containsExactly(
                them("Asha Patel", "Are you free tonight?", "7:01 PM"),
                me("Yes, after 8", "7:03 PM"),
                them("Asha Patel", "Dinner at 9?", "7:05 PM"),
                them("Asha Patel", "9:30", "7:05 PM"),
                me("Done 👍", "7:06 PM"),
            ).inOrder()
    }

    @Test
    fun `registry routes telegram here`() {
        assertThat(Parsers.registry(india).parserFor(TargetApps.TELEGRAM)).isInstanceOf(TelegramParser::class.java)
    }

    @Test
    fun `a status line needs a time, and must be the last line`() {
        assertThat(CellDescription.parse("Received the parcel\nthanks", isGroup = false)).isNull()
        assertThat(CellDescription.parse("Sent from my phone", isGroup = false)).isNull()

        val cell = CellDescription.parse("Sent from my phone\nSent today at 6:42 PM, Read", isGroup = false)!!
        assertThat(cell.isMe).isTrue()
        assertThat(cell.text).isEqualTo("Sent from my phone")
        assertThat(cell.time).isEqualTo("6:42 PM")
    }
}
