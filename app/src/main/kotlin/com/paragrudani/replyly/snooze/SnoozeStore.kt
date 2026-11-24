package com.paragrudani.replyly.snooze

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The bubble's snooze (#56): one snoozed-until time in epoch ms ([Long.MAX_VALUE] = until the
 * user turns it back on; `null` = not snoozed), in its own DataStore file
 * ([com.paragrudani.replyly.settings.ReplylyDataStores.SNOOZE]), so it survives a restart or a
 * reboot. Not secret: one number.
 *
 * Like `ButtonPositionStore`, reads are synchronous ([until] is a hot [StateFlow]) and changes
 * apply in memory at once; the disk writes follow, one at a time, in call order. Until the first
 * load finishes the bubble reads as not snoozed, and a change made before that wins over the disk.
 */
class SnoozeStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private val state = MutableStateFlow<Long?>(null)

    /** The snoozed-until time; `null` when not snoozed. An expired value stays until [end]. */
    val until: StateFlow<Long?> = state.asStateFlow()

    private val lock = Any()
    private var loadedFlag = false
    private var changedBeforeLoad = false
    private val loaded = CompletableDeferred<Unit>()
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                val disk = readDisk()
                synchronized(lock) {
                    if (!changedBeforeLoad) state.value = disk
                    loadedFlag = true
                }
            } finally {
                // Also on failure or cancellation: sync() must never wait forever.
                loaded.complete(Unit)
            }
            for (write in writes) {
                try {
                    write()
                } catch (e: java.io.IOException) {
                    // Disk full or similar: the snooze holds in memory until the app restarts.
                }
            }
        }
    }

    private suspend fun readDisk(): Long? =
        try {
            dataStore.data.first()[KEY]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An unreadable file: not snoozed (the button shows), never a crash.
            null
        }

    /** True while [nowMillis] is before the snoozed-until time. */
    fun isSnoozed(nowMillis: Long): Boolean = state.value?.let { nowMillis < it } ?: false

    fun snooze(untilMillis: Long) {
        set(untilMillis)
        writes.trySend { dataStore.edit { it[KEY] = untilMillis } }
    }

    /** Ends the snooze (timer, shake, "End snooze now"). */
    fun end() {
        set(null)
        writes.trySend { dataStore.edit { it.remove(KEY) } }
    }

    /** Waits until the first load is done and every earlier change is on disk (tests). */
    suspend fun sync() {
        loaded.await()
        val done = CompletableDeferred<Unit>()
        writes.send { done.complete(Unit) }
        done.await()
    }

    private fun set(value: Long?) {
        synchronized(lock) {
            if (!loadedFlag) changedBeforeLoad = true
            state.value = value
        }
    }

    private companion object {
        val KEY = longPreferencesKey("snoozed_until")
    }
}
