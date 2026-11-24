package com.paragrudani.replyly.snooze

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.testing.TestDataStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId

/**
 * The snooze's runtime in the accessibility service (#56), in virtual time: expiry and every
 * store change re-decide the button with no accessibility event; the shake listener is on only
 * while snoozed and the screen is on.
 */
class SnoozeKeeperTest {
    @get:Rule val tmp = TemporaryFolder()

    private val stores by lazy { TestDataStores(tmp.root) }
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { SnoozeStore(stores.open("snooze"), storeScope) }

    private var refreshes = 0
    private val shakeStates = mutableListOf<Boolean>()
    private val logs = mutableListOf<String>()
    private var shakeEnds = 0

    @After
    fun tearDown() {
        runBlocking { storeScope.coroutineContext[Job]?.cancelAndJoin() }
        stores.closeAll()
    }

    private suspend fun TestScope.keeper(screenOn: Boolean = true): SnoozeKeeper {
        withContext(Dispatchers.Default) { store.sync() }
        val keeper =
            SnoozeKeeper(
                store = store,
                now = { testScheduler.currentTime },
                zone = ZoneId.of("Asia/Kolkata"),
                scope = backgroundScope,
                shake = { on -> shakeStates += on },
                refresh = { refreshes++ },
                onShakeEnded = { shakeEnds++ },
                log = { logs += it },
            )
        keeper.start(screenOn)
        runCurrent()
        return keeper
    }

    private val shakeOn get() = shakeStates.lastOrNull() ?: false

    @Test
    fun `expiry refreshes the button with no event and ends the snooze`() =
        runTest {
            val keeper = keeper()
            keeper.snooze(SnoozeChoice.TEN_MINUTES)
            runCurrent()
            val before = refreshes

            advanceTimeBy(10 * 60_000L - 1)
            runCurrent()
            assertThat(refreshes).isEqualTo(before)
            assertThat(store.until.value).isNotNull()

            advanceTimeBy(1)
            runCurrent()
            assertThat(refreshes).isGreaterThan(before)
            assertThat(store.until.value).isNull()
            assertThat(logs).contains("snooze end reason=TIMER")
        }

    @Test
    fun `a new snooze and an end each refresh at once`() =
        runTest {
            val keeper = keeper()
            val start = refreshes
            keeper.snooze(SnoozeChoice.ONE_HOUR)
            runCurrent()
            assertThat(refreshes).isEqualTo(start + 1)

            store.end()
            runCurrent()
            assertThat(refreshes).isEqualTo(start + 2)
        }

    @Test
    fun `a change re-schedules the timer`() =
        runTest {
            val keeper = keeper()
            keeper.snooze(SnoozeChoice.TEN_MINUTES)
            runCurrent()
            keeper.snooze(SnoozeChoice.ONE_HOUR)
            runCurrent()

            advanceTimeBy(30 * 60_000L)
            runCurrent()
            assertThat(store.until.value).isNotNull()
            advanceTimeBy(30 * 60_000L)
            runCurrent()
            assertThat(store.until.value).isNull()
        }

    @Test
    fun `until turned back on has no timer`() =
        runTest {
            val keeper = keeper()
            keeper.snooze(SnoozeChoice.UNTIL_TURNED_ON)
            runCurrent()
            advanceTimeBy(365L * 24 * 3_600_000)
            runCurrent()
            assertThat(store.until.value).isEqualTo(Long.MAX_VALUE)
        }

    @Test
    fun `a snooze that expired while the service was off ends at start`() =
        runTest {
            store.snooze(untilMillis = 0)
            keeper()
            assertThat(store.until.value).isNull()
            assertThat(logs).contains("snooze end reason=TIMER")
        }

    @Test
    fun `the screen turning on ends a snooze whose timer is late`() =
        runTest {
            // The main-thread timer runs on uptime, which stops in deep sleep: the wall clock can pass the end first.
            var wallClock = 0L
            withContext(Dispatchers.Default) { store.sync() }
            val keeper =
                SnoozeKeeper(store, { wallClock }, ZoneId.of("UTC"), backgroundScope, { shakeStates += it }, {
                    refreshes++
                }, {}, { logs += it })
            keeper.start(screenOn = true)
            keeper.snooze(SnoozeChoice.TEN_MINUTES)
            runCurrent()
            keeper.setScreenOn(false)
            wallClock = 11 * 60_000L
            keeper.setScreenOn(true)
            runCurrent()
            assertThat(store.until.value).isNull()
        }

    @Test
    fun `choosing stores the until time, logs the choice and returns the toast`() =
        runTest {
            val keeper = keeper()
            val toast = keeper.snooze(SnoozeChoice.ONE_HOUR)
            assertThat(store.until.value).isEqualTo(Instant.ofEpochMilli(0).plusSeconds(3_600).toEpochMilli())
            assertThat(toast).isEqualTo("Snoozed for 1 hour. Shake your phone to bring it back.")
            assertThat(logs).contains("snooze start choice=ONE_HOUR")
        }

    @Test
    fun `shake listening runs only while snoozed and the screen is on`() =
        runTest {
            val keeper = keeper(screenOn = true)
            assertThat(shakeOn).isFalse()

            keeper.snooze(SnoozeChoice.ONE_HOUR)
            runCurrent()
            assertThat(shakeOn).isTrue()

            keeper.setScreenOn(false)
            assertThat(shakeOn).isFalse()
            keeper.setScreenOn(true)
            runCurrent()
            assertThat(shakeOn).isTrue()

            store.end()
            runCurrent()
            assertThat(shakeOn).isFalse()

            keeper.snooze(SnoozeChoice.ONE_HOUR)
            runCurrent()
            keeper.stop()
            assertThat(shakeOn).isFalse()
        }

    @Test
    fun `a screen that starts off keeps the listener off until it turns on`() =
        runTest {
            store.snooze(Long.MAX_VALUE)
            val keeper = keeper(screenOn = false)
            assertThat(shakeOn).isFalse()
            keeper.setScreenOn(true)
            runCurrent()
            assertThat(shakeOn).isTrue()
        }

    @Test
    fun `a shake ends the snooze once, with the shake reason`() =
        runTest {
            val keeper = keeper()
            keeper.snooze(SnoozeChoice.UNTIL_TURNED_ON)
            runCurrent()

            keeper.onShake()
            runCurrent()
            keeper.onShake()

            assertThat(store.until.value).isNull()
            assertThat(shakeEnds).isEqualTo(1)
            assertThat(logs.filter { it.startsWith("snooze end") }).containsExactly("snooze end reason=SHAKE")
        }

    @Test
    fun `logs carry event names and enum values only`() =
        runTest {
            val keeper = keeper()
            keeper.snooze(SnoozeChoice.UNTIL_MORNING)
            runCurrent()
            keeper.onShake()
            runCurrent()
            logs.forEach { assertThat(it).doesNotContainMatch("[0-9]") }
        }
}
