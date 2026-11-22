package com.paragrudani.replyly.llm

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
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
         * - A message with blank text (media without a caption) is a turn marker: it can be my last
         *   message, and a photo they send after my message answers it. It is never a target.
         * - Others wrote text after my last message (or I never wrote): [Reply] with exactly those
         *   messages. No such messages and no message of mine: [NothingToReply].
         * - Only their media after my last message: they answered, so [NothingToReply].
         * - My message is last: [FollowUp] when it is older than [FOLLOW_UP_AFTER] or is a question
         *   ([isQuestion]); otherwise [NothingToReply].
         * - [followUpAnyway] (the "anyway" tap) only ever turns [NothingToReply] into
         *   [FollowUpReason.Asked].
         *
         * Age: see [sentAt]. With no known time the message counts as recent.
         */
        fun decide(
            messages: List<PromptMessage>,
            now: ZonedDateTime,
            isGroup: Boolean,
            myNames: List<String> = emptyList(),
            followUpAnyway: Boolean = false,
        ): ReplyTarget {
            val lastMine = messages.indexOfLast { it.message.isMe }
            val after = messages.drop(lastMine + 1)
            val targets = after.filter { it.message.text.isNotBlank() }
            if (targets.isNotEmpty()) return Reply(rank(targets, isGroup, myNames))
            val decided =
                when {
                    lastMine < 0 || after.isNotEmpty() -> NothingToReply
                    isOlderThan(messages[lastMine], now, FOLLOW_UP_AFTER) -> FollowUp(FollowUpReason.Stale)
                    isQuestion(messages[lastMine].message.text) -> FollowUp(FollowUpReason.UnansweredQuestion)
                    else -> NothingToReply
                }
            return if (decided == NothingToReply && followUpAnyway) FollowUp(FollowUpReason.Asked) else decided
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
            val sent = sentAt(message, now.zone) ?: return false
            return Duration.between(sent, now.toInstant()) > limit
        }

        /**
         * When the message was sent, or null when unknown. Never earlier than the real send time, so a
         * message is never called stale too early:
         * - exact date and a bubble time: that local date and time (the later instant if the clock
         *   was turned back and the time happened twice);
         * - otherwise, with [PromptMessage.firstSeenAt] F and a bubble time t: the latest instant
         *   at or before F whose local time is t. The real send time S is at or before F and has
         *   local time t, so this is at or after S;
         * - otherwise F itself.
         */
        fun sentAt(
            message: PromptMessage,
            zone: ZoneId,
        ): Instant? {
            val time = parseBubbleTime(message.message.timeShown)
            val date = message.date
            if (date != null && !message.dateIsApprox && time != null) {
                return date
                    .atTime(time)
                    .atZone(zone)
                    .withLaterOffsetAtOverlap()
                    .toInstant()
            }
            val seen = message.firstSeenAt ?: return null
            if (time == null) return seen
            val day = seen.atZone(zone).toLocalDate()
            return listOf(day, day.minusDays(1))
                .flatMap { d ->
                    val local = d.atTime(time).atZone(zone)
                    listOf(local.withEarlierOffsetAtOverlap(), local.withLaterOffsetAtOverlap())
                }.map { it.toInstant() }
                .filter { it <= seen }
                .maxOrNull() ?: seen
        }

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
         * Devanagari and Gujarati script, checked as the first word of the last sentence. Some English
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

        /**
         * Words that make a statement a yes/no question when they end it: Hindi and Gujarati put
         * the marker last ("khana khaya kya", "tu aa raha hai na", "aavse ke").
         */
        private val LAST_WORD_MARKERS = setOf("kya", "kyaa", "na", "ke", "ne", "क्या", "ना")

        /** First words that ask only with a subject and a verb after them: "did it" and "can do" are statements. */
        private val AUXILIARIES = setOf("did", "does", "can", "shall")

        /** Exclamations that start with a question word: "kyun nahi, done", "what a match", "how cool is that". */
        private val EXCLAMATION =
            Regex(
                """^(?:(?:kyun|kyu|kyon|why)\s+(?:nahi|nahin|not)\b|what\s+an?\b|how\s+\S+\s+(?:is|was)\s+(?:that|this)$)""",
            )

        private val WORD = Regex("""[\p{L}\p{M}']+""")

        /** Sentence ends other than "?": the question mark itself stays inside its sentence. */
        private val SENTENCE_END = Regex("""[.!！।\n]+""")

        /** Variation selector, zero-width joiner and the BMP symbols often sent as emoji. */
        private val EMOJI_TAIL = setOf('️', '‍', '☺', '♥', '❤', '✌', '☹', '✨')

        /**
         * True for a question, judged on the last sentence (text after the last ".", "!", "।" or
         * line break), with trailing emoji ignored:
         * - a message ending in "!" with no "?" anywhere is not a question ("kya baat hai!");
         * - a "?" anywhere in the last sentence is ("aa raha hai? bata");
         * - so is a last word from [LAST_WORD_MARKERS] ("kal free hai kya");
         * - an [EXCLAMATION] is not ("what a match"), nor a 2-word [AUXILIARIES] start ("did it");
         * - otherwise, a first word from [QUESTION_WORDS] is.
         */
        fun isQuestion(text: String): Boolean {
            // Trailing emoji ("aa raha hai? 😅") do not hide the question mark.
            val trimmed = text.trim().trimEnd { it.isWhitespace() || it.isSurrogate() || it in EMOJI_TAIL }
            val hasQuestionMark = trimmed.any { it == '?' || it == '？' }
            if (!hasQuestionMark && (trimmed.endsWith('!') || trimmed.endsWith('！'))) return false
            val last = trimmed.split(SENTENCE_END).lastOrNull { it.isNotBlank() }?.trim() ?: return false
            if (last.any { it == '?' || it == '？' }) return true
            val lower = last.lowercase()
            val words = WORD.findAll(lower).map { it.value }.toList()
            if (words.isEmpty()) return false
            if (words.last() in LAST_WORD_MARKERS) return true
            if (EXCLAMATION.containsMatchIn(words.joinToString(" "))) return false
            if (words.first() in AUXILIARIES && words.size < 3) return false
            return words.first() in QUESTION_WORDS
        }

        /** A letter, a combining mark (Devanagari vowel signs), a digit or "_": part of a longer name. */
        private const val NAME_CHAR = """[\p{L}\p{M}\p{N}_]"""

        /** A whole-word match of one of [myNames] (so "@Parag" counts too), any case. */
        fun mentionsMe(
            text: String,
            myNames: List<String>,
        ): Boolean =
            myNames
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .any { name ->
                    Regex("""(?<!$NAME_CHAR)${Regex.escape(name)}(?!$NAME_CHAR)""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(text)
                }
    }
}
