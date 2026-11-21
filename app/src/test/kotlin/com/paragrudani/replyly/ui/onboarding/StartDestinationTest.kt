package com.paragrudani.replyly.ui.onboarding

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class StartDestinationTest {
    @Test
    fun `all steps done opens settings`() =
        runTest {
            assertThat(isOnboardingDone(ioContext = EmptyCoroutineContext) { true }).isTrue()
        }

    @Test
    fun `a step not done opens onboarding`() =
        runTest {
            assertThat(isOnboardingDone(ioContext = EmptyCoroutineContext) { false }).isFalse()
        }

    @Test
    fun `a check slower than the timeout opens onboarding`() =
        runTest {
            val done =
                isOnboardingDone(ioContext = EmptyCoroutineContext) {
                    delay(START_CHECK_TIMEOUT_MS + 1)
                    true
                }

            assertThat(done).isFalse()
        }

    @Test
    fun `a check just inside the timeout still counts`() =
        runTest {
            val done =
                isOnboardingDone(ioContext = EmptyCoroutineContext) {
                    delay(START_CHECK_TIMEOUT_MS - 1)
                    true
                }

            assertThat(done).isTrue()
        }

    @Test
    fun `a check that throws opens onboarding`() =
        runTest {
            assertThat(isOnboardingDone(ioContext = EmptyCoroutineContext) { error("store not ready") }).isFalse()
        }
}
