package com.paragrudani.replyly.history.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.feedback.FeedbackLog
import com.paragrudani.replyly.feedback.InMemoryFeedbackLog
import com.paragrudani.replyly.history.FakeHistory
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

    /** The package of the active window's root, checked before the tree is read. */
    private var activePackage: () -> String? = { screenPackage }
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
    private var feedbackPurges = 0
    private val countingFeedback =
        object : FeedbackLog by feedback {
            override suspend fun purgeExpired(): Int {
                feedbackPurges++
                return 0
            }
        }
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
            feedbackLog = countingFeedback,
            retention = HistoryRetention(history, InMemorySettingsRepository(), clock),
            activePackage = { activePackage() },
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
    fun `a screen that never stops changing is still captured every 3 seconds`() =
        runTest {
            // A typing indicator or a playing GIF changes the screen every few hundred ms.
            val capture = component()
            val mergesAfterEachStep = mutableListOf<Int>()

            repeat(25) {
                capture.onEvent(contentChanged(), host)
                advanceTimeBy(400)
                mergesAfterEachStep += merged.size
            }

            // Bursts start at 0, 3200 and 6400 ms (the first change after a capture): captures at
            // 3000, 6200 and 9400 ms.
            assertThat(mergesAfterEachStep)
                .containsExactlyElementsIn(
                    List(7) { 0 } + List(8) { 1 } + List(8) { 2 } + List(2) { 3 },
                ).inOrder()
            // The last burst started at 9600 ms and then the screen went still.
            advanceTimeBy(599)
            runCurrent()
            assertThat(merged).hasSize(3)
            advanceTimeBy(2)
            runCurrent()
            assertThat(merged).hasSize(4)
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
    fun `another app in front by capture time is not read at all`() =
        runTest {
            val capture = component()
            screenPackage = "com.example.bank"

            capture.onEvent(contentChanged(), host)
            advanceTimeBy(1_001)
            runCurrent()

            assertThat(captures).isEqualTo(0)
            assertThat(policyCalls).isEmpty()
        }

    @Test
    fun `the package check runs on the app actually in front`() =
        runTest {
            policy =
                SensitiveScreenPolicy {
                    if (it.packageName ==
                        whatsapp
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

            assertThat(policyCalls.map { it.packageName }).containsExactly(whatsapp)
            assertThat(captures).isEqualTo(0)
        }

    @Test
    fun `a window that changes app between the check and the read is skipped`() =
        runTest {
            val capture = component()
            activePackage = { whatsapp }
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
    fun `B1 - after a gap, a scroll-up into older messages does not attach my old reply`() =
        runTest {
            // The real merge on an in-memory history.
            val stored = FakeHistory()
            mergeResult = { chat ->
                val plan = stored.merge(chat.messages)
                MergeOutcome(plan.inserts.size, plan.edits.size, plan.newAtEnd)
            }

            fun them(
                text: String,
                time: String,
            ) = ChatMessage("Asha", text, time, isMe = false)

            fun me(
                text: String,
                time: String,
            ) = ChatMessage(null, text, time, isMe = true)
            val capture = component()

            suspend fun captureScreen(vararg messages: ChatMessage) {
                screenMessages = messages.toList()
                capture.onEvent(contentChanged(), host)
                advanceTimeBy(1_001)
                runCurrent()
            }
            captureScreen(
                them("dinner at 8?", "6:00 pm"),
                me("sure", "6:01 pm"),
                them("great", "6:02 pm"),
                me("see you", "6:03 pm"),
            )
            // Next day, at the bottom, no overlap: appended after a gap.
            captureScreen(
                them("morning", "9:00 am"),
                me("hey", "9:01 am"),
                them("coffee?", "9:02 am"),
                me("yes", "9:03 am"),
            )
            val entry = feedback.record(asha, "model", "prompt", listOf(Suggestion("agree", "yes")))
            feedback.markInserted(entry, 0)

            // Fling up into yesterday.
            captureScreen(
                them("great", "6:02 pm"),
                me("see you", "6:03 pm"),
                them("running late", "7:55 pm"),
                me("no worries", "7:56 pm"),
            )

            assertThat(merged).hasSize(3)
            assertThat(
                feedback
                    .observeRecent(1)
                    .first()
                    .single()
                    .finalSentText,
            ).isNull()
            assertThat(stored.segments())
                .containsExactly(
                    listOf("dinner at 8?", "sure", "great", "see you", "running late", "no worries"),
                    listOf("morning", "hey", "coffee?", "yes"),
                ).inOrder()
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
    fun `connecting purges the feedback log too`() =
        runTest {
            component()

            assertThat(feedbackPurges).isEqualTo(1)
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
