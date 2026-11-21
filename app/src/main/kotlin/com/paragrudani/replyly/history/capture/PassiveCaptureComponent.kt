package com.paragrudani.replyly.history.capture

import android.annotation.SuppressLint
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.paragrudani.replyly.di.AppGraph
import com.paragrudani.replyly.feedback.FeedbackLog
import com.paragrudani.replyly.history.HistoryReadMerger
import com.paragrudani.replyly.history.HistoryRetention
import com.paragrudani.replyly.history.MergeOutcome
import com.paragrudani.replyly.parser.ChatParserRegistry
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.policy.ScreenContext
import com.paragrudani.replyly.policy.SensitiveScreenPolicy
import com.paragrudani.replyly.policy.SensitivityVerdict
import com.paragrudani.replyly.service.AccessibilityComponent
import com.paragrudani.replyly.service.AccessibilityHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Passive capture (spec 4, ticket #3). In an app with a full parser, every screen change restarts
 * a [quietPeriod] wait, but a screen that never goes still (typing indicator, playing GIF) is
 * still captured [maxWait] after its first change. Then it:
 *
 * 1. checks that the app in front ([activePackage], the active window's root) is still the app of
 *    the screen changes, and asks [policy] about that app; only then reads the active window,
 *    with the FLAG_SECURE check first (`captureActiveWindowChecked`, fails closed);
 * 2. skips it unless it still belongs to that app, and asks [policy] about the screen (a secure
 *    window arrives with an empty tree and `secureWindow` set, and the policy blocks it);
 * 3. parses it, and asks [policy] about the chat (blocklisted chats are known only after parsing);
 * 4. merges it into history;
 * 5. if the merge found a message I sent below the saved tail, gives the newest one to
 *    [FeedbackLog.attachFinalMessage] (the final sent text of the last inserted suggestion);
 * 6. purges old history if a day has passed ([HistoryRetention]); it also purges on connect,
 *    together with the feedback log ([FeedbackLog.purgeExpired]).
 *
 * It never calls the LLM. Generic apps get no history. The wait runs on [scope]'s dispatcher,
 * so tests drive it with virtual time. Failures are logged by class name only.
 */
class PassiveCaptureComponent(
    private val scope: CoroutineScope,
    private val policy: SensitiveScreenPolicy,
    private val parsers: ChatParserRegistry,
    private val history: HistoryReadMerger,
    private val feedbackLog: FeedbackLog,
    private val retention: HistoryRetention,
    private val quietPeriod: Duration = 1.seconds,
    private val maxWait: Duration = 3.seconds,
    /** The package of the active window, without mapping or prefetching its tree. */
    private val activePackage: (AccessibilityHost) -> String? = ::activeWindowPackage,
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) : AccessibilityComponent {
    /** The package of the last screen change, conflated: only the latest matters. */
    private val changes =
        MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var session: Job? = null

    override fun onConnected(host: AccessibilityHost) {
        session?.cancel()
        session =
            scope.launch {
                launch { guarded("purge") { retention.purgeNow() } }
                launch { guarded("feedback purge") { feedbackLog.purgeExpired() } }
                changes.debounceAtMost(quietPeriod, maxWait).collect { packageName ->
                    guarded("capture") { capture(host, packageName) }
                }
            }
    }

    override fun onEvent(
        event: AccessibilityEvent,
        host: AccessibilityHost,
    ) {
        if (event.eventType and WATCHED_EVENTS == 0) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName in TargetApps.FULL_PARSER_PACKAGES) changes.tryEmit(packageName)
    }

    override fun onDisconnected() {
        session?.cancel()
        session = null
    }

    private suspend fun capture(
        host: AccessibilityHost,
        packageName: String,
    ) {
        // The app in front may have changed since the last event: check it before reading anything.
        if (activePackage(host) != packageName) return
        if (policy.evaluate(ScreenContext(packageName)).blocked) return
        // Keeps the probe's default 1 s cache: a window can turn secure without changing id (a
        // Telegram secret chat), and this path stores what it reads.
        val snapshot = host.captureActiveWindowChecked() ?: return
        if (snapshot.packageName != packageName) return
        if (policy.evaluate(ScreenContext(packageName, snapshot)).blocked) return
        val chat = parsers.parse(snapshot)?.takeIf { it.historyApplies } ?: return
        if (policy.evaluate(ScreenContext(packageName, snapshot, chat.chatKey)).blocked) return

        val outcome = history.mergeRead(chat)
        if (outcome == MergeOutcome.NONE) {
            log("capture unchanged")
        } else {
            log("capture merged inserted=${outcome.inserted} edited=${outcome.edited}")
        }
        outcome.newAtEnd.lastOrNull { it.isMe }?.let { sent ->
            if (feedbackLog.attachFinalMessage(chat.chatKey, sent.text)) log("feedback final message attached")
        }
        val purged = retention.purgeIfDue()
        if (purged > 0) log("history purged count=$purged")
    }

    private suspend fun guarded(
        what: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Class name only: exception messages can contain chat text.
            log("$what failed: ${e::class.simpleName}")
        }
    }

    private val SensitivityVerdict.blocked: Boolean get() = this is SensitivityVerdict.Blocked

    companion object {
        private const val TAG = "ReplylyCapture"
        private const val WATCHED_EVENTS =
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        /** The app's component, wired from [AppGraph]. */
        fun create(graph: AppGraph): PassiveCaptureComponent {
            val repository = graph.historyRepository
            val merger =
                repository as? HistoryReadMerger
                    ?: HistoryReadMerger { chat -> MergeOutcome(repository.merge(chat), 0, emptyList()) }
            return PassiveCaptureComponent(
                scope = graph.appScope,
                policy = graph.sensitiveScreenPolicy,
                parsers = graph.chatParserRegistry,
                history = merger,
                feedbackLog = graph.feedbackLog,
                retention = HistoryRetention(repository, graph.settingsRepository, graph.clock),
            )
        }
    }
}

/**
 * The active window's package from its root node alone. No prefetching: fetching the tree would
 * load node text into this process before the FLAG_SECURE check.
 *
 * WrongConstant: getRootInActiveWindow's docs allow 0 (no prefetching); lint's IntDef does not.
 */
@SuppressLint("WrongConstant")
private fun activeWindowPackage(host: AccessibilityHost): String? =
    host.service
        .getRootInActiveWindow(NO_PREFETCH)
        ?.packageName
        ?.toString()

private const val NO_PREFETCH = 0

/**
 * Like `debounce(quiet)`, but a burst of values that never pauses for [quiet] still emits its
 * latest value [maxWait] after the burst's first value. The next value starts a new burst.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T : Any> Flow<T>.debounceAtMost(
    quiet: Duration,
    maxWait: Duration,
): Flow<T> =
    channelFlow {
        val values = produce(capacity = Channel.CONFLATED) { collect { send(it) } }
        var upstreamOpen = true
        while (upstreamOpen) {
            var latest: T = values.receiveCatching().getOrNull() ?: break
            val deadline = launch { delay(maxWait) }
            while (true) {
                // null: the screen was still for [quiet], or the burst reached [maxWait].
                val next: ChannelResult<T>? =
                    select {
                        values.onReceiveCatching { it }
                        deadline.onJoin { null }
                        onTimeout(quiet) { null }
                    }
                if (next == null) break
                val value = next.getOrNull()
                if (value == null) {
                    upstreamOpen = false
                    break
                }
                latest = value
            }
            deadline.cancel()
            send(latest)
        }
    }
