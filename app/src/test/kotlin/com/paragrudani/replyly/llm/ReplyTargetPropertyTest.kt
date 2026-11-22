package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.parser.ChatMessage
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Property tests for [ReplyTarget.decide], on seeded generators (the same seeds give the same
 * conversations).
 *
 * Generator 1 (random): 1:1 chats and groups, 0 to 30 messages, blank media messages, questions,
 * mentions, replies to me, random unordered ages, exact and approx dates, bubble times in 12 and
 * 24-hour forms. Invariants, for every conversation ("text" = non-blank; media count as turns):
 * 1. A [ReplyTarget.Reply]'s targets are exactly the text messages after my last message (any
 *    message of mine, media too; all of them when I never wrote), as a multiset.
 * 2. In a 1:1 chat the targets keep the conversation order; in a group, the ranked-first ones
 *    (mention or reply to me) come first, and each part keeps the conversation order.
 * 3. It never returns Reply when no text message follows my last message.
 * 4. FollowUp(Stale) and FollowUp(UnansweredQuestion) only when the very last message is mine
 *    (their media after mine means they answered).
 * 5. The "anyway" tap only ever turns NothingToReply into FollowUp(Asked); every other decision
 *    is the same with or without it.
 *
 * Generator 2 (chronological): real timelines with whole-minute send times in Asia/Kolkata,
 * America/New_York and Europe/London around their autumn clock changes, crossing midnight;
 * `firstSeenAt` between the send time and 10 hours later (never after now); dates exact (the send
 * date) or approx (firstSeenAt's date, as history stores them), or missing for on-screen messages.
 * Checked against the generator's own send times, not against [ReplyTarget.sentAt]:
 * 6. FollowUp(Stale) only when my last message's true age is over 2 hours.
 * 7. With my message last, a bubble time, and an exact date or a firstSeenAt: a true age over 2
 *    hours gives FollowUp(Stale) (no missed stale), unless the local time happened twice (clock
 *    turned back), where the earlier instant cannot be told from the later one.
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

            val lastMine = conversation.indexOfLast { it.message.isMe }
            val othersAfter = conversation.drop(lastMine + 1).filter { it.message.text.isNotBlank() }
            val myMessageLast = conversation.isNotEmpty() && conversation.last().message.isMe

            if (othersAfter.isNotEmpty()) {
                // 1
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
                // 4
                if (decision is ReplyTarget.FollowUp) assertWithMessage(context).that(myMessageLast).isTrue()
                // 5
                if (decision == ReplyTarget.NothingToReply) {
                    assertWithMessage(context).that(anyway).isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Asked))
                    nothing++
                } else {
                    assertWithMessage(context).that(anyway).isEqualTo(decision)
                    followUps++
                }
            }
        }
        // The generator reaches every outcome often enough to mean something.
        assertWithMessage("replies").that(replies).isGreaterThan(SEEDS / 5)
        assertWithMessage("follow-ups").that(followUps).isGreaterThan(SEEDS / 20)
        assertWithMessage("nothing to reply to").that(nothing).isGreaterThan(SEEDS / 50)
    }

    private val zones = listOf("Asia/Kolkata", "America/New_York", "Europe/London").map(ZoneId::of)

    /** A message of a real timeline with its true send time. */
    private class Timed(
        val prompt: PromptMessage,
        val sent: Instant,
    )

    private fun timeline(
        random: Random,
        zone: ZoneId,
    ): Pair<List<Timed>, ZonedDateTime> {
        // 20 Oct to 8 Nov 2026: London turns its clocks back on 25 Oct, New York on 1 Nov.
        var at =
            ZonedDateTime
                .of(2026, 10, 20, 0, 0, 0, 0, zone)
                .plusMinutes(random.nextLong(0, 60L * 24 * 19))
        val sendTimes =
            List(random.nextInt(1, 16)) {
                at = at.plusMinutes(random.nextLong(0, random.nextLong(1, 60L * 8)))
                at.toInstant()
            }
        val now = at.plusMinutes(random.nextLong(0, 60L * 8)).withSecond(random.nextInt(60))
        val timed =
            sendTimes.map { sent ->
                val isMe = random.nextInt(3) == 0
                val local = sent.atZone(zone)
                val time =
                    when (random.nextInt(4)) {
                        0 -> {
                            null
                        }

                        1 -> {
                            "%02d:%02d".format(local.hour, local.minute)
                        }

                        else -> {
                            "%d:%02d %s".format(
                                (local.hour + 11) % 12 + 1,
                                local.minute,
                                if (local.hour <
                                    12
                                ) {
                                    "am"
                                } else {
                                    "pm"
                                },
                            )
                        }
                    }
                val seen =
                    minOf(now.toInstant(), sent.plusSeconds(random.nextLong(0, 10L * 3600)))
                        .takeIf { random.nextInt(5) != 0 }
                val (date, approx) =
                    when {
                        seen == null -> (if (random.nextBoolean()) local.toLocalDate() else null) to false
                        random.nextBoolean() -> local.toLocalDate() to false
                        else -> seen.atZone(zone).toLocalDate() to true
                    }
                Timed(
                    PromptMessage(
                        ChatMessage(if (isMe) null else senders.random(random), texts.random(random), time, isMe),
                        date = date,
                        dateIsApprox = approx,
                        firstSeenAt = seen,
                    ),
                    sent,
                )
            }
        return timed to now
    }

    @Test
    fun `stale is decided from the true send time on chronological conversations`() {
        var stale = 0
        var recentMine = 0
        for (seed in 1..CHRONO_SEEDS) {
            val random = Random(seed * 7_919L)
            val zone = zones.random(random)
            val (timed, now) = timeline(random, zone)
            val context = "seed $seed, $zone, now $now"
            val decision = ReplyTarget.decide(timed.map { it.prompt }, now, random.nextBoolean(), names)
            val last = timed.last()
            val trueAge = Duration.between(last.sent, now.toInstant())
            // 6
            if (decision == ReplyTarget.FollowUp(FollowUpReason.Stale)) {
                assertWithMessage(context).that(last.prompt.message.isMe).isTrue()
                assertWithMessage(context).that(trueAge).isGreaterThan(ReplyTarget.FOLLOW_UP_AFTER)
                stale++
            }
            // 7
            val message = last.prompt
            val local = last.sent.atZone(zone).toLocalDateTime()
            val timeKnown = ReplyTarget.parseBubbleTime(message.message.timeShown) != null
            val anchor = (message.date != null && !message.dateIsApprox) || message.firstSeenAt != null
            if (message.message.isMe && timeKnown && anchor && zone.rules.getValidOffsets(local).size == 1) {
                if (trueAge > ReplyTarget.FOLLOW_UP_AFTER) {
                    assertWithMessage(context).that(decision).isEqualTo(ReplyTarget.FollowUp(FollowUpReason.Stale))
                } else {
                    recentMine++
                }
            }
        }
        assertWithMessage("stale").that(stale).isGreaterThan(CHRONO_SEEDS / 20)
        assertWithMessage("recent mine").that(recentMine).isGreaterThan(CHRONO_SEEDS / 50)
    }

    private companion object {
        const val SEEDS = 3_000
        const val CHRONO_SEEDS = 10_000
    }
}
