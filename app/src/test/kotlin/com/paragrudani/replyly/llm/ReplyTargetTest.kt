package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatMessage
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReplyTargetTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 22, 20, 0, 0, 0, zone)

    private fun me(
        text: String = "theek hai",
        time: String? = null,
        date: LocalDate? = null,
        approx: Boolean = false,
        seen: Duration? = null,
    ) = PromptMessage(
        ChatMessage(null, text, time, isMe = true),
        date,
        approx,
        firstSeenAt = seen?.let { now.toInstant().minus(it) },
    )

    private fun them(
        text: String = "hi",
        sender: String = "Ravi",
        repliesToMe: Boolean = false,
    ) = PromptMessage(ChatMessage(sender, text, null, isMe = false), repliesToMe = repliesToMe)

    private fun decide(
        vararg messages: PromptMessage,
        isGroup: Boolean = false,
        names: List<String> = emptyList(),
        anyway: Boolean = false,
    ) = ReplyTarget.decide(messages.toList(), now, isGroup, names, anyway)

    @Test
    fun `others after my last message are the targets`() {
        val a = them("a")
        val b = them("b")
        assertThat(decide(them("old"), me(), a, b)).isEqualTo(ReplyTarget.Reply(listOf(a, b)))
    }

    @Test
    fun `no messages is nothing to reply to, or a follow-up when asked`() {
        assertThat(decide()).isEqualTo(ReplyTarget.NothingToReply)
        assertThat(decide(anyway = true)).isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Asked))
    }

    @Test
    fun `blank messages are ignored`() {
        val text = them("kal?")
        assertThat(decide(me(), text, them("  "))).isEqualTo(ReplyTarget.Reply(listOf(text)))
        assertThat(decide(them("hi"), me(), them(" "))).isEqualTo(ReplyTarget.NothingToReply)
    }

    @Test
    fun `my recent statement is nothing to reply to`() {
        assertThat(decide(them(), me("ok done", seen = Duration.ofMinutes(10)))).isEqualTo(ReplyTarget.NothingToReply)
    }

    @Test
    fun `the 2 hour boundary from firstSeenAt`() {
        assertThat(decide(me(seen = Duration.ofHours(2)))).isEqualTo(ReplyTarget.NothingToReply)
        assertThat(decide(me(seen = Duration.ofHours(2).plusSeconds(1))))
            .isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Stale))
    }

    @Test
    fun `the 2 hour boundary from an exact date plus the bubble time`() {
        val today = now.toLocalDate()
        assertThat(decide(me(time = "6:00 pm", date = today))).isEqualTo(ReplyTarget.NothingToReply)
        assertThat(decide(me(time = "5:59 pm", date = today))).isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Stale))
        assertThat(decide(me(time = "17:59", date = today))).isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Stale))
    }

    @Test
    fun `an approx date or a missing time falls back to firstSeenAt`() {
        val yesterday = now.toLocalDate().minusDays(1)
        // The approx date says yesterday, but Replyly first saw it 10 minutes ago: recent.
        assertThat(decide(me(time = "6:00 pm", date = yesterday, approx = true, seen = Duration.ofMinutes(10))))
            .isEqualTo(ReplyTarget.NothingToReply)
        assertThat(decide(me(time = null, date = yesterday, seen = Duration.ofMinutes(10))))
            .isEqualTo(ReplyTarget.NothingToReply)
        assertThat(decide(me(time = "6:00 pm", date = yesterday, seen = Duration.ofMinutes(10))))
            .isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Stale))
    }

    @Test
    fun `without any time the message counts as recent`() {
        assertThat(decide(me())).isEqualTo(ReplyTarget.NothingToReply)
    }

    @Test
    fun `my unanswered question gets follow-ups`() {
        listOf(
            "Kab aa rahe ho",
            "kitne baje?",
            "Did you reach",
            "what time works",
            "kem cho",
            "shu karo cho",
            "aa raha hai? 😅",
            "kal milte hain？",
            "क्या हुआ",
            "શું કરે છે",
        ).forEach { text ->
            assertThat(decide(them(), me(text, seen = Duration.ofMinutes(5))))
                .isEqualTo(ReplyTarget.FollowUp(FollowUpReason.UnansweredQuestion))
        }
    }

    @Test
    fun `statements are not questions`() {
        listOf(
            "will do",
            "should be fine",
            "can't make it",
            "ok",
            "kal pakka",
            "Kaam ho gaya",
            "is baar nahi",
            "are yaar",
        ).forEach {
            assertThat(ReplyTarget.isQuestion(it)).isFalse()
        }
    }

    @Test
    fun `a stale question is stale first`() {
        assertThat(decide(me("kab?", seen = Duration.ofHours(5)))).isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Stale))
    }

    @Test
    fun `the anyway tap turns nothing to reply to into one follow-up, and changes nothing else`() {
        assertThat(decide(me("ok"), anyway = true)).isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Asked))
        val target = them()
        assertThat(decide(me(), target, anyway = true)).isEqualTo(ReplyTarget.Reply(listOf(target)))
        assertThat(decide(me("kab?"), anyway = true)).isEqualTo(ReplyTarget.FollowUp(FollowUpReason.UnansweredQuestion))
    }

    @Test
    fun `groups rank mentions and replies to me first, keeping order in each part`() {
        val a = them("koi?", sender = "Amit")
        val b = them("Parag bata", sender = "Priya")
        val c = them("haan", sender = "Neha", repliesToMe = true)
        val d = them("ok", sender = "Amit")
        assertThat(decide(me(), a, b, c, d, isGroup = true, names = listOf("parag")))
            .isEqualTo(ReplyTarget.Reply(listOf(b, c, a, d)))
        // 1:1 chats keep the order.
        assertThat(decide(me(), a, b, c, d, names = listOf("parag"))).isEqualTo(ReplyTarget.Reply(listOf(a, b, c, d)))
    }

    @Test
    fun `mentions match whole words, at signs, any case, any of my names`() {
        val names = listOf("Parag", " Paru ", "")
        assertThat(ReplyTarget.mentionsMe("@parag kal?", names)).isTrue()
        assertThat(ReplyTarget.mentionsMe("PARU aa ja", names)).isTrue()
        assertThat(ReplyTarget.mentionsMe("thanks, Parag!", names)).isTrue()
        assertThat(ReplyTarget.mentionsMe("Paragraph", names)).isFalse()
        assertThat(ReplyTarget.mentionsMe("Parag_2", names)).isFalse()
        assertThat(ReplyTarget.mentionsMe("anything", emptyList())).isFalse()
        assertThat(ReplyTarget.mentionsMe("a.b (c)", listOf("(c)"))).isTrue()
    }

    @Test
    fun `bubble times in 12 and 24 hour forms`() {
        assertThat(ReplyTarget.parseBubbleTime("6:42 pm")).isEqualTo(LocalTime.of(18, 42))
        assertThat(ReplyTarget.parseBubbleTime("6:42 PM")).isEqualTo(LocalTime.of(18, 42))
        assertThat(ReplyTarget.parseBubbleTime("12:05 a.m.")).isEqualTo(LocalTime.of(0, 5))
        assertThat(ReplyTarget.parseBubbleTime("12:05 pm")).isEqualTo(LocalTime.of(12, 5))
        assertThat(ReplyTarget.parseBubbleTime("18.42")).isEqualTo(LocalTime.of(18, 42))
        assertThat(ReplyTarget.parseBubbleTime("Sent today at 09:15")).isEqualTo(LocalTime.of(9, 15))
        assertThat(ReplyTarget.parseBubbleTime("25:00")).isNull()
        assertThat(ReplyTarget.parseBubbleTime("yesterday")).isNull()
        assertThat(ReplyTarget.parseBubbleTime(null)).isNull()
    }

    @Test
    fun `decide on a request uses its fields`() {
        val request =
            SuggestionRequest(
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                chatTitle = "Ravi",
                isGroup = false,
                history = listOf(me("ok")),
                aboutMe = "",
                contactNote = null,
                styleExamples = emptyList(),
                instruction = null,
                now = now,
            )
        assertThat(ReplyTarget.decide(request)).isEqualTo(ReplyTarget.NothingToReply)
        assertThat(ReplyTarget.decide(request.copy(followUpAnyway = true)))
            .isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Asked))
    }
}
