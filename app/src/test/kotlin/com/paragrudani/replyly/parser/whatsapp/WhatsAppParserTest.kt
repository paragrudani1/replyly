package com.paragrudani.replyly.parser.whatsapp

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.Parsers
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.testing.Fixtures
import org.junit.Test

class WhatsAppParserTest {
    private val parser = WhatsAppParser()

    private fun me(
        text: String,
        time: String,
    ) = ChatMessage(sender = null, text = text, timeShown = time, isMe = true)

    private fun them(
        sender: String?,
        text: String,
        time: String,
    ) = ChatMessage(sender = sender, text = text, timeShown = time, isMe = false)

    private val oneToOne =
        listOf(
            them("Asha Patel", "Are you coming to Riya's birthday on Saturday?", "9:15 pm"),
            me("Yes! What time does it start?", "9:20 pm"),
            // A message whose text is "Today" is a message, not a date separator.
            them("Asha Patel", "Today", "10:02 am"),
            them("Asha Patel", "7 pm at her place", "10:02 am"),
            // The reply quote ("7 pm at her place") is not part of the text.
            me("Perfect, I'll bring the cake 🎂", "10:05 am"),
            // A message that looks like a time is still a message.
            me("10:30", "10:06 am"),
            // "This message was deleted" is dropped; trailing no-break-space padding is trimmed.
            them("Asha Patel", "Can you also pick up candles?", "10:08 am"),
        )

    @Test
    fun `one to one chat by view ids`() {
        val report = parser.explain(Fixtures.snapshot("whatsapp/synthetic-one_to_one.json"))
        val chat = checkNotNull(report.chat) { report.toString() }

        assertThat(report.method).isEqualTo(WhatsAppParser.METHOD_VIEW_IDS)
        assertThat(chat.title).isEqualTo("Asha Patel")
        assertThat(chat.chatKey.encoded).isEqualTo("com.whatsapp:Asha Patel")
        assertThat(chat.isGroup).isFalse()
        assertThat(chat.historyApplies).isTrue()
        // No encryption notice, no date separators, no draft, no reply composer, no unread badge.
        assertThat(chat.messages).containsExactlyElementsIn(oneToOne).inOrder()
    }

    @Test
    fun `one to one chat by geometry when the view ids are gone`() {
        val report = parser.explain(Fixtures.snapshot("whatsapp/synthetic-one_to_one-no_ids.json"))
        val chat = checkNotNull(report.chat) { report.toString() }

        assertThat(report.method).isEqualTo(WhatsAppParser.METHOD_GEOMETRY)
        assertThat(chat.title).isEqualTo("Asha Patel")
        assertThat(chat.isGroup).isFalse()
        assertThat(chat.messages).containsExactlyElementsIn(oneToOne).inOrder()
    }

    // Android 14+ writes a narrow no-break space before am/pm; the time is kept exactly as shown.
    private fun t(time: String) = time.replace(' ', '\u202F')

    private val group =
        listOf(
            // Top bubble, cut by the toolbar: its sender label scrolled away.
            them(null, "Also bring a torch", t("6:10 pm")),
            // The "TODAY" divider sits inside this row.
            them("Asha", "Good morning all ☀️", t("8:02 am")),
            // No label: same sender as the bubble above.
            them("Asha", "Trek is on for Sunday?", t("8:03 am")),
            // "Asha added Meera" (system) is skipped.
            them("Meera", "Hi everyone! Thanks for adding me", t("8:10 am")),
            me("Welcome Meera! 🎉", t("8:12 am")),
            // Unread banner skipped. Unsaved number: the label is the number; the reply quote is not text.
            them("+91 98765 43210", "Sunday works. What time?", t("8:15 am")),
            // "Forwarded" is not text.
            them("Ravi", "Trek checklist: water, shoes, cap", t("8:20 am")),
            them("Ravi", "ok", t("8:21 am")),
            me("ok", t("8:22 am")),
        )

    @Test
    fun `group chat by view ids`() {
        val report = parser.explain(Fixtures.snapshot("whatsapp/synthetic-group.json"))
        val chat = checkNotNull(report.chat) { report.toString() }

        assertThat(report.method).isEqualTo(WhatsAppParser.METHOD_VIEW_IDS)
        assertThat(chat.title).isEqualTo("Weekend Trek")
        assertThat(chat.isGroup).isTrue()
        assertThat(chat.messages).containsExactlyElementsIn(group).inOrder()
    }

    @Test
    fun `group chat by geometry when the view ids are gone`() {
        val report = parser.explain(Fixtures.snapshot("whatsapp/synthetic-group-no_ids.json"))
        val chat = checkNotNull(report.chat) { report.toString() }

        assertThat(report.method).isEqualTo(WhatsAppParser.METHOD_GEOMETRY)
        assertThat(chat.title).isEqualTo("Weekend Trek")
        assertThat(chat.isGroup).isTrue()
        assertThat(chat.messages).containsExactlyElementsIn(group).inOrder()
    }

    @Test
    fun `the same text sent twice stays twice`() {
        val chat = parser.parse(Fixtures.snapshot("whatsapp/synthetic-same_text_twice.json"))!!

        assertThat(chat.title).isEqualTo("Dad")
        assertThat(chat.messages)
            .containsExactly(
                them("Dad", "Reached?", "9:00 pm"),
                me("ok", "9:02 pm"),
                me("ok", "9:02 pm"),
                them("Dad", "ok", "9:03 pm"),
                them("Dad", "ok", "9:03 pm"),
            ).inOrder()
    }

    private fun hinglish(sender: String) =
        listOf(
            them(sender, "Bhai kal ka plan kya hai? 😅", "11:40 am"),
            // "6:30" inside a message is text, not a time.
            me("Kal 6:30 tak aa jaunga, tension mat le", "11:42 am"),
            // "Edited" is not text.
            them(sender, "Acha theek hai 👍", "11:43 am"),
            them(sender, "Aur haan, Mummy ne bola ghar pe khana khake jana", "11:43 am"),
            me("Haan haan pakka 🙏🏽", "11:45 am"),
            them(sender, "Kem cho? Majama?", "11:50 am"),
            me("Majama! Tame kaho", "11:51 am"),
        )

    @Test
    fun `hinglish and gujlish text is kept as written`() {
        val chat = parser.parse(Fixtures.snapshot("whatsapp/synthetic-hinglish.json"))!!

        assertThat(chat.title).isEqualTo("Rohan Bhai")
        assertThat(chat.isGroup).isFalse()
        assertThat(chat.messages).containsExactlyElementsIn(hinglish("Rohan Bhai")).inOrder()
    }

    @Test
    fun `whatsapp business chat uses its own package in the chat key`() {
        val chat = Parsers.registry().parse(Fixtures.snapshot("whatsapp_business/synthetic-hinglish.json"))!!

        assertThat(chat.chatKey.encoded).isEqualTo("com.whatsapp.w4b:Rohan Bhai")
        assertThat(chat.historyApplies).isTrue()
        assertThat(chat.messages).containsExactlyElementsIn(hinglish("Rohan Bhai")).inOrder()
    }

    @Test
    fun `the chat list is not a chat`() {
        // Search field at the top, chat rows with names and times: no message ids, no bottom input.
        assertThat(parser.parse(Fixtures.snapshot("whatsapp/synthetic-chat_list.json"))).isNull()
    }

    @Test
    fun `whatsapp business shares the parser`() {
        assertThat(parser.packages).containsExactly(TargetApps.WHATSAPP, TargetApps.WHATSAPP_BUSINESS)
    }
}
