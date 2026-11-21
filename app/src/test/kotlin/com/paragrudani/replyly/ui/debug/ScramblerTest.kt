package com.paragrudani.replyly.ui.debug

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.Parsers
import com.paragrudani.replyly.screen.ScreenNode
import com.paragrudani.replyly.testing.Fixtures
import org.junit.Test

class ScramblerTest {
    private val scrambler = Scrambler()

    private fun shape(text: String): String =
        text
            .map {
                when {
                    it.isUpperCase() -> 'A'
                    it.isLowerCase() -> 'a'
                    else -> it
                }
            }.joinToString("")

    @Test
    fun `letters change, case, length, digits, emoji, punctuation and spaces stay`() {
        val original = "Kal 6:30 tak aa jaunga, OK? 🙏🏽 +91 98765"
        val scrambled = scrambler.scrambleText(original)

        assertThat(scrambled).isNotEqualTo(original)
        assertThat(scrambled.length).isEqualTo(original.length)
        assertThat(shape(scrambled)).isEqualTo(shape(original))
        assertThat(scrambled.filter { !it.isLetter() }).isEqualTo(original.filter { !it.isLetter() })
    }

    @Test
    fun `the same text scrambles the same way within one dump, differently in another`() {
        assertThat(scrambler.scrambleText("ok")).isEqualTo(scrambler.scrambleText("ok"))
        val long = "Are you coming to Riya's birthday on Saturday evening"
        assertThat(Scrambler().scrambleText(long)).isNotEqualTo(Scrambler().scrambleText(long))
    }

    @Test
    fun `screen chrome the parser needs stays readable`() {
        listOf(
            "TODAY",
            "9:02\u202Fpm",
            "Messages and calls are end-to-end encrypted. Tap to learn more.",
            "2 UNREAD MESSAGES",
            "online",
            "Edited",
            "5 members, 2 online",
        ).forEach { assertThat(scrambler.scrambleText(it)).isEqualTo(it) }

        val status = scrambler.scrambleText("Kal milte hai?\nSent today at 6:42 PM, Read")
        assertThat(status).endsWith("\nSent today at 6:42 PM, Read")
        assertThat(status).doesNotContain("milte")
    }

    @Test
    fun `scrambles text, descriptions and hints but keeps ids, classes, bounds and package`() {
        val original = Fixtures.snapshot("whatsapp/synthetic-group.json")
        val scrambled = scrambler.scramble(original)

        fun ScreenNode.flat(): List<ScreenNode> = listOf(this) + children.flatMap { it.flat() }
        val before = original.root.flat()
        val after = scrambled.root.flat()
        assertThat(scrambled.packageName).isEqualTo(original.packageName)
        assertThat(after.map { Triple(it.viewId, it.className, it.bounds) })
            .isEqualTo(before.map { Triple(it.viewId, it.className, it.bounds) })
        assertThat(after.mapNotNull { it.text }).doesNotContain("Weekend Trek")
        assertThat(after.mapNotNull { it.hint }).doesNotContain("Message")
    }

    @Test
    fun `a scrambled dump still parses to the same structure`() {
        listOf("whatsapp/synthetic-group-no_ids.json", "telegram/synthetic-group.json").forEach { path ->
            val original = Fixtures.snapshot(path)
            val plain = Parsers.registry().parse(original)!!
            val scrambled = Parsers.registry().parse(scrambler.scramble(original))!!

            assertThat(scrambled.isGroup).isEqualTo(plain.isGroup)
            assertThat(scrambled.messages.map { it.isMe to it.timeShown })
                .isEqualTo(plain.messages.map { it.isMe to it.timeShown })
            assertThat(scrambled.messages.map { it.text.length }).isEqualTo(plain.messages.map { it.text.length })
            assertThat(scrambled.messages.map { it.text }).containsNoneIn(
                plain.messages.map { it.text }.filter {
                    it.length >
                        3
                },
            )
        }
    }
}
