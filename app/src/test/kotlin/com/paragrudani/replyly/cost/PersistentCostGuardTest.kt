package com.paragrudani.replyly.cost

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.llm.TokenUsage
import com.paragrudani.replyly.testing.MutableClock
import com.paragrudani.replyly.testing.TestDataStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

class PersistentCostGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    private val stores by lazy { TestDataStores(tmp.root) }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val clock = MutableClock.atLocal(LocalDateTime.of(2026, 11, 21, 23, 58))
    private val limit = MutableStateFlow(3)
    private val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private fun guard() = PersistentCostGuard(stores.open("cost"), limit, clock, scope, ticks)

    @After fun tearDown() {
        scope.cancel()
        stores.closeAll()
    }

    private suspend fun CostGuard.awaitStatus(predicate: (CostStatus) -> Boolean): CostStatus =
        withTimeout(5_000) { status.first(predicate) }

    @Test
    fun `allows requests up to the daily limit, then refuses`() =
        runBlocking<Unit> {
            val guard = guard()
            repeat(3) { assertThat(guard.tryAcquire()).isTrue() }
            assertThat(guard.tryAcquire()).isFalse()
            assertThat(guard.awaitStatus { it.requestsToday == 3 }.limitReached).isTrue()
        }

    @Test
    fun `the daily limit resets at local midnight`() =
        runBlocking<Unit> {
            val guard = guard()
            repeat(3) { guard.tryAcquire() }
            clock.setLocal(LocalDateTime.of(2026, 11, 21, 23, 59, 59))
            assertThat(guard.tryAcquire()).isFalse()

            clock.setLocal(LocalDateTime.of(2026, 11, 22, 0, 0, 1))
            assertThat(guard.tryAcquire()).isTrue()
            assertThat(guard.awaitStatus { it.requestsToday == 1 }.dailyRequestLimit).isEqualTo(3)
        }

    @Test
    fun `status shows the new day after a midnight tick without any request`() =
        runBlocking<Unit> {
            val guard = guard()
            guard.tryAcquire()
            guard.awaitStatus { it.requestsToday == 1 }

            clock.setLocal(LocalDateTime.of(2026, 11, 22, 0, 0))
            ticks.emit(Unit)
            guard.awaitStatus { it.requestsToday == 0 }
        }

    @Test
    fun `tokens add up within the month and reset on the 1st`() =
        runBlocking<Unit> {
            clock.setLocal(LocalDateTime.of(2026, 11, 30, 22, 0))
            val guard = guard()
            guard.recordUsage(TokenUsage(promptTokens = 1200, completionTokens = 150))
            guard.recordUsage(TokenUsage(promptTokens = 800, completionTokens = 50))
            val november = guard.awaitStatus { it.totalTokensThisMonth == 2200L }
            assertThat(november.promptTokensThisMonth).isEqualTo(2000)
            assertThat(november.completionTokensThisMonth).isEqualTo(200)

            clock.setLocal(LocalDateTime.of(2026, 12, 1, 0, 0, 1))
            guard.recordUsage(TokenUsage(promptTokens = 10, completionTokens = 5))
            guard.awaitStatus { it.totalTokensThisMonth == 15L }
        }

    @Test
    fun `a new day keeps the month tokens`() =
        runBlocking<Unit> {
            val guard = guard()
            guard.recordUsage(TokenUsage(100, 10))
            clock.setLocal(LocalDateTime.of(2026, 11, 22, 9, 0))
            ticks.emit(Unit)
            guard.recordUsage(TokenUsage(1, 1))
            guard.awaitStatus { it.totalTokensThisMonth == 112L }
        }

    @Test
    fun `the limit follows settings`() =
        runBlocking<Unit> {
            val guard = guard()
            repeat(3) { guard.tryAcquire() }
            limit.value = 5
            guard.awaitStatus { it.dailyRequestLimit == 5 && !it.limitReached }
            assertThat(guard.tryAcquire()).isTrue()
        }

    @Test
    fun `counts survive a restart`() =
        runBlocking<Unit> {
            val first = guard()
            first.tryAcquire()
            first.recordUsage(TokenUsage(40, 2))
            first.awaitStatus { it.requestsToday == 1 && it.totalTokensThisMonth == 42L }

            val second = PersistentCostGuard(stores.reopen("cost"), limit, clock, scope, ticks)
            second.awaitStatus { it.requestsToday == 1 && it.totalTokensThisMonth == 42L }
        }

    @Test
    fun `concurrent requests never exceed the limit`() =
        runBlocking<Unit> {
            limit.value = 10
            val guard = guard()
            val results =
                (1..40)
                    .map { async(Dispatchers.Default) { guard.tryAcquire() } }
                    .map { it.await() }
            assertThat(results.count { it }).isEqualTo(10)
        }
}
