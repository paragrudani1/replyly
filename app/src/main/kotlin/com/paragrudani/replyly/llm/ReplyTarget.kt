package com.paragrudani.replyly.llm

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/** Why the model is asked for follow-ups to my own last message. */
enum class FollowUpReason {
    /** My last message is more than [ReplyTarget.FOLLOW_UP_AFTER] old with no answer. */
    Stale,

    /** My last message is a question, and nobody has answered it (it is the last message). */
    UnansweredQuestion,

    /** I tapped "Suggest follow-ups anyway" on the "Nothing to reply to" state. */
    Asked,
}

/**
 * What the next message from me should do (issue #26, PR 2). Pure: decided from the messages and
 * the time only, by [ReplyTarget.decide].
 */
sealed interface ReplyTarget {
    /**
     * Answer the others' messages after my last message. In a group, the ones that mention me or
     * reply to me come first; otherwise (and within each part) oldest first.
     */
    data class Reply(
        val targets: List<PromptMessage>,
    ) : ReplyTarget

    /** My message is the last one: suggest follow-ups. */
    data class FollowUp(
        val reason: FollowUpReason,
    ) : ReplyTarget

    /** My message is the last one, recent and not a question: the panel offers follow-ups instead of calling. */
    data object NothingToReply : ReplyTarget

