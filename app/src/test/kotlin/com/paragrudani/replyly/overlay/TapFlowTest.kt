package com.paragrudani.replyly.overlay

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatParser
import com.paragrudani.replyly.parser.ChatParserRegistry
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.policy.BlockReason
import com.paragrudani.replyly.policy.DefaultSensitiveScreenPolicy
import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.screen.ScreenNode
import com.paragrudani.replyly.screen.ScreenSnapshot
import com.paragrudani.replyly.settings.ReplylySettings
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class TapFlowTest {
    private var settings = ReplylySettings()
    private val policy = DefaultSensitiveScreenPolicy(settings = { settings })
    private var snapshot: ScreenSnapshot? = snapshot()
    private val capturedWindows = mutableListOf<Int>()
    private var parsed: ParsedChat? = chat("Asha")

    private val parser =
        object : ChatParser {
            override val packages = emptySet<String>()

            override fun parse(snapshot: ScreenSnapshot): ParsedChat? = parsed
        }
    private val flow =
        TapFlow(
            capture = { windowId ->
                capturedWindows += windowId
                snapshot
            },
            policy = policy,
            parsers = ChatParserRegistry(emptyList(), fallback = parser),
        )

    @Test
    fun `an allowed chat opens the panel with the parsed chat`() =
        runTest {
            assertThat(flow.run(WHATSAPP, 7)).isEqualTo(TapOutcome.Open(chat("Asha")))
            assertThat(capturedWindows).containsExactly(7)
        }

    @Test
    fun `a blocked app reads nothing at all`() =
        runTest {
            settings = ReplylySettings(blockedPackages = setOf(WHATSAPP))

            assertThat(flow.run(WHATSAPP, 7)).isEqualTo(TapOutcome.Blocked(BlockReason.BLOCKLISTED_APP))
            assertThat(capturedWindows).isEmpty()
        }

    @Test
    fun `a secure window is blocked`() =
        runTest {
            snapshot = snapshot(secureWindow = true)

            assertThat(flow.run(WHATSAPP, 7)).isEqualTo(TapOutcome.Blocked(BlockReason.SECURE_WINDOW))
        }

    @Test
    fun `a password field on screen is blocked before parsing`() =
        runTest {
            snapshot = snapshot(ScreenNode(bounds = Bounds.EMPTY, password = true))
            parsed = null

            assertThat(flow.run(WHATSAPP, 7)).isEqualTo(TapOutcome.Blocked(BlockReason.PASSWORD_FIELD))
        }

    @Test
    fun `a blocklisted chat is blocked after parsing, the chat never reaches the panel`() =
        runTest {
            settings = ReplylySettings(blockedChatKeys = setOf("$WHATSAPP:Doctor"))
            parsed = chat("Doctor")

            assertThat(flow.run(WHATSAPP, 7)).isEqualTo(TapOutcome.Blocked(BlockReason.BLOCKLISTED_CHAT))
        }

    @Test
    fun `a screen that is not a chat`() =
        runTest {
            parsed = null

            assertThat(flow.run(WHATSAPP, 7)).isEqualTo(TapOutcome.NotAChat)
        }

    @Test
    fun `no active window, or the active window now belongs to another app`() =
        runTest {
            snapshot = null
            assertThat(flow.run(WHATSAPP, 7)).isEqualTo(TapOutcome.NotAChat)

            snapshot = snapshot().copy(packageName = "com.android.launcher")
            assertThat(flow.run(WHATSAPP, 7)).isEqualTo(TapOutcome.NotAChat)
        }

    private fun snapshot(
        vararg nodes: ScreenNode,
        secureWindow: Boolean = false,
    ) = ScreenSnapshot(
        packageName = WHATSAPP,
        capturedAt = Instant.EPOCH,
        screenWidth = 1080,
        screenHeight = 2340,
        root = ScreenNode(bounds = Bounds(0, 0, 1080, 2340), children = nodes.toList()),
        secureWindow = secureWindow,
    )

    private fun chat(title: String) =
        ParsedChat(WHATSAPP, title, isGroup = false, messages = emptyList(), historyApplies = true)

    private companion object {
        const val WHATSAPP = "com.whatsapp"
    }
}
