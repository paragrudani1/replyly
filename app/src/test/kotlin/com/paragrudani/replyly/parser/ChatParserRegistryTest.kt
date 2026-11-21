package com.paragrudani.replyly.parser

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.testing.Fixtures
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatParserRegistryTest {
    private class FakeParser(
        override val packages: Set<String>,
        val name: String,
    ) : ChatParser {
        override fun parse(snapshot: com.paragrudani.replyly.screen.ScreenSnapshot) =
            ParsedChat(snapshot.packageName, name, isGroup = false, messages = emptyList(), historyApplies = true)
    }

    private val whatsapp = FakeParser(setOf(TargetApps.WHATSAPP, TargetApps.WHATSAPP_BUSINESS), "wa")
    private val telegram = FakeParser(setOf(TargetApps.TELEGRAM), "tg")
    private val generic = FakeParser(emptySet(), "generic")
    private val registry = ChatParserRegistry(listOf(whatsapp, telegram), generic)

    @Test
    fun `picks the parser by package`() {
        assertThat(registry.parserFor(TargetApps.WHATSAPP)).isSameInstanceAs(whatsapp)
        assertThat(registry.parserFor(TargetApps.WHATSAPP_BUSINESS)).isSameInstanceAs(whatsapp)
        assertThat(registry.parserFor(TargetApps.TELEGRAM)).isSameInstanceAs(telegram)
    }

    @Test
    fun `unknown packages fall back to the generic parser`() {
        assertThat(registry.parserFor("com.slack")).isSameInstanceAs(generic)
        assertThat(registry.parse(Fixtures.snapshot("sample/minimal_chat.json"))?.title).isEqualTo("generic")
    }

    @Test
    fun `two parsers cannot claim one package`() {
        assertThrows(IllegalStateException::class.java) {
            ChatParserRegistry(listOf(whatsapp, FakeParser(setOf(TargetApps.WHATSAPP), "dup")), generic)
        }
    }

    @Test
    fun `chat key round trips through its encoded form`() {
        val key = ChatKey(TargetApps.WHATSAPP, "Mom: home")

        assertThat(key.encoded).isEqualTo("com.whatsapp:Mom: home")
        assertThat(ChatKey.decode(key.encoded)).isEqualTo(key)
    }
}