    companion object {
        /** After this long without an answer, my last message gets follow-ups by itself. */
        val FOLLOW_UP_AFTER: Duration = Duration.ofHours(2)

        fun decide(request: SuggestionRequest): ReplyTarget =
            decide(request.history, request.now, request.isGroup, request.myNames, request.followUpAnyway)

        /**
         * - Messages with blank text (media without a caption) are ignored.
         * - Others wrote after my last message (or I never wrote): [Reply] with exactly those
         *   messages. No messages at all: [NothingToReply].
         * - My message is last: [FollowUp] when it is older than [FOLLOW_UP_AFTER] or is a question
         *   ([isQuestion]); otherwise [NothingToReply], or [FollowUpReason.Asked] with
         *   [followUpAnyway] (the "anyway" tap, which only ever turns NothingToReply into a call).
         *
         * Age: the message's [PromptMessage.date] plus its bubble time when both are known and the
         * date is exact; otherwise [PromptMessage.firstSeenAt]. With neither, the age is unknown and
         * the message counts as recent.
         */
        fun decide(
            messages: List<PromptMessage>,
            now: ZonedDateTime,
            isGroup: Boolean,
            myNames: List<String> = emptyList(),
            followUpAnyway: Boolean = false,
        ): ReplyTarget {
            val visible = messages.filter { it.message.text.isNotBlank() }
            val lastMine = visible.indexOfLast { it.message.isMe }
            val after = visible.drop(lastMine + 1)
            if (after.isNotEmpty()) return Reply(rank(after, isGroup, myNames))
            if (lastMine < 0) return if (followUpAnyway) FollowUp(FollowUpReason.Asked) else NothingToReply
            val mine = visible[lastMine]
            return when {
                isOlderThan(mine, now, FOLLOW_UP_AFTER) -> FollowUp(FollowUpReason.Stale)
                isQuestion(mine.message.text) -> FollowUp(FollowUpReason.UnansweredQuestion)
                followUpAnyway -> FollowUp(FollowUpReason.Asked)
                else -> NothingToReply
            }
        }

        /** Groups: mentions of me and replies to me first. Both parts keep their order. */
        private fun rank(
            targets: List<PromptMessage>,
            isGroup: Boolean,
            myNames: List<String>,
        ): List<PromptMessage> {
            if (!isGroup) return targets
            val (first, rest) = targets.partition { it.repliesToMe || mentionsMe(it.message.text, myNames) }
            return first + rest
        }

        /** True if the message's time is known and lies more than [limit] before [now]. */
        fun isOlderThan(
            message: PromptMessage,
            now: ZonedDateTime,
            limit: Duration,
        ): Boolean {
            val sent = sentAt(message, now) ?: return false
            return Duration.between(sent, now.toInstant()) > limit
        }

        private fun sentAt(
            message: PromptMessage,
            now: ZonedDateTime,
        ) = message.date
            ?.takeIf { !message.dateIsApprox }
            ?.let { date -> parseBubbleTime(message.message.timeShown)?.let { date.atTime(it).atZone(now.zone) } }
            ?.toInstant()
            ?: message.firstSeenAt

        private val TIME_12H =
            Regex("""(\d{1,2})[:.](\d{2})\s*([ap])\.?\s*m\.?""", RegexOption.IGNORE_CASE)
        private val TIME_24H = Regex("""(\d{1,2})[:.](\d{2})""")

        /** "6:42 pm", "6:42 PM", "6.42 p.m.", "18:42" (any spaces, including narrow no-break ones). */
        fun parseBubbleTime(shown: String?): LocalTime? {
            val text = shown?.replace(' ', ' ')?.replace(' ', ' ') ?: return null
            TIME_12H.find(text)?.let { m ->
                val hour = m.groupValues[1].toInt()
                val minute = m.groupValues[2].toInt()
                if (hour !in 1..12 || minute > 59) return null
                val pm = m.groupValues[3].equals("p", ignoreCase = true)
                return LocalTime.of(hour % 12 + if (pm) 12 else 0, minute)
            }
            val m = TIME_24H.find(text) ?: return null
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            return if (hour <= 23 && minute <= 59) LocalTime.of(hour, minute) else null
        }

        /**
         * English, Hinglish and Gujlish question words (Latin letters), plus the common ones in
         * Devanagari and Gujarati script. Only a message's first word is checked. Some English
         * auxiliaries are left out on purpose: "will do" and "should be fine" are answers, and "is"
         * and "are" are everyday Hinglish ("is baar nahi", "are yaar").
         */
        private val QUESTION_WORDS =
            setOf(
                // English
                "what",
                "whats",
                "what's",
                "when",
                "where",
                "who",
                "whom",
                "whose",
                "why",
                "how",
                "which",
                "did",
                "does",
                "can",
                "wanna",
                "shall",
                // Hinglish
                "kya",
                "kyaa",
                "kab",
                "kahan",
                "kaha",
                "kahaan",
                "kidhar",
                "kaun",
                "kon",
                "kaunsa",
                "konsa",
                "kitna",
                "kitne",
                "kitni",
                "kyun",
                "kyu",
                "kyon",
                "kaise",
                "kaisa",
                "kaisi",
                // Gujlish
                "kem",
                "shu",
                "su",
                "shun",
                "kyare",
                "kyaare",
                "kone",
                "ketla",
                "ketlu",
                "ketli",
                "kevi",
                "kevu",
                "kayu",
                // Devanagari and Gujarati script
                "क्या",
                "कब",
                "कहाँ",
                "कहां",
                "कौन",
                "कितना",
                "कितने",
                "क्यों",
                "कैसे",
                "શું",
                "ક્યારે",
                "ક્યાં",
                "કોણ",
                "કેમ",
                "કેટલા",
                "કેવી",
            )

        private val FIRST_WORD = Regex("""[\p{L}\p{M}']+""")

        /** Variation selector, zero-width joiner and the BMP symbols often sent as emoji. */
        private val EMOJI_TAIL = setOf('️', '‍', '☺', '♥', '❤', '✌', '☹', '✨')

        /** Ends with a question mark, or starts with a question word ([QUESTION_WORDS]). */
        fun isQuestion(text: String): Boolean {
            // Trailing emoji ("aa raha hai? 😅") do not hide the question mark.
            val trimmed = text.trim().trimEnd { it.isWhitespace() || it.isSurrogate() || it in EMOJI_TAIL }
            if (trimmed.endsWith('?') || trimmed.endsWith('？')) return true
            val first = FIRST_WORD.find(trimmed)?.value?.lowercase() ?: return false
            return first in QUESTION_WORDS
        }

        /** A whole-word match of one of [myNames] (so "@Parag" counts too), any case. */
        fun mentionsMe(
            text: String,
            myNames: List<String>,
        ): Boolean =
            myNames
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .any { name ->
                    Regex("""(?<![\p{L}\p{N}_])${Regex.escape(name)}(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(text)
                }
    }
}
