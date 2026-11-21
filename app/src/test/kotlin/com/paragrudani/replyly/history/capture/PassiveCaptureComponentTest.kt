package com.paragrudani.replyly.history.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.feedback.InMemoryFeedbackLog
import com.paragrudani.replyly.history.HistoryReadMerger
import com.paragrudani.replyly.history.HistoryRetention
import com.paragrudani.replyly.history.InMemoryHistoryRepository
import com.paragrudani.replyly.history.MergeOutcome
import com.paragrudani.replyly.history.MutableClock
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ChatParser
import com.paragrudani.replyly.parser.ChatParserRegistry
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.policy.BlockReason
import com.paragrudani.replyly.policy.ScreenContext
import com.paragrudani.replyly.policy.SensitiveScreenPolicy
import com.paragrudani.replyly.policy.SensitivityVerdict
import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.screen.ScreenNode
import com.paragrudani.replyly.screen.ScreenSnapshot
import com.paragrudani.replyly.service.AccessibilityHost
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PassiveCaptureComponentTest {
    private val whatsapp = TargetApps.WHATSAPP
    private val asha = ChatKey(whatsapp, "Asha")

    /** The screen the fake host returns, read from the active window at capture time. */
    private var screenPackage: String? = whatsapp
    private var captures = 0
    private val host =
        object : AccessibilityHost {
            override val service: AccessibilityService get() = error("not used")

            override fun captureActiveWindow(): ScreenSnapshot? {
                captures++
                val pkg = screenPackage ?: return null
                return ScreenSnapshot(
                    packageName = pkg,
                    capturedAt = clock.instant(),
                    screenWidth = 1080,
                    screenHeight = 2340,
                    root = ScreenNode(bounds = Bounds.EMPTY),
                )
            }
        }

    private var screenMessages = listOf(ChatMessage("Asha", "hi", "6:40 pm", isMe = false))
    private var parses = 0
    private val parser =
        object : ChatParser {
            override val packages = TargetApps.FULL_PARSER_PACKAGES

            override fun parse(snapshot: ScreenSnapshot): ParsedChat? {
                parses++
                return ParsedChat(snapshot.packageName, "Asha", false, screenMessages, historyApplies = true)
            }
        }
    private val registry =
        ChatParserRegistry(
            listOf(parser),
            fallback =
                object : ChatParser {
                    override val packages = emptySet<String>()

                    override fun parse(snapshot: ScreenSnapshot): ParsedChat? = error("generic apps are never parsed")
                },
        )

    private val policyCalls = mutableListOf<ScreenContext>()
    private var policy = SensitiveScreenPolicy { SensitivityVerdict.Allowed }

    private val merged = mutableListOf<ParsedChat>()
    private var mergeResult: (ParsedChat) -> MergeOutcome = { MergeOutcome(it.messages.size, 0, emptyList()) }
    private val merger =
        HistoryReadMerger { chat ->
            merged += chat
            mergeResult(chat)
        }

    private val clock = MutableClock()
    private val history = InMemoryHistoryRepository(clock)
    private val feedback = InMemoryFeedbackLog(clock)
    private val logs = mutableListOf<String>()

    private fun TestScope.component() =
        PassiveCaptureComponent(
            scope = backgroundScope,
            policy = { context ->
                policyCalls += context
                policy.evaluate(context)
            },
            parsers = registry,
            history = merger,
            feedbackLog = feedback,
            retention = HistoryRetention(history, InMemorySettingsRepository(), clock),
            log = { logs += it },
        ).also {
            it.onConnected(host)
            runCurrent()
        }

    private fun contentChanged(pkg: String = whatsapp): AccessibilityEvent =
        AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { packageName = pkg }

    @Test
    fun `captures once, about a second after the last screen change`() =
        runTest {
            val capture = component()

            capture.onEvent(contentChanged(), host)
            advanceTimeBy(500)
            capture.onEvent(contentChanged(), host)
            advanceTimeBy(900)
            capture.onEvent(contentChanged(), host)
            advanceTimeBy(999)
            runCurrent()
            assertThat(merged).isEmpty()

            advanceTimeBy(2)
            runCurrent()
            assertThat(merged).hasSize(1)
            assertThat(merged.single().chatKey).isEqualTo(asha)
        }

    @Test
    fun `apps without a full parser and unrelated events never capture`() =
        runTest {
            val capture = component()

            capture.onEvent(contentChanged("com.slack"), host)
            capture.onEvent(
                AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED).apply { packageName = whatsapp },
                host,
            )
            advanceTimeBy(5_000)
            runCurrent()

            assertThat(captures).isEqualTo(0)
        }

    @Test
    fun `window state changes count as screen changes`() =
        runTest {
            val capture = component()

            capture.onEvent(
                AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = whatsapp },
                host,
            )
            advanceTimeBy(1_001)
            runCurrent()

            assertThat(merged).hasSize(1)
        }

    @Test
    fun `a blocked screen is not parsed or stored`() =
        runTest {
            policy = SensitiveScreenPolicy { SensitivityVerdict.Blocked(BlockReason.PASSWORD_FIELD) }
            val capture = component()

            capture.onEvent(contentChanged(), host)
            advanceTimeBy(1_001)
            runCurrent()

            assertThat(parses).isEqualTo(0)
            assertThat(merged).isEmpty()
        }

    @Test
    fun `a blocked app is not even read`() =
        runTest {
            policy =
                SensitiveScreenPolicy {
                    if (it.snapshot ==
                        null
                    ) {
                        SensitivityVerdict.Blocked(BlockReason.BLOCKLISTED_APP)
                    } else {
                        SensitivityVerdict.Allowed
                    }
                }
            val capture = component()

            capture.onEvent(contentChanged(), host)
            advanceTimeBy(1_001)
            runCurrent()

            assertThat(captures).isEqualTo(0)
        }

    @Test
    fun `a blocklisted chat is parsed but not stored`() =
        runTest {
            policy =
                SensitiveScreenPolicy {
                    if (it.chatKey ==
                        asha
                    ) {
                        SensitivityVerdict.Blocked(BlockReason.BLOCKLISTED_CHAT)
                    } else {
                        SensitivityVerdict.Allowed
                    }
                }
            val capture = component()

            capture.onEvent(contentChanged(), host)
            advanceTimeBy(1_001)
            runCurrent()

            assertThat(policyCalls.map { it.chatKey }).contains(asha)
            assertThat(merged).isEmpty()
        }

    @Test
    fun `a screen that moved to another app by capture time is skipped`() =
        runTest {
            val capture = component()
            screenPackage = "com.android.launcher"

            capture.onEvent(contentChanged(), host)
            advanceTimeBy(1_001)
            runCurrent()

            assertThat(captures).isEqualTo(1)
            assertThat(parses).isEqualTo(0)
        }

    @Test
    fun `the message I sent after an insert becomes the feedback entry's final message`() =
        runTest {
            val entry = feedback.record(asha, "model", "prompt", listOf(Suggestion("agree", "see you at 6")))
            feedback.markInserted(entry, 0)
            mergeResult = {
                MergeOutcome(
                    inserted = 3,
                    edited = 0,
                    newAtEnd =
                        listOf(
                            ChatMessage(null, "see you at 6!", "6:42 pm", isMe = true),
                            ChatMessage(null, "bringing cake", "6:42 pm", isMe = true),
                            ChatMessage("Asha", "yay", "6:43 pm", isMe = false),
                        ),
                )
            }
            val capture = component()

            capture.onEvent(contentChanged(), host)
            advanceTimeBy(1_001)
            runCurrent()

            assertThat(
                feedback
                    .observeRecent(1)
                    .first()
                    .single()
                    .finalSentText,
            ).isEqualTo("bringing cake")
        }

    @Test
    fun `no feedback update when nothing of mine is new`() =
        runTest {
            val entry = feedback.record(asha, "model", "prompt", listOf(Suggestion("agree", "ok")))
            feedback.markInserted(entry, 0)
            mergeResult = { MergeOutcome(1, 0, listOf(ChatMessage("Asha", "yay", "6:43 pm", isMe = false))) }
            val capture = component()

            capture.onEvent(contentChanged(), host)
            advanceTimeBy(1_001)
            runCurrent()

            assertThat(
                feedback
                    .observeRecent(1)
                    .first()
                    .single()
                    .finalSentText,
            ).isNull()
        }

    @Test
    fun `a failing capture is logged by class name and the next one still runs`() =
        runTest {
            mergeResult = { error("secret chat text") }
            val capture = component()
            capture.onEvent(contentChanged(), host)
            advanceTimeBy(1_001)
            runCurrent()

            mergeResult = { MergeOutcome(1, 0, emptyList()) }
            capture.onEvent(contentChanged(), host)
            advanceTimeBy(1_001)
            runCurrent()

            assertThat(merged).hasSize(2)
            assertThat(logs.joinToString()).contains("IllegalStateException")
            assertThat(logs.joinToString()).doesNotContain("secret")
        }

    @Test
    fun `connecting purges old history`() =
        runTest {
            history.merge(ParsedChat(whatsapp, "Asha", false, screenMessages, historyApplies = true))
            clock.advance(Duration.ofDays(61))

            component()

            assertThat(history.lastMessages(asha, 10)).isEmpty()
        }

    @Test
    fun `nothing is captured after the service disconnects`() =
        runTest {
            val capture = component()

            capture.onEvent(contentChanged(), host)
            capture.onDisconnected()
            advanceTimeBy(5_000)
            runCurrent()

            assertThat(captures).isEqualTo(0)
        }
}
