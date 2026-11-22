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
    fun `only the narrow English separator list stays whole, not every date the parsers read`() {
        listOf("TODAY", "Yesterday", "Monday", "21 November 2026", "November 21, 2026", "21/11/2026")
            .forEach { assertThat(Scrambler.isKeptWhole(it)).isTrue() }
        // Bubble texts that ChatDates would also read as a separator ("tomorrow", "today", a date).
        listOf("कल", "आज", "21.11.26", "10-10-10", "शनिवार", "Sat 21 Nov")
            .forEach { assertThat(Scrambler.isKeptWhole(it)).isFalse() }
        val fixed = Scrambler(ByteArray(32) { it.toByte() })
        assertThat(fixed.scrambleText("कल")).isNotEqualTo("कल")
        assertThat(fixed.scrambleText("आज")).isNotEqualTo("आज")
    }

    @Test
    fun `text that only starts like chrome is scrambled whole, kept words included`() {
        val fixed = Scrambler(ByteArray(32) { it.toByte() })
        listOf(
            "Active on Sunday? call Priya at 98765 43210",
            "typing this from the hospital, Rahul is ok",
            "last seen at Raj's house near Andheri station",
            "5 members of the Mehta family are coming",
            "Messages are end-to-end encrypted, my password is hunter2",
            "Waiting for this message from Neha about the loan",
            "Tap here for the address: 12 MG Road",
            "I am at you know where, sent from Priya",
            "Sent from my phone at 6:30",
        ).forEach { original ->
            val scrambled = fixed.scrambleText(original)
            val words = Regex("""\p{L}{2,}""").findAll(original).map { it.value }.toList()
            val kept =
                Regex("""\p{L}{2,}""")
                    .findAll(scrambled)
                    .map { it.value }
                    .filter { it in words }
                    .toList()

            assertThat(kept).isEmpty()
            assertThat(shape(scrambled)).isEqualTo(shape(original))
        }
    }

    @Test
    fun `the same name scrambles the same way in every cell, subtitle and member list`() {
        val a = scrambler.scrambleText("Asha\nGood morning\nReceived today at 8:02 AM").lines()
        val b = scrambler.scrambleText("Asha\nok\nReceived today at 8:03 AM").lines()
        val typing = scrambler.scrambleText("Asha is typing…")
        val members = scrambler.scrambleText("Asha, Ravi, You")

        assertThat(a.first()).isEqualTo(b.first())
        assertThat(a.first()).isNotEqualTo("Asha")
        assertThat(typing).isEqualTo("${a.first()} is typing…")
        assertThat(members).isEqualTo("${a.first()}, ${scrambler.scrambleText("Ravi")}, You")
        assertThat(a.last()).isEqualTo("Received today at 8:02 AM")
        // A forwarded line keeps its prefix only inside a Telegram cell.
        val forwarded = scrambler.scrambleText("Ravi\nForwarded from Trek Club\nRoute map\nReceived today at 8:06 AM")
        assertThat(forwarded.lines()[1]).startsWith("Forwarded from ")
        assertThat(forwarded.lines()[1]).doesNotContain("Trek")
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

            // Same sender, same scrambled name: the runs of senders keep their shape.
            fun runs(senders: List<String?>) = senders.map { s -> s?.let { senders.indexOf(it) } }
            assertThat(runs(scrambled.messages.map { it.sender })).isEqualTo(runs(plain.messages.map { it.sender }))
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
