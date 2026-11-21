package com.paragrudani.replyly.policy

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

enum class SecureWindowState { SECURE, NOT_SECURE, UNKNOWN }

sealed interface ProbeResult {
    /** The system captured the window, so it is not FLAG_SECURE. The pixels are already discarded. */
    data object Captured : ProbeResult

    /** An `AccessibilityService.ERROR_TAKE_SCREENSHOT_*` code. */
    data class Failed(
        val errorCode: Int,
    ) : ProbeResult
}

/** Asks the system to screenshot one window. Calls [onResult] at most once. */
fun interface WindowScreenshotProbe {
    fun probe(
        windowId: Int,
        onResult: (ProbeResult) -> Unit,
    )
}

/**
 * Accessibility cannot read FLAG_SECURE, but since API 34 `takeScreenshotOfWindow` fails with
 * `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` on a secure window. This uses that as a probe.
 *
 * Cost: one window capture per check. Results are cached per window for [cacheMillis] (1 s by
 * default), which absorbs the screenshot API's own rate limit and a tap racing passive capture.
 * Not longer: one window can turn secure without changing id (a Telegram secret chat, a Chrome
 * incognito tab). Unknown results (other errors, no answer) are never cached.
 */
class SecureWindowDetector(
    private val probe: WindowScreenshotProbe,
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val cacheMillis: Long = 1_000,
    private val timeoutMillis: Long = 1_500,
    private val rateLimitRetryMillis: Long = 400,
) {
    private data class Entry(
        val state: SecureWindowState,
        val at: Long,
    )

    private val cache = mutableMapOf<Int, Entry>()

    suspend fun check(
        windowId: Int,
        maxAgeMillis: Long = cacheMillis,
    ): SecureWindowState {
        synchronized(cache) { cache[windowId] }
            ?.takeIf { now() - it.at < maxAgeMillis }
            ?.let { return it.state }

        var result = ask(windowId)
        if (result == ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)) {
            delay(rateLimitRetryMillis)
            result = ask(windowId)
        }
        val state =
            when (result) {
                ProbeResult.Captured -> SecureWindowState.NOT_SECURE
                ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) -> SecureWindowState.SECURE
                else -> SecureWindowState.UNKNOWN
            }
        if (state != SecureWindowState.UNKNOWN) {
            synchronized(cache) {
                if (cache.size > MAX_ENTRIES) cache.clear()
                cache[windowId] = Entry(state, now())
            }
        }
        return state
    }

    private suspend fun ask(windowId: Int): ProbeResult? =
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                probe.probe(windowId) { result -> if (continuation.isActive) continuation.resume(result) }
            }
        }

    private companion object {
        const val MAX_ENTRIES = 32
    }
}

/**
 * The real probe. Needs `canTakeScreenshot` in the accessibility config. The hardware buffer is
 * closed at once: Replyly never keeps or decodes the pixels.
 */
class AccessibilityScreenshotProbe(
    private val service: AccessibilityService,
) : WindowScreenshotProbe {
    override fun probe(
        windowId: Int,
        onResult: (ProbeResult) -> Unit,
    ) {
        val callback =
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    screenshot.hardwareBuffer.close()
                    onResult(ProbeResult.Captured)
                }

                override fun onFailure(errorCode: Int) {
                    onResult(ProbeResult.Failed(errorCode))
                }
            }
        try {
            service.takeScreenshotOfWindow(windowId, service.mainExecutor, callback)
        } catch (e: SecurityException) {
            // canTakeScreenshot missing (an old service config still bound): unknown.
            onResult(ProbeResult.Failed(NO_PERMISSION))
        }
    }

    companion object {
        const val NO_PERMISSION = -1
    }
}
