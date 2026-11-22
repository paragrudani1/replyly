package com.paragrudani.replyly.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

/** One-time cleanups of the `settings` DataStore, run by DataStore before the first read. */
object SettingsMigrations {
    /** Parallel mode (3 calls with fixed intents) was removed with prompt v2 (issue #26). */
    internal val PARALLEL_CALLS = booleanPreferencesKey("use_parallel_calls")

    /** Deletes the `use_parallel_calls` key. Runs only while the key is present. */
    val DropParallelCalls: DataMigration<Preferences> =
        object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences): Boolean = PARALLEL_CALLS in currentData

            override suspend fun migrate(currentData: Preferences): Preferences =
                currentData.toMutablePreferences().apply { remove(PARALLEL_CALLS) }.toPreferences()

            override suspend fun cleanUp() = Unit
        }

    val ALL: List<DataMigration<Preferences>> = listOf(DropParallelCalls)
}
