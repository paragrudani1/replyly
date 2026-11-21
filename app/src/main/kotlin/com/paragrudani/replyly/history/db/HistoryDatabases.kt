package com.paragrudani.replyly.history.db

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import androidx.room.Room
import com.paragrudani.replyly.security.DatabaseKeyProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Opens the DB on first use, off the main thread. If opening fails (for example a transient
 * Keystore error), the next call tries again.
 */
class HistoryDatabaseHandle(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val open: () -> HistoryDatabase,
) {
    private val mutex = Mutex()

    @Volatile private var database: HistoryDatabase? = null

    suspend fun get(): HistoryDatabase =
        database ?: mutex.withLock {
            database ?: withContext(ioDispatcher) { open() }.also { database = it }
        }
}

object HistoryDatabases {
    const val NAME = "history.db"
    private const val TAG = "ReplylyHistoryDb"

    @Volatile private var shared: HistoryDatabaseHandle? = null

    /** The process-wide handle, shared by the history repository and the contact note store. */
    fun shared(
        context: Context,
        keyProvider: DatabaseKeyProvider,
    ): HistoryDatabaseHandle =
        shared ?: synchronized(this) {
            shared ?: HistoryDatabaseHandle { openEncrypted(context.applicationContext, keyProvider) }
                .also { shared = it }
        }

    /** Room on SQLCipher. A file that the passphrase cannot open is wiped and created again. */
    fun openEncrypted(
        context: Context,
        keyProvider: DatabaseKeyProvider,
    ): HistoryDatabase {
        System.loadLibrary("sqlcipher")
        return openOrWipe(
            open = {
                // The factory keeps the bytes to open later connections, so they are not cleared here.
                Room
                    .databaseBuilder(context, HistoryDatabase::class.java, NAME)
                    .openHelperFactory(SupportOpenHelperFactory(keyProvider.getOrCreatePassphrase()))
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
            },
            discard = { database ->
                database.close()
                context.deleteDatabase(NAME)
            },
        )
    }

    /**
     * Opens [open] and forces the file open. On a [SQLiteDatabaseCorruptException] (SQLCipher's
     * error for a wrong passphrase or a file that is not a database) it logs the class name,
     * [discard]s the DB and opens a fresh one: losing history beats a service that crashes on
     * every start. Any other error (full disk, I/O error, locked DB) is thrown and wipes nothing.
     */
    fun openOrWipe(
        open: () -> HistoryDatabase,
        discard: (HistoryDatabase) -> Unit,
    ): HistoryDatabase {
        val first = open()
        try {
            first.openHelper.writableDatabase
            return first
        } catch (e: SQLiteDatabaseCorruptException) {
            Log.w(TAG, "history db unreadable, recreating: ${e::class.simpleName}")
            discard(first)
        }
        return open().also { it.openHelper.writableDatabase }
    }
}
