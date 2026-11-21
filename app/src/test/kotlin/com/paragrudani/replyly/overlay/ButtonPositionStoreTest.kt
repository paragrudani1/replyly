package com.paragrudani.replyly.overlay

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

class ButtonPositionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val stores by lazy { TestDataStores(tmp.root) }
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        runBlocking { scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }
        stores.closeAll()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private suspend fun restart(): ButtonPositionStore {
        scopes
            .removeLastOrNull()
            ?.coroutineContext
            ?.get(Job)
            ?.cancelAndJoin()
        return ButtonPositionStore(stores.reopen(NAME), newScope()).also { it.sync() }
    }

    private fun fresh() = ButtonPositionStore(stores.open(NAME), newScope())

    @Test
    fun `a fresh install remembers nothing`() =
        runBlocking {
            val store = fresh()
            store.sync()
            assertThat(store.liftDp(WHATSAPP)).isNull()
        }

    @Test
    fun `a saved spot reads back at once, before it is on disk`() {
        val store = fresh()
        store.save(WHATSAPP, 120f)
        assertThat(store.liftDp(WHATSAPP)).isEqualTo(120f)
    }

    @Test
    fun `spots are per app and survive a restart`() =
        runBlocking {
            val store = fresh()
            store.save(WHATSAPP, 120f)
            store.save(TELEGRAM, 40.5f)
            store.save(WHATSAPP, 80f)
            store.sync()

            val after = restart()

            assertThat(after.liftDp(WHATSAPP)).isEqualTo(80f)
            assertThat(after.liftDp(TELEGRAM)).isEqualTo(40.5f)
            assertThat(after.liftDp("com.other")).isNull()
        }

    @Test
    fun `reset forgets one app, also after a restart`() =
        runBlocking {
            val store = fresh()
            store.save(WHATSAPP, 120f)
            store.save(TELEGRAM, 40f)
            store.reset(WHATSAPP)
            assertThat(store.liftDp(WHATSAPP)).isNull()
            store.sync()

            val after = restart()

            assertThat(after.liftDp(WHATSAPP)).isNull()
            assertThat(after.liftDp(TELEGRAM)).isEqualTo(40f)
        }

    @Test
    fun `resetAll forgets every app, also after a restart`() =
        runBlocking {
            val store = fresh()
            store.save(WHATSAPP, 120f)
            store.save(TELEGRAM, 40f)
            store.sync()
            store.resetAll()
            assertThat(store.liftDp(TELEGRAM)).isNull()
            store.sync()

            val after = restart()

            assertThat(after.liftDp(WHATSAPP)).isNull()
            assertThat(after.liftDp(TELEGRAM)).isNull()
        }

    @Test
    fun `a change made while loading wins over the old value on disk`() =
        runBlocking {
            val first = fresh()
            first.save(WHATSAPP, 120f)
            first.save(TELEGRAM, 40f)
            first.sync()
            scopes
                .removeLastOrNull()
                ?.coroutineContext
                ?.get(Job)
                ?.cancelAndJoin()

            // Changes right after start, maybe before the load finished.
            val second = ButtonPositionStore(stores.reopen(NAME), newScope())
            second.save(WHATSAPP, 10f)
            second.reset(TELEGRAM)
            second.sync()

            assertThat(second.liftDp(WHATSAPP)).isEqualTo(10f)
            assertThat(second.liftDp(TELEGRAM)).isNull()
        }

    private companion object {
        const val NAME = "button_positions"
        const val WHATSAPP = "com.whatsapp"
        const val TELEGRAM = "org.telegram.messenger"
    }
}
