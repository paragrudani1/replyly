package com.paragrudani.replyly.ui.debug

import android.accessibilityservice.AccessibilityService
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatParser
import com.paragrudani.replyly.parser.ChatParserRegistry
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.parser.Parsers
import com.paragrudani.replyly.policy.BlockReason
import com.paragrudani.replyly.policy.DefaultSensitiveScreenPolicy
import com.paragrudani.replyly.policy.SensitiveScreenPolicy
import com.paragrudani.replyly.policy.SensitivityVerdict
import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.screen.ScreenNode
import com.paragrudani.replyly.screen.ScreenSnapshot
import com.paragrudani.replyly.service.AccessibilityHost
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.testing.Fixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DumpCapturerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeHost(
        var screen: ScreenSnapshot?,
    ) : AccessibilityHost {
        var captures = 0

        override val service: AccessibilityService get() = error("not used")

        override fun captureActiveWindow(): ScreenSnapshot? = error("the dump must use the checked capture")

        override suspend fun captureActiveWindowChecked(): ScreenSnapshot? {
            captures++
            return screen
        }
    }

    private val store by lazy { DebugDumpStore(folder.root) }
    private val hosts = MutableStateFlow<AccessibilityHost?>(null)

    /** The real policy, with [settings] already loaded. */
    private fun TestScope.capturer(
        registry: ChatParserRegistry = Parsers.registry(),
        settings: ReplylySettings = ReplylySettings(),
        policy: SensitiveScreenPolicy = DefaultSensitiveScreenPolicy(settings = { settings }),
    ) = DumpCapturer(
        hosts = hosts,
        store = store,
        policy = policy,
        registry = registry,
        ownPackage = "com.paragrudani.replyly",
        workDispatcher = StandardTestDispatcher(testScheduler),
    )

    private val crashingParser =
        object : ChatParser {
            override val packages = setOf("com.whatsapp")

            override fun parse(snapshot: ScreenSnapshot): ParsedChat? = error("parser bug")
        }
    private val nullParser =
        object : ChatParser {
            override val packages = emptySet<String>()

            override fun parse(snapshot: ScreenSnapshot): ParsedChat? = null
        }
    private val crashing get() = ChatParserRegistry(listOf(crashingParser), crashingParser)
    private val readsNothing get() = ChatParserRegistry(emptyList(), nullParser)

    private val whatsapp = Fixtures.snapshot("whatsapp/synthetic-one_to_one.json")

    @Test
    fun `without the accessibility service nothing is captured`() =
        runTest {
            assertThat(capturer().capture(5.seconds)).isEqualTo(DumpCapturer.Outcome.ServiceOff)
        }

    @Test
    fun `waits, counting down, then saves the screen that is on top`() =
        runTest {
            val host = FakeHost(whatsapp)
            hosts.value = host
            val ticks = mutableListOf<Int>()
            var outcome: DumpCapturer.Outcome? = null
            backgroundScope.launch { outcome = capturer().capture(5.seconds) { ticks += it } }

            advanceTimeBy(4_500)
            assertThat(host.captures).isEqualTo(0)
            advanceTimeBy(1_000)

            assertThat(ticks).containsExactly(5, 4, 3, 2, 1).inOrder()
            assertThat(host.captures).isEqualTo(1)
            val saved = outcome as DumpCapturer.Outcome.Saved
            assertThat(saved.entry.packageName).isEqualTo("com.whatsapp")
            assertThat(store.read(saved.entry.name)).isEqualTo(whatsapp)
        }

    @Test
    fun `a service that stops during the countdown is reported`() =
        runTest {
            hosts.value = FakeHost(whatsapp)
            var outcome: DumpCapturer.Outcome? = null
            backgroundScope.launch { outcome = capturer().capture(5.seconds) }
            advanceTimeBy(2_000)
            hosts.value = null
            advanceTimeBy(4_000)

            assertThat(outcome).isEqualTo(DumpCapturer.Outcome.ServiceOff)
        }

    @Test
    fun `still on replyly after the countdown is not saved`() =
        runTest {
            hosts.value = FakeHost(whatsapp.copy(packageName = "com.paragrudani.replyly"))

            assertThat(capturer().capture(0.seconds)).isEqualTo(DumpCapturer.Outcome.StillOnReplyly)
            assertThat(store.list()).isEmpty()
        }

    @Test
    fun `no active window is reported`() =
        runTest {
            hosts.value = FakeHost(null)

            assertThat(capturer().capture(0.seconds)).isEqualTo(DumpCapturer.Outcome.NoWindow)
        }

    @Test
    fun `a screen the policy blocks is never saved`() =
        runTest {
            hosts.value = FakeHost(whatsapp)
            val blockChat =
                SensitiveScreenPolicy { context ->
                    if (context.chatKey?.title == "Asha Patel") {
                        SensitivityVerdict.Blocked(BlockReason.BLOCKLISTED_CHAT)
                    } else {
                        SensitivityVerdict.Allowed
                    }
                }

            assertThat(capturer(policy = blockChat).capture(0.seconds))
                .isEqualTo(DumpCapturer.Outcome.Blocked(BlockReason.BLOCKLISTED_CHAT))
            assertThat(store.list()).isEmpty()
        }

    @Test
    fun `a saved dump says whether the chat blocklist could be checked`() =
        runTest {
            hosts.value = FakeHost(whatsapp)

            val parsed = capturer().capture(0.seconds) as DumpCapturer.Outcome.Saved
            val crashed = capturer(registry = crashing).capture(0.seconds)

            assertThat(parsed.chatKeyChecked).isTrue()
            assertThat((crashed as DumpCapturer.Outcome.Saved).chatKeyChecked).isFalse()
        }

    @Test
    fun `a blocklisted app is never saved, even when the parser fails`() =
        runTest {
            hosts.value = FakeHost(whatsapp)
            val settings = ReplylySettings(blockedPackages = setOf("com.whatsapp"))

            listOf(Parsers.registry(), crashing, readsNothing)
                .forEach { registry ->
                    assertThat(capturer(registry = registry, settings = settings).capture(0.seconds))
                        .isEqualTo(DumpCapturer.Outcome.Blocked(BlockReason.BLOCKLISTED_APP))
                }
            assertThat(store.list()).isEmpty()
        }

    @Test
    fun `a secure window is never saved`() =
        runTest {
            // What the checked capture returns for a FLAG_SECURE window (or one it could not check).
            hosts.value = FakeHost(whatsapp.copy(root = ScreenNode(bounds = Bounds.EMPTY), secureWindow = true))

            assertThat(capturer().capture(0.seconds))
                .isEqualTo(DumpCapturer.Outcome.Blocked(BlockReason.SECURE_WINDOW))
            assertThat(store.list()).isEmpty()
        }

    @Test
    fun `a blocklisted chat is never saved`() =
        runTest {
            hosts.value = FakeHost(whatsapp)
            val settings = ReplylySettings(blockedChatKeys = setOf("com.whatsapp:Asha Patel"))

            assertThat(capturer(settings = settings).capture(0.seconds))
                .isEqualTo(DumpCapturer.Outcome.Blocked(BlockReason.BLOCKLISTED_CHAT))
            assertThat(store.list()).isEmpty()
        }
}
