package com.paragrudani.replyly.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Real Preferences DataStores on temp files, for plain JVM tests. A file may have only one open
 * active store at a time (DataStore throws "multiple DataStores active for the same file"), and
 * the file is only released once the old store's scope has *completed*, not merely been cancelled.
 * So each store gets its own scope, and [reopen] cancels and joins the previous one before opening
 * the next.
 */
class TestDataStores(
    private val dir: File,
) {
    private val scopes = mutableMapOf<String, CoroutineScope>()

    /** Opens the first store for [name]. Use [reopen] to simulate a restart. */
    fun open(name: String): DataStore<Preferences> {
        check(name !in scopes) { "$name is already open; use reopen()" }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes[name] = scope
        return PreferenceDataStoreFactory.create(scope = scope) { File(dir, "$name.preferences_pb") }
    }

    /** Simulates a process restart: fully shuts down the current store of [name], then opens a new one. */
    suspend fun reopen(name: String): DataStore<Preferences> {
        scopes
            .remove(name)
            ?.coroutineContext
            ?.get(Job)
            ?.cancelAndJoin()
        return open(name)
    }

    fun file(name: String): File = File(dir, "$name.preferences_pb")

    fun closeAll() {
        runBlocking { scopes.values.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }
        scopes.clear()
    }
}
