package com.paragrudani.replyly.overlay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Where the user dragged the floating button, per app (package name): the gap between the
 * button and the keyboard top, in dp (see [ButtonPlacement.liftOf]), and the edge it rests on
 * ([ButtonSide]). Not secret: package names, a number and a side, no chat content. Lives in its
 * own DataStore file ([com.paragrudani.replyly.settings.ReplylyDataStores.BUTTON_POSITIONS]):
 * `lift_dp:<pkg>` (float) and, since #54, `side:<pkg>` (`left` / `right`). A lift without a side
 * (saved before #54) reads as right; a side without a lift is ignored. No migration.
 *
 * Reads are synchronous ([liftDp], [side]) because the button is placed on the main thread on every
 * window change. They come from an in-memory copy that is loaded once at start and updated at
 * once by [save] / [reset] / [resetAll]; the disk writes run after, one at a time, in call order.
 * Until the first load finishes, apps read as "never dragged" (default spot).
 */
class ButtonPositionStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    /** One app's remembered spot. */
    private data class Spot(
        val liftDp: Float,
        val side: ButtonSide,
    )

    @Volatile private var cache: Map<String, Spot> = emptyMap()

    /** Keys changed before the load finished: the load must not bring back their old values. */
    private val touchedBeforeLoad = mutableSetOf<String>()

    /** [resetAll] ran before the load finished: nothing read from disk may come back. */
    private var clearedBeforeLoad = false
    private var loadedFlag = false
    private val loaded = CompletableDeferred<Unit>()
    private val lock = Any()
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                val disk = readDisk()
                synchronized(lock) {
                    if (!clearedBeforeLoad) cache = disk.filterKeys { it !in touchedBeforeLoad } + cache
                    loadedFlag = true
                    touchedBeforeLoad.clear()
                    clearedBeforeLoad = false
                }
            } finally {
                // Also on an unexpected failure or cancellation: sync() must never wait forever.
                loaded.complete(Unit)
            }
            for (write in writes) {
                try {
                    write()
                } catch (e: java.io.IOException) {
                    // Disk full or similar: the spot stays remembered until the app restarts.
                }
            }
        }
    }

    /** Every saved spot on disk; empty if the file can't be read (default spots, nothing lost in memory). */
    private suspend fun readDisk(): Map<String, Spot> =
        try {
            val prefs = dataStore.data.first().asMap()
            val sides =
                prefs
                    .mapNotNull { (key, value) ->
                        val pkg = key.name.removePrefix(SIDE_PREFIX).takeIf { key.name.startsWith(SIDE_PREFIX) }
                        val side = (value as? String)?.let(::sideOf)
                        if (pkg != null && side != null) pkg to side else null
                    }.toMap()
            prefs
                .mapNotNull { (key, value) ->
                    val pkg = key.name.removePrefix(PREFIX).takeIf { key.name.startsWith(PREFIX) }
                    if (pkg != null && value is Float) pkg to Spot(value, sides[pkg] ?: ButtonSide.RIGHT) else null
                }.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }

    /** The remembered gap for [packageName] in dp, or `null` if the user never dragged it there. */
    fun liftDp(packageName: String): Float? = cache[packageName]?.liftDp

    /** The edge the button rests on in [packageName]; right if never moved there or saved before sides. */
    fun side(packageName: String): ButtonSide = cache[packageName]?.side ?: ButtonSide.RIGHT

    /** Remembers (lift, side) for [packageName]; both keys go to disk in one write. */
    fun save(
        packageName: String,
        liftDp: Float,
        side: ButtonSide = ButtonSide.RIGHT,
    ) {
        change(packageName) { it + (packageName to Spot(liftDp, side)) }
        writes.trySend {
            dataStore.edit {
                it[key(packageName)] = liftDp
                it[sideKey(packageName)] = side.name.lowercase()
            }
        }
    }

    /** Back to the default spot in [packageName]. */
    fun reset(packageName: String) {
        change(packageName) { it - packageName }
        writes.trySend {
            dataStore.edit {
                it.remove(key(packageName))
                it.remove(sideKey(packageName))
            }
        }
    }

    /** Back to the default spot (right side) in every app ("Reset button positions" in Settings). */
    fun resetAll() {
        synchronized(lock) {
            if (!loadedFlag) clearedBeforeLoad = true
            cache = emptyMap()
        }
        writes.trySend {
            dataStore.edit { prefs ->
                prefs
                    .asMap()
                    .keys
                    .filter { it.name.startsWith(PREFIX) || it.name.startsWith(SIDE_PREFIX) }
                    .forEach { prefs.remove(it) }
            }
        }
    }

    /** Waits until the first load is done and every earlier change is on disk (tests). */
    suspend fun sync() {
        loaded.await()
        val done = CompletableDeferred<Unit>()
        writes.send { done.complete(Unit) }
        done.await()
    }

    private fun change(
        packageName: String,
        transform: (Map<String, Spot>) -> Map<String, Spot>,
    ) {
        synchronized(lock) {
            if (!loadedFlag) touchedBeforeLoad += packageName
            cache = transform(cache)
        }
    }

    private fun key(packageName: String) = floatPreferencesKey(PREFIX + packageName)

    private fun sideKey(packageName: String) = stringPreferencesKey(SIDE_PREFIX + packageName)

    private fun sideOf(value: String): ButtonSide? = ButtonSide.entries.firstOrNull { it.name.lowercase() == value }

    private companion object {
        const val PREFIX = "lift_dp:"
        const val SIDE_PREFIX = "side:"
    }
}
