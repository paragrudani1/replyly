package com.paragrudani.replyly.snooze

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.testing.TestDataStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Plain JVM, like `ButtonPositionStoreTest`: a real Preferences DataStore on a temp file. */
class SnoozeStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val stores by lazy { TestDataStores(tmp.root) }
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        runBlocking { scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }
        stores.closeAll()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private fun fresh() = SnoozeStore(stores.open(NAME), newScope())

    /** A process restart: the old store's scope ends, a new store loads from disk. */
    private suspend fun restart(): SnoozeStore {
        scopes
            .removeLastOrNull()
            ?.coroutineContext
            ?.get(Job)
            ?.cancelAndJoin()
        return SnoozeStore(stores.reopen(NAME), newScope()).also { it.sync() }
    }

    @Test
    fun `a fresh install is not snoozed`() =
        runBlocking {
            val store = fresh()
            store.sync()
            assertThat(store.until.value).isNull()
            assertThat(store.isSnoozed(nowMillis = 0)).isFalse()
        }

    @Test
    fun `a snooze reads back at once, before it is on disk`() {
        val store = fresh()
        store.snooze(untilMillis = 5_000)
        assertThat(store.until.value).isEqualTo(5_000)
        assertThat(store.isSnoozed(nowMillis = 4_999)).isTrue()
        assertThat(store.isSnoozed(nowMillis = 5_000)).isFalse()
    }

    @Test
    fun `a snooze survives a restart`() =
        runBlocking {
            val store = fresh()
            store.snooze(untilMillis = 1_800_000_000_000)
            store.sync()

            assertThat(restart().until.value).isEqualTo(1_800_000_000_000)
        }

    @Test
    fun `until turned back on is stored as Long MAX_VALUE`() =
        runBlocking {
            val store = fresh()
            store.snooze(Long.MAX_VALUE)
            store.sync()

            val after = restart()
            assertThat(after.until.value).isEqualTo(Long.MAX_VALUE)
            assertThat(after.isSnoozed(nowMillis = 4_102_444_800_000)).isTrue()
        }

    @Test
    fun `end removes the value, also from disk`() =
        runBlocking {
            val store = fresh()
            store.snooze(9_000)
            store.end()
            assertThat(store.until.value).isNull()
            store.sync()

            assertThat(restart().until.value).isNull()
        }

    @Test
    fun `a change made before the load finishes wins over the disk`() =
        runBlocking {
            fresh().apply {
                snooze(9_000)
                sync()
            }
            scopes.removeLast().coroutineContext[Job]?.cancelAndJoin()
            val store = SnoozeStore(stores.reopen(NAME), newScope())
            store.end()
            store.sync()
            assertThat(store.until.value).isNull()
        }

    @Test
    fun `the flow emits each change`() =
        runBlocking {
            val store = fresh()
            store.sync()
            store.until.test {
                assertThat(awaitItem()).isNull()
                store.snooze(7_000)
                assertThat(awaitItem()).isEqualTo(7_000)
                store.end()
                assertThat(awaitItem()).isNull()
            }
        }

    private companion object {
        const val NAME = "snooze"
    }
}
