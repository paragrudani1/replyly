package com.paragrudani.replyly.overlay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.testing.TestDataStores
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test
    fun `resetAll before the load finishes keeps the old spots from coming back`() =
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

            val disk = Gated(stores.reopen(NAME))
            val second = ButtonPositionStore(disk, newScope())
            second.resetAll()
            second.save(TELEGRAM, 7f) // After the reset, still before the load.
            disk.gate.complete(Unit)
            second.sync()

            assertThat(second.liftDp(WHATSAPP)).isNull()
            assertThat(second.liftDp(TELEGRAM)).isEqualTo(7f)
            val after = restart()
            assertThat(after.liftDp(WHATSAPP)).isNull()
            assertThat(after.liftDp(TELEGRAM)).isEqualTo(7f)
        }

    @Test
    fun `a load that fails with any error still finishes, with default spots`() =
        runBlocking {
            val broken =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> = flow { throw IllegalStateException("corrupt") }

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                        transform(emptyPreferences())
                }
            val store = ButtonPositionStore(broken, newScope())

            withTimeout(5_000) { store.sync() }

            assertThat(store.liftDp(WHATSAPP)).isNull()
            store.save(WHATSAPP, 30f)
            assertThat(store.liftDp(WHATSAPP)).isEqualTo(30f)
        }

    // Sides (#54) --------------------------------------------------------------------------------

    @Test
    fun `a side is saved with the lift, reads back at once and survives a restart`() =
        runBlocking {
            val store = fresh()
            store.save(WHATSAPP, 120f, ButtonSide.LEFT)
            store.save(TELEGRAM, 40f, ButtonSide.RIGHT)
            assertThat(store.side(WHATSAPP)).isEqualTo(ButtonSide.LEFT)
            store.sync()

            val after = restart()

            assertThat(after.liftDp(WHATSAPP)).isEqualTo(120f)
            assertThat(after.side(WHATSAPP)).isEqualTo(ButtonSide.LEFT)
            assertThat(after.liftDp(TELEGRAM)).isEqualTo(40f)
            assertThat(after.side(TELEGRAM)).isEqualTo(ButtonSide.RIGHT)
        }

    @Test
    fun `moving back to the right is remembered too`() =
        runBlocking {
            val store = fresh()
            store.save(WHATSAPP, 120f, ButtonSide.LEFT)
            store.save(WHATSAPP, 90f, ButtonSide.RIGHT)
            store.sync()

            val after = restart()

            assertThat(after.side(WHATSAPP)).isEqualTo(ButtonSide.RIGHT)
            assertThat(after.liftDp(WHATSAPP)).isEqualTo(90f)
        }

    @Test
    fun `an app never dragged reads as right`() =
        runBlocking {
            val store = fresh()
            store.sync()
            assertThat(store.side(WHATSAPP)).isEqualTo(ButtonSide.RIGHT)
        }

    @Test
    fun `a file from before sides reads as the same lift on the right`() =
        runBlocking {
            // What the previous version wrote: only lift_dp:<pkg>.
            stores.open(NAME).edit { it[floatPreferencesKey("lift_dp:$WHATSAPP")] = 75f }

            val store = ButtonPositionStore(stores.reopen(NAME), newScope())
            store.sync()

            assertThat(store.liftDp(WHATSAPP)).isEqualTo(75f)
            assertThat(store.side(WHATSAPP)).isEqualTo(ButtonSide.RIGHT)
        }

    @Test
    fun `reset and resetAll clear sides as well as lifts`() =
        runBlocking {
            val store = fresh()
            store.save(WHATSAPP, 120f, ButtonSide.LEFT)
            store.save(TELEGRAM, 40f, ButtonSide.LEFT)
            store.save(OTHER, 10f, ButtonSide.LEFT)
            store.reset(WHATSAPP)
            assertThat(store.side(WHATSAPP)).isEqualTo(ButtonSide.RIGHT)
            store.sync()

            var after = restart()
            assertThat(after.liftDp(WHATSAPP)).isNull()
            assertThat(after.side(WHATSAPP)).isEqualTo(ButtonSide.RIGHT)
            assertThat(after.side(TELEGRAM)).isEqualTo(ButtonSide.LEFT)

            after.resetAll()
            assertThat(after.side(TELEGRAM)).isEqualTo(ButtonSide.RIGHT)
            after.sync()
            after = restart()

            assertThat(after.side(TELEGRAM)).isEqualTo(ButtonSide.RIGHT)
            assertThat(after.side(OTHER)).isEqualTo(ButtonSide.RIGHT)
            assertThat(after.liftDp(OTHER)).isNull()
            // Saving a lift alone after a reset is on the right, not on a side left over on disk.
            after.save(TELEGRAM, 5f)
            assertThat(after.side(TELEGRAM)).isEqualTo(ButtonSide.RIGHT)
        }

    @Test
    fun `a side changed before the load finishes wins over the old side on disk`() =
        runBlocking {
            val first = fresh()
            first.save(WHATSAPP, 120f, ButtonSide.LEFT)
            first.save(TELEGRAM, 40f, ButtonSide.LEFT)
            first.sync()
            scopes
                .removeLastOrNull()
                ?.coroutineContext
                ?.get(Job)
                ?.cancelAndJoin()

            val disk = Gated(stores.reopen(NAME))
            val second = ButtonPositionStore(disk, newScope())
            second.save(WHATSAPP, 10f, ButtonSide.RIGHT)
            assertThat(second.side(WHATSAPP)).isEqualTo(ButtonSide.RIGHT)
            assertThat(second.side(TELEGRAM)).isEqualTo(ButtonSide.RIGHT) // Not loaded yet: default.
            disk.gate.complete(Unit)
            second.sync()

            assertThat(second.side(WHATSAPP)).isEqualTo(ButtonSide.RIGHT)
            assertThat(second.liftDp(WHATSAPP)).isEqualTo(10f)
            assertThat(second.side(TELEGRAM)).isEqualTo(ButtonSide.LEFT)
            val after = restart()
            assertThat(after.side(WHATSAPP)).isEqualTo(ButtonSide.RIGHT)
            assertThat(after.side(TELEGRAM)).isEqualTo(ButtonSide.LEFT)
        }

    /** A DataStore whose first read waits for [gate], so a test can act "before the load". */
    private class Gated(
        private val real: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        val gate = CompletableDeferred<Unit>()

        override val data: Flow<Preferences> =
            flow {
                gate.await()
                emitAll(real.data)
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            gate.await()
            return real.updateData(transform)
        }
    }

    private companion object {
        const val NAME = "button_positions"
        const val WHATSAPP = "com.whatsapp"
        const val TELEGRAM = "org.telegram.messenger"
        const val OTHER = "com.example.chat"
    }
}
