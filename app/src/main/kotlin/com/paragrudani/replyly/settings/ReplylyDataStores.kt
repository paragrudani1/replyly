package com.paragrudani.replyly.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's Preferences DataStore files (under `files/datastore/`, excluded from backup). Create
 * each one once per process: AppGraph's lazy bindings guarantee that.
 */
object ReplylyDataStores {
    const val SETTINGS = "settings"
    const val API_KEY = "api_key"
    const val COST = "cost"

    /** Polish #3: where the user dragged the floating button, per app (not secret). */
    const val BUTTON_POSITIONS = "button_positions"

    fun create(
        context: Context,
        name: String,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // A corrupt file falls back to defaults instead of crashing the app on every start.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.applicationContext.preferencesDataStoreFile(name) },
        )
}
