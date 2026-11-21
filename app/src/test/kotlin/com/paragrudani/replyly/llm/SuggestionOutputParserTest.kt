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
}
