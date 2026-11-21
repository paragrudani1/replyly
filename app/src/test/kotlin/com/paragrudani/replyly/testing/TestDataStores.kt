package com.paragrudani.replyly.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Real Preferences DataStores on temp files, for plain JVM tests. A file may have only one open
 * store at a time, so [reopen] closes the previous store of that file first.
 */
class TestDataStores(
    private val dir: File,
) {
    private val scopes = mutableMapOf<String, CoroutineScope>()

    fun open(name: String): DataStore<Preferences> {
        // Wait until the old store is gone: DataStore frees the file only once its scope completes,
        // and a second store on a still-registered file throws "multiple DataStores active".
        scopes.remove(name)?.let { runBlocking { it.coroutineContext.job.cancelAndJoin() } }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes[name] = scope
        return PreferenceDataStoreFactory.create(scope = scope) { File(dir, "$name.preferences_pb") }
    }

    fun reopen(name: String): DataStore<Preferences> = open(name)

    fun file(name: String): File = File(dir, "$name.preferences_pb")

    fun closeAll() {
        scopes.values.forEach { it.cancel() }
        scopes.clear()
    }
}
