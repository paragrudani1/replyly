package com.paragrudani.replyly.policy

import android.accessibilityservice.AccessibilityService
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SecureWindowDetectorTest {
    /** Answers each probe with the next queued result; `null` means the system never answers. */
    private class FakeProbe : WindowScreenshotProbe {
        val answers = ArrayDeque<ProbeResult?>()
        val probedWindows = mutableListOf<Int>()

        override fun probe(
            windowId: Int,
            onResult: (ProbeResult) -> Unit,
        ) {
            probedWindows += windowId
            answers.removeFirst()?.let(onResult)
        }
    }

    private val probe = FakeProbe()

    private fun TestScope.detector() = SecureWindowDetector(probe, now = { testScheduler.currentTime })

    @Test
    fun `a window that can be captured is not secure`() =
        runTest {
            probe.answers += ProbeResult.Captured

            assertThat(detector().check(7)).isEqualTo(SecureWindowState.NOT_SECURE)
        }

    @Test
    fun `the secure-window error means FLAG_SECURE`() =
        runTest {
            probe.answers += ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)

            assertThat(detector().check(7)).isEqualTo(SecureWindowState.SECURE)
        }

    @Test
    fun `any other error is unknown`() =
        runTest {
            probe.answers += ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)

            assertThat(detector().check(7)).isEqualTo(SecureWindowState.UNKNOWN)
        }

    @Test
    fun `a missing screenshot capability is unknown`() =
        runTest {
            probe.answers += ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS)

            assertThat(detector().check(7)).isEqualTo(SecureWindowState.UNKNOWN)
        }

    @Test
    fun `window or display gone is gone`() =
        runTest {
            probe.answers += ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW)
            probe.answers += ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY)
            val detector = detector()

            assertThat(detector.check(7)).isEqualTo(SecureWindowState.GONE)
            assertThat(detector.check(7)).isEqualTo(SecureWindowState.GONE)
        }

    @Test
    fun `no answer within the timeout is unknown`() =
        runTest {
            probe.answers += null

            assertThat(detector().check(7)).isEqualTo(SecureWindowState.UNKNOWN)
        }

    @Test
    fun `a second check of the same window within the cache time does not probe again`() =
        runTest {
            val detector = detector()
            probe.answers += ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
            detector.check(7)

            testScheduler.advanceTimeBy(500)

            assertThat(detector.check(7)).isEqualTo(SecureWindowState.SECURE)
            assertThat(probe.probedWindows).containsExactly(7)
        }

    @Test
    fun `after the cache time the window is probed again, it may have turned secure`() =
        runTest {
            val detector = detector()
            probe.answers += ProbeResult.Captured
            probe.answers += ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
            detector.check(7)

            testScheduler.advanceTimeBy(1_500)

            assertThat(detector.check(7)).isEqualTo(SecureWindowState.SECURE)
        }

    @Test
    fun `maxAge zero always probes`() =
        runTest {
            val detector = detector()
            probe.answers += ProbeResult.Captured
            probe.answers += ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
            detector.check(7)

            assertThat(detector.check(7, maxAgeMillis = 0)).isEqualTo(SecureWindowState.SECURE)
        }

    @Test
    fun `another window is probed separately`() =
        runTest {
            val detector = detector()
            probe.answers += ProbeResult.Captured
            probe.answers += ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
            detector.check(7)

            assertThat(detector.check(8)).isEqualTo(SecureWindowState.SECURE)
        }

    @Test
    fun `rate limited - waits and tries once more`() =
        runTest {
            probe.answers += ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
            probe.answers += ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)

            assertThat(detector().check(7)).isEqualTo(SecureWindowState.SECURE)
            assertThat(probe.probedWindows).containsExactly(7, 7)
        }

    @Test
    fun `unknown results are not cached`() =
        runTest {
            val detector = detector()
            probe.answers += ProbeResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
            probe.answers += ProbeResult.Captured
            detector.check(7)

            assertThat(detector.check(7)).isEqualTo(SecureWindowState.NOT_SECURE)
        }
}
