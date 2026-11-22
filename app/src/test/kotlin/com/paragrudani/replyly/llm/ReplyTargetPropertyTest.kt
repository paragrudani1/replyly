package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.parser.ChatMessage
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Property test for [ReplyTarget.decide]. A seeded generator writes random conversations (1:1 and
 * groups, 0 to 30 messages, blank media messages, questions, mentions, replies to me, random ages,
 * exact and approx dates, bubble times in 12 and 24-hour forms) and checks each decision against a
 * simple model of the truth. Deterministic: the same seeds give the same conversations.
 *
 * Invariants, for every conversation (ignoring messages with blank text):
 * 1. A [ReplyTarget.Reply]'s targets are exactly the others' messages after my last message (all
 *    of them when I never wrote), as a multiset.
 * 2. In a 1:1 chat the targets keep the conversation order; in a group, the ranked-first ones
 *    (mention or reply to me) come first, and each part keeps the conversation order.
 * 3. It never returns Reply when my message is last.
 * 4. It never returns FollowUp when others wrote after me.
 * 5. With my message last, the "anyway" tap only ever turns NothingToReply into
 *    FollowUp(Asked); every other decision is the same with or without it.
 * 6. FollowUp(Stale) only for a message whose known age is over 2 hours.
 */
class ReplyTargetPropertyTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 22, 20, 0, 0, 0, zone)
    private val names = listOf("Parag", "Paru")

    private val texts =
        listOf(
            "ok",
            "haan",
            "kal milte?",
            "kab aa rahe ho",
            "done 👍",
            "@Parag bata na",
            "parag kal free?",
            "Paragraph bhej",
            "theek hai",
            "what time?",
            "kem cho",
            "lol",
            "",
            "  ",
            "sure, 6 works",
        )
    private val senders = listOf("Ravi", "Priya", "Amit")

    private fun message(random: Random): PromptMessage {
        val isMe = random.nextInt(3) == 0
        val age = Duration.ofMinutes(random.nextLong(0, 60L * 30))
        val sent = now.minus(age)
        val time =
            when (random.nextInt(3)) {
                0 -> null
                1 -> "%d:%02d %s".format((sent.hour + 11) % 12 + 1, sent.minute, if (sent.hour < 12) "am" else "pm")
                else -> "%02d:%02d".format(sent.hour, sent.minute)
            }
        return PromptMessage(
            message =
                ChatMessage(
                    sender = if (isMe) null else senders.random(random),
                    text = texts.random(random),
                    timeShown = time,
                    isMe = isMe,
                ),
            date = if (random.nextBoolean()) sent.toLocalDate() else null,
            dateIsApprox = random.nextBoolean(),
            repliesToMe = !isMe && random.nextInt(5) == 0,
            firstSeenAt = if (random.nextBoolean()) sent.toInstant().plusSeconds(random.nextLong(0, 120)) else null,
        )
    }

    @Test
    fun `decisions match the truth on random conversations`() {
        var replies = 0
        var followUps = 0
        var nothing = 0
        for (seed in 1..SEEDS) {
            val random = Random(seed)
            val isGroup = random.nextBoolean()
            val conversation = List(random.nextInt(0, 31)) { message(random) }
            val context = "seed $seed"

            val decision = ReplyTarget.decide(conversation, now, isGroup, names)
            val anyway = ReplyTarget.decide(conversation, now, isGroup, names, followUpAnyway = true)

            val visible = conversation.filter { it.message.text.isNotBlank() }
            val lastMine = visible.indexOfLast { it.message.isMe }
            val othersAfter = visible.drop(lastMine + 1)
            val myMessageLast = visible.isNotEmpty() && visible.last().message.isMe

            if (othersAfter.isNotEmpty()) {
                // 1, 4
                val reply = decision as? ReplyTarget.Reply
                assertWithMessage(context).that(reply).isNotNull()
                assertWithMessage(context).that(reply!!.targets).containsExactlyElementsIn(othersAfter)
                // 2
                val first = { m: PromptMessage -> m.repliesToMe || ReplyTarget.mentionsMe(m.message.text, names) }
                val expected =
                    if (isGroup) othersAfter.filter(first) + othersAfter.filterNot(first) else othersAfter
                assertWithMessage(context).that(reply.targets).containsExactlyElementsIn(expected).inOrder()
                assertWithMessage(context).that(anyway).isEqualTo(decision)
                replies++
            } else {
                // 3
                assertWithMessage(context).that(decision).isNotInstanceOf(ReplyTarget.Reply::class.java)
                // 5
                if (decision == ReplyTarget.NothingToReply) {
                    assertWithMessage(context).that(anyway).isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Asked))
                    nothing++
                } else {
                    assertWithMessage(context).that(anyway).isEqualTo(decision)
                    followUps++
                }
                // 6
                if (decision == ReplyTarget.FollowUp(FollowUpReason.Stale)) {
                    assertWithMessage(context).that(myMessageLast).isTrue()
                    assertWithMessage(context)
                        .that(ReplyTarget.isOlderThan(visible.last(), now, ReplyTarget.FOLLOW_UP_AFTER))
                        .isTrue()
                }
            }
        }
        // The generator reaches every outcome often enough to mean something.
        assertWithMessage("replies").that(replies).isGreaterThan(SEEDS / 5)
        assertWithMessage("follow-ups").that(followUps).isGreaterThan(SEEDS / 20)
        assertWithMessage("nothing to reply to").that(nothing).isGreaterThan(SEEDS / 50)
    }

    private companion object {
        const val SEEDS = 3_000
    }
}
