package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SuggestionOutputParserTest {
    private fun ok(raw: String) = SuggestionOutputParser.parse(raw) as ParsedSuggestions.Ok

    private fun invalid(raw: String) = SuggestionOutputParser.parse(raw) as ParsedSuggestions.Invalid

    private val three =
        """{"suggestions":[{"intent":"agree","text":"Haan done"},""" +
            """{"intent":"decline","text":"Aaj nahi ho payega"},{"intent":"ask","text":"Kitne baje?"}]}"""

    @Test
    fun `parses plain JSON with 3 suggestions`() {
        assertThat(ok(three).suggestions)
            .containsExactly(
                Suggestion("agree", "Haan done"),
                Suggestion("decline", "Aaj nahi ho payega"),
                Suggestion("ask", "Kitne baje?"),
            ).inOrder()
    }

    @Test
    fun `strips code fences and stray text around the JSON`() {
        val raw = "Sure! Here are your replies:\n```json\n$three\n```\nHope this helps."
        assertThat(ok(raw).suggestions.map { it.intent }).containsExactly("agree", "decline", "ask").inOrder()
    }

    @Test
    fun `trims text and normalises intent case`() {
        val raw =
            """{"suggestions":[{"intent":" Agree ","text":"  ok  "},{"intent":"DECLINE","text":"no"},""" +
                """{"intent":"ask","text":"when?"}]}"""
        assertThat(ok(raw).suggestions.first()).isEqualTo(Suggestion("agree", "ok"))
        assertThat(ok(raw).suggestions[1].intent).isEqualTo("decline")
    }

    @Test
    fun `takes the first 3 when there are more`() {
        val raw =
            """{"suggestions":[{"intent":"a","text":"1"},{"intent":"b","text":"2"},""" +
                """{"intent":"c","text":"3"},{"intent":"d","text":"4"}]}"""
        assertThat(ok(raw).suggestions.map { it.text }).containsExactly("1", "2", "3").inOrder()
    }

    @Test
    fun `skips invalid items and still succeeds with 3 valid ones`() {
        val raw =
            """{"suggestions":[{"intent":"agree","text":"   "},{"intent":"agree","text":"yes"},""" +
                """{"text":"no intent"},{"intent":"agree","text":"dup intent"},""" +
                """{"intent":"decline","text":"no"},{"intent":"ask","text":"why?"}]}"""
        assertThat(ok(raw).suggestions)
            .containsExactly(Suggestion("agree", "yes"), Suggestion("decline", "no"), Suggestion("ask", "why?"))
            .inOrder()
    }

    @Test
    fun `fewer than 3 valid suggestions is invalid`() {
        val raw = """{"suggestions":[{"intent":"agree","text":"yes"},{"intent":"agree","text":"sure"}]}"""
        assertThat(invalid(raw).reason).contains("2")
    }

    @Test
    fun `unparseable output is invalid`() {
        assertThat(invalid("I cannot help with that.").reason).isNotEmpty()
        assertThat(invalid("""{"suggestions": [ {"intent": "agree", "text": "yes"} """).reason).isNotEmpty()
        assertThat(invalid("""{"replies":[]}""").reason).isNotEmpty()
        assertThat(invalid("").reason).isNotEmpty()
    }

    @Test
    fun `reason never echoes the model output`() {
        val secret = "Meet me at the secret place"
        assertThat(invalid(secret).reason).doesNotContain("secret")
    }

    @Test
    fun `single mode expects exactly one suggestion`() {
        val raw = """```{"suggestions":[{"intent":"whatever","text":"Theek hai"}]}```"""
        val parsed = SuggestionOutputParser.parse(raw, expected = 1) as ParsedSuggestions.Ok
        assertThat(parsed.suggestions).containsExactly(Suggestion("whatever", "Theek hai"))
    }

    @Test
    fun `tolerates trailing commas`() {
        val raw =
            """{"suggestions":[{"intent":"agree","text":"yes",},{"intent":"decline","text":"no"},""" +
                """{"intent":"ask","text":"when?"},],}"""
        val parsed = SuggestionOutputParser.parse(raw) as ParsedSuggestions.Ok
        assertThat(parsed.suggestions.map { it.intent }).containsExactly("agree", "decline", "ask").inOrder()
    }

    // --- X: the post length (#30 PR 2)

    private fun json(vararg cards: Pair<String, String>) =
        """{"analysis":"a","suggestions":[""" +
            cards.joinToString(",") { (intent, text) -> """{"intent":"$intent","text":"$text"}""" } + "]}"

    private fun x(
        raw: String,
        limit: Int = 280,
    ) = SuggestionOutputParser.parse(raw, lengthLimit = limit)

    @Test
    fun `X - cards within the limit are unchanged and not marked`() {
        val parsed =
            x(
                json("agree" to "Fair point.", "question" to "Which part?", "insight" to "Sync is the hard bit."),
            ) as ParsedSuggestions.Ok
        assertThat(parsed.suggestions.map { it.trimmed }).containsExactly(false, false, false)
        assertThat(parsed.droppedOverLimit).isEqualTo(0)
    }

    @Test
    fun `X - a card over the limit is trimmed at its last sentence that fits and marked`() {
        val first =
            "Agree with the local-first part: " + "start with a store that can merge, ".repeat(4).trimEnd(' ', ',') +
                "."
        val long = "$first " + "Sync later is where it gets hard, ".repeat(10) + "honestly."
        val parsed =
            x(
                json("agree" to long, "question" to "Which part?", "insight" to "Sync is the hard bit."),
            ) as ParsedSuggestions.Ok
        assertThat(parsed.suggestions[0]).isEqualTo(Suggestion("agree", first, trimmed = true))
        assertThat(parsed.suggestions[1].trimmed).isFalse()
        // A first sentence under half the limit loses to the longest word cut (review r1 S4).
        val shortFirst =
            "Agree with the local-first part. " + "Sync later is where it gets hard, ".repeat(10) + "honestly."
        val cut =
            (
                x(
                    json("agree" to shortFirst, "question" to "Which part?", "insight" to "Sync is hard."),
                ) as ParsedSuggestions.Ok
            ).suggestions[0]
        assertThat(cut.trimmed).isTrue()
        assertThat(XLength.weighted(cut.text)).isAtLeast(XLength.STANDARD_LIMIT / 2)
    }

    @Test
    fun `X - the limit follows the setting`() {
        val text = "word ".repeat(100).trim() // 499
        val short =
            x(
                json("agree" to text, "question" to "Which part?", "insight" to "ok then"),
                limit = 280,
            ) as ParsedSuggestions.Ok
        val medium =
            x(
                json("agree" to text, "question" to "Which part?", "insight" to "ok then"),
                limit = 500,
            ) as ParsedSuggestions.Ok
        assertThat(XLength.weighted(short.suggestions[0].text)).isAtMost(280)
        assertThat(short.suggestions[0].trimmed).isTrue()
        assertThat(medium.suggestions[0]).isEqualTo(Suggestion("agree", text))
    }

    @Test
    fun `X - a card that cannot be trimmed to something sensible is dropped, the others stay`() {
        val oneWord = "a".repeat(300)
        val tiny = "Yes. " + "b".repeat(300)
        val parsed =
            x(
                json("agree" to oneWord, "question" to "Which part do you mean?", "insight" to tiny),
            ) as ParsedSuggestions.Ok
        assertThat(parsed.suggestions).containsExactly(Suggestion("question", "Which part do you mean?"))
        assertThat(parsed.droppedOverLimit).isEqualTo(2)
    }

    @Test
    fun `X - every card dropped is invalid output`() {
        val oneWord = "a".repeat(300)
        val parsed = x(json("agree" to oneWord, "question" to oneWord, "insight" to oneWord))
        assertThat(parsed).isEqualTo(ParsedSuggestions.Invalid("all 3 suggestions over the length limit of 280"))
    }

    @Test
    fun `X - fewer than 3 valid cards from the model is still invalid, before any trimming`() {
        val parsed = x(json("agree" to "ok", "agree" to "fine"))
        assertThat(parsed).isInstanceOf(ParsedSuggestions.Invalid::class.java)
    }

    // --- X: hashtags and @-mentions (#46)

    private val stripBoth = XTags.Keep(hashtags = false, mentions = false)

    @Test
    fun `X - hashtags and mentions are stripped unless kept, before the length check`() {
        val parsed =
            SuggestionOutputParser.parse(
                json(
                    "agree" to "@ravi_k Local-first wins, love #Kotlin for this. #buildinpublic",
                    "question" to "How do you merge as @asha_dev asked?",
                    "insight" to "Sync is the hard bit.",
                ),
                lengthLimit = 280,
                xTags = stripBoth,
            ) as ParsedSuggestions.Ok
        assertThat(parsed.suggestions.map { it.text })
            .containsExactly(
                "Local-first wins, love Kotlin for this.",
                "How do you merge as asha_dev asked?",
                "Sync is the hard bit.",
            ).inOrder()
        assertThat(parsed.suggestions.map { it.trimmed }).containsExactly(false, false, false)

        val kept =
            SuggestionOutputParser.parse(
                json("agree" to "Ship it #buildinpublic", "question" to "Why @ravi_k?", "insight" to "Sync is hard."),
                lengthLimit = 280,
                xTags = XTags.Keep(hashtags = true, mentions = false),
            ) as ParsedSuggestions.Ok
        assertThat(
            kept.suggestions.map { it.text },
        ).containsExactly("Ship it #buildinpublic", "Why ravi_k?", "Sync is hard.")
    }

    @Test
    fun `X - stripping comes before trimming, so a reply over only by its tags is not trimmed`() {
        val body = "word ".repeat(55).trim() // 274
        val parsed =
            SuggestionOutputParser.parse(
                json("agree" to "$body #Kotlin #dev", "question" to "Which part?", "insight" to "Sync is hard."),
                lengthLimit = 280,
                xTags = stripBoth,
            ) as ParsedSuggestions.Ok
        assertThat(parsed.suggestions[0]).isEqualTo(Suggestion("agree", body))
    }

    @Test
    fun `X - a card of only tags is dropped without the length notice`() {
        val parsed =
            SuggestionOutputParser.parse(
                json("agree" to "#Kotlin @ravi_k", "question" to "Which part?", "insight" to "Sync is hard."),
                lengthLimit = 280,
                xTags = stripBoth,
            ) as ParsedSuggestions.Ok
        assertThat(parsed.suggestions.map { it.intent }).containsExactly("question", "insight").inOrder()
        assertThat(parsed.droppedOverLimit).isEqualTo(0)
        val none =
            SuggestionOutputParser.parse(
                json("agree" to "#a", "question" to "@b", "insight" to "#c #d"),
                lengthLimit = 280,
                xTags = stripBoth,
            )
        assertThat(none).isEqualTo(ParsedSuggestions.Invalid("no suggestion left after the X checks"))
    }

    @Test
    fun `chats ignore the length`() {
        val long = "word ".repeat(100).trim()
        val parsed =
            SuggestionOutputParser.parse(
                json("agree" to long, "decline" to "no", "ask" to "when?"),
            ) as ParsedSuggestions.Ok
        assertThat(parsed.suggestions[0]).isEqualTo(Suggestion("agree", long))
    }
}
