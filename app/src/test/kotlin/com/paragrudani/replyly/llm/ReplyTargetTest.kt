package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
    fun `blank messages are never targets`() {
        val text = them("kal?")
        assertThat(decide(me(), text, them("  "))).isEqualTo(ReplyTarget.Reply(listOf(text)))
        assertThat(decide(them(" "), text)).isEqualTo(ReplyTarget.Reply(listOf(text)))
        assertThat(decide(them(" "))).isEqualTo(ReplyTarget.NothingToReply)
    }

    @Test
    fun `their photo after my question answers it`() {
        // "photo bhej na?" then their photo: answered, not an unanswered question.
        assertThat(decide(me("photo bhej na?", seen = Duration.ofMinutes(5)), them("")))
            .isEqualTo(ReplyTarget.NothingToReply)
        // Even when my message is old: no follow-up after their media, only with the anyway tap.
        assertThat(decide(me("ok", seen = Duration.ofHours(5)), them(" ")))
            .isEqualTo(ReplyTarget.NothingToReply)
        assertThat(decide(me("ok", seen = Duration.ofHours(5)), them(" "), anyway = true))
            .isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Asked))
    }

    @Test
    fun `my photo after their message answers it`() {
        // "photo bhej" then my photo: nothing left to reply to.
        assertThat(decide(them("photo bhej"), me("", seen = Duration.ofMinutes(5))))
            .isEqualTo(ReplyTarget.NothingToReply)
        // My photo still ages like any message of mine.
        assertThat(decide(them("photo bhej"), me("", seen = Duration.ofHours(3))))
            .isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Stale))
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
    fun `an approx date uses the bubble time at or before firstSeenAt`() {
        val yesterday = now.toLocalDate().minusDays(1)
        // First seen 10 minutes ago (7:50 pm) with a 3:00 pm bubble: sent at the latest 3:00 pm today, 5 h ago.
        assertThat(decide(me(time = "3:00 pm", date = yesterday, approx = true, seen = Duration.ofMinutes(10))))
            .isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Stale))
        // A 7:00 pm bubble seen at 7:50 pm: 1 h ago, recent (the approx date is not trusted).
        assertThat(decide(me(time = "7:00 pm", date = yesterday, approx = true, seen = Duration.ofMinutes(10))))
            .isEqualTo(ReplyTarget.NothingToReply)
        // A bubble time after firstSeenAt's time is from the day before: 9:00 pm yesterday, 23 h ago.
        assertThat(decide(me(time = "9:00 pm", approx = true, seen = Duration.ofMinutes(10))))
            .isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Stale))
    }

    @Test
    fun `11 50 pm on an approx date first seen at 00 10 is 23 50 the day before`() {
        val seen = ZonedDateTime.of(2026, 11, 23, 0, 10, 0, 0, zone)
        val message =
            PromptMessage(
                ChatMessage(null, "theek hai", "11:50 pm", isMe = true),
                date = seen.toLocalDate(),
                dateIsApprox = true,
                firstSeenAt = seen.toInstant(),
            )
        assertThat(ReplyTarget.sentAt(message, zone))
            .isEqualTo(ZonedDateTime.of(2026, 11, 22, 23, 50, 0, 0, zone).toInstant())
        // At 1:00 am the age is 1 h 10: recent. At 1:51 am it is over 2 h: stale.
        val oneAm = ZonedDateTime.of(2026, 11, 23, 1, 0, 0, 0, zone)
        assertThat(ReplyTarget.decide(listOf(message), oneAm, isGroup = false)).isEqualTo(ReplyTarget.NothingToReply)
        assertThat(ReplyTarget.decide(listOf(message), oneAm.plusMinutes(51), isGroup = false))
            .isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Stale))
    }

    @Test
    fun `a missing bubble time falls back to firstSeenAt`() {
        val yesterday = now.toLocalDate().minusDays(1)
        assertThat(decide(me(time = null, date = yesterday, seen = Duration.ofMinutes(10))))
            .isEqualTo(ReplyTarget.NothingToReply)
        assertThat(decide(me(time = "6:00 pm", date = yesterday, seen = Duration.ofMinutes(10))))
            .isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Stale))
    }

    @Test
    fun `an exact time that happened twice takes the later one`() {
        val newYork = ZoneId.of("America/New_York")
        // 1 Nov 2026: clocks go from 2:00 EDT back to 1:00 EST, so 1:30 am happens twice.
        val message =
            PromptMessage(ChatMessage(null, "ok", "1:30 am", isMe = true), date = LocalDate.of(2026, 11, 1))
        assertThat(ReplyTarget.sentAt(message, newYork))
            .isEqualTo(ZonedDateTime.of(2026, 11, 1, 6, 30, 0, 0, ZoneId.of("UTC")).toInstant())
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
            assertWithMessage(it).that(ReplyTarget.isQuestion(it)).isFalse()
        }
    }

    @Test
    fun `questions marked at the end or with a question mark mid-message`() {
        listOf(
            "khana khaya kya",
            "kal free hai kya",
            "tu aa raha hai na",
            "aavse ke",
            "aavje ne",
            "aa raha hai? bata",
            "kya baat hai! kab aa rahe ho?",
            "ok. kab aa rahe ho",
            "तू आ रहा है ना",
        ).forEach {
            assertWithMessage(it).that(ReplyTarget.isQuestion(it)).isTrue()
        }
    }

    @Test
    fun `exclamations that start with a question word are not questions`() {
        listOf(
            "kya baat hai!",
            "kyun nahi, done",
            "what a match",
            "how cool is that",
            "did it 💪",
            "kab aa gaya tu!",
        ).forEach {
            assertWithMessage(it).that(ReplyTarget.isQuestion(it)).isFalse()
        }
        assertThat(ReplyTarget.isQuestion("kya baat hai!?")).isTrue()
        assertThat(ReplyTarget.isQuestion("did it work")).isTrue()
        assertThat(ReplyTarget.isQuestion("how much is it")).isTrue()
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
    fun `a name followed by a Devanagari vowel sign is a longer name`() {
        assertThat(ReplyTarget.mentionsMe("राजा आ गया", listOf("राज"))).isFalse()
        assertThat(ReplyTarget.mentionsMe("राज आ गया", listOf("राज"))).isTrue()
        assertThat(ReplyTarget.mentionsMe("@राज, कल?", listOf("राज"))).isTrue()
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
