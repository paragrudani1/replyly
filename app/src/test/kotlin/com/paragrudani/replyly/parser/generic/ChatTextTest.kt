package com.paragrudani.replyly.parser.generic

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class ChatTextTest {
    @Test
    fun `clean trims padding and direction marks but keeps inner text and emoji`() {
        assertThat(ChatText.clean("ok\u00A0\u00A0\u00A0\u00A0")).isEqualTo("ok")
        assertThat(ChatText.clean("\u200E+91 98765 43210\u200F")).isEqualTo("+91 98765 43210")
        assertThat(ChatText.clean("Good morning ☀️ ")).isEqualTo("Good morning ☀️")
        assertThat(ChatText.clean("line one\nline two")).isEqualTo("line one\nline two")
        assertThat(ChatText.clean(" \u00A0\u202F ")).isNull()
    }

    @Test
    fun `clock times in every spacing are times, text around a time is not`() {
        listOf("6:42", "18:42", "6:42 pm", "6:42\u202Fpm", "6:42\u00A0PM", "6:42 p.m.", "10:30").forEach {
            assertWithMessage(it).that(ChatText.isTime(it)).isTrue()
        }
        listOf("6:30 tak aa jaunga", "at 6:42 pm", "6.42", "642", "Today").forEach {
            assertWithMessage(it).that(ChatText.isTime(it)).isFalse()
        }
        assertThat(ChatText.findTime("Received today at 6:42\u202FPM, Read")).isEqualTo("6:42\u202FPM")
    }

    @Test
    fun `date separators`() {
        listOf(
            "TODAY",
            "Yesterday",
            "Monday",
            "21 November 2026",
            "November 21, 2026",
            "Nov 21",
            "21/11/2026",
            "Friday, 21 November",
        ).forEach { assertWithMessage(it).that(ChatText.isDateSeparator(it)).isTrue() }
        listOf("Today at 6?", "may be", "Monday works", "21 people").forEach {
            assertWithMessage(it).that(ChatText.isDateSeparator(it)).isFalse()
        }
    }

    @Test
    fun `notices that are never messages`() {
        assertThat(ChatText.isAlwaysChrome("2 UNREAD MESSAGES")).isTrue()
        assertThat(ChatText.isAlwaysChrome("1 unread message")).isTrue()
        assertThat(ChatText.isAlwaysChrome("Messages are end-to-end encrypted.")).isTrue()
        assertThat(ChatText.isAlwaysChrome("This message was deleted")).isTrue()
        assertThat(ChatText.isAlwaysChrome("I read 2 messages")).isFalse()
    }

    @Test
    fun `subtitles tell groups from one to one chats`() {
        assertThat(ChatText.groupHint("Asha, Ravi, You")).isEqualTo(GroupHint.GROUP)
        assertThat(ChatText.groupHint("5 members, 2 online")).isEqualTo(GroupHint.GROUP)
        assertThat(ChatText.groupHint("tap here for group info")).isEqualTo(GroupHint.GROUP)
        assertThat(ChatText.groupHint("online")).isEqualTo(GroupHint.ONE_TO_ONE)
        assertThat(ChatText.groupHint("last seen today at 9:01 pm")).isEqualTo(GroupHint.ONE_TO_ONE)
        assertThat(ChatText.groupHint("typing…")).isEqualTo(GroupHint.ONE_TO_ONE)
        // Only groups say who is typing.
        assertThat(ChatText.groupHint("Asha is typing…")).isEqualTo(GroupHint.GROUP)
        assertThat(ChatText.groupHint("Ravi is recording audio…")).isEqualTo(GroupHint.GROUP)
        assertThat(ChatText.activeMember("Asha is typing…")).isEqualTo("Asha")
        assertThat(ChatText.activeMember("online")).isNull()
        assertThat(ChatText.groupHint("Mumbai, India")).isEqualTo(GroupHint.UNKNOWN)
        assertThat(ChatText.groupHint(null)).isEqualTo(GroupHint.UNKNOWN)
    }

    @Test
    fun `badges, pinned bars and labelled times`() {
        assertThat(ChatText.isBadge("12")).isTrue()
        assertThat(ChatText.isBadge("99+")).isTrue()
        assertThat(ChatText.isBadge("Asha 2")).isFalse()
        assertThat(ChatText.isPinnedLabel("Pinned message")).isTrue()
        assertThat(ChatText.isPinnedLabel("Pinned Message: my OTP")).isTrue()
        assertThat(ChatText.isPinnedLabel("Asha pinned a message")).isFalse()

        assertThat(ChatText.timeOf("edited 6:42\u202Fpm")).isEqualTo("6:42\u202Fpm")
        assertThat(ChatText.timeOf("Edited, 18:42")).isEqualTo("18:42")
        assertThat(ChatText.timeOf("6:42 pm")).isEqualTo("6:42 pm")
        assertThat(ChatText.timeOf("see you at 6:42 pm")).isNull()
    }
}
