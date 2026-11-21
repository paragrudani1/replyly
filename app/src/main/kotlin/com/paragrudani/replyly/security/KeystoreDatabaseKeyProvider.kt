package com.paragrudani.replyly.security

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/** Why a new passphrase was created. Every encrypted DB was wiped, because none can be opened. */
enum class NewPassphraseReason {
    /** First run, or the wrapped passphrase file is gone. */
    NO_SAVED_PASSPHRASE,

    /** The Keystore key is gone or permanently invalidated. */
    KEY_LOST,

    /** The wrapped bytes are damaged or were wrapped with another key. */
    SAVED_PASSPHRASE_UNREADABLE,
}

/** The Keystore could not be used right now (not a lost key). Nothing was wiped; try again later. */
class DatabaseKeyUnavailableException(
    cause: Throwable,
) : Exception("Database key unavailable: ${cause::class.simpleName}", cause)

/** Persists the wrapped passphrase bytes. Never sees the plain passphrase. */
interface WrappedPassphraseStore {
    fun read(): ByteArray?

    fun write(bytes: ByteArray)

    fun delete()
}

/** Stores the wrapped bytes in one file, replaced atomically (write a temp file, then rename). */
class FileWrappedPassphraseStore(
    private val file: File,
) : WrappedPassphraseStore {
    override fun read(): ByteArray? = if (file.isFile) file.readBytes() else null

    override fun write(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun delete() {
        if (file.exists() && !file.delete()) throw IOException("Could not delete the wrapped passphrase")
    }
}

/**
 * The one SQLCipher passphrase of the app (see [DatabaseKeyProvider]).
 *
 * - The passphrase is 32 random bytes, hex-encoded to 64 ASCII bytes (no encoding surprises in
 *   SQLCipher's key derivation). It is never stored in plain form: [wrapper] encrypts it with a
 *   Keystore key that needs no user authentication, so the accessibility service can open the DB
 *   in the background, and [store] keeps only the wrapped bytes.
 * - If the passphrase cannot be recovered for good (no saved bytes, Keystore key lost or
 *   invalidated, damaged bytes), every encrypted DB is unreadable. Then [onNewPassphrase] runs
 *   (the app wires it to wipe all encrypted DBs) and a fresh passphrase is created. History is
 *   lost, but the app keeps working: no exception, so no crash loop.
 * - A transient Keystore failure throws [DatabaseKeyUnavailableException] and wipes nothing.
 * - Wipes never happen under an open DB. Every encrypted DB opens with this passphrase, so before
 *   the first successful call of a process none is open. After it, the passphrase is kept in
 *   memory (the DB open helpers keep it anyway) and returned without asking the Keystore again:
 *   a key lost while the process runs is noticed, and the DBs wiped, at the next start.
 */
class KeystoreDatabaseKeyProvider(
    private val wrapper: PassphraseWrapper,
    private val store: WrappedPassphraseStore,
    private val onNewPassphrase: (NewPassphraseReason) -> Unit,
    private val random: SecureRandom = SecureRandom(),
) : DatabaseKeyProvider {
    /** The passphrase once known in this process. Guarded by `this`. */
    private var known: ByteArray? = null

    /** Callers get a copy they may clear. */
    @Synchronized
    override fun getOrCreatePassphrase(): ByteArray = (known ?: load().also { known = it }).copyOf()

    private fun load(): ByteArray =
        try {
            val saved = store.read()
            val reason =
                if (saved == null) {
                    NewPassphraseReason.NO_SAVED_PASSPHRASE
                } else {
                    when (val result = wrapper.unwrap(saved)) {
                        is UnwrapResult.Ok -> return result.plain
                        UnwrapResult.KeyLost -> NewPassphraseReason.KEY_LOST
                        UnwrapResult.Corrupt -> NewPassphraseReason.SAVED_PASSPHRASE_UNREADABLE
                    }
                }
            createPassphrase(reason)
        } catch (e: DatabaseKeyUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw DatabaseKeyUnavailableException(e)
        }

    private fun createPassphrase(reason: NewPassphraseReason): ByteArray {
        // Wipe first: if the process dies halfway, the next start finds no saved bytes and wipes again.
        store.delete()
        onNewPassphrase(reason)
        wrapper.deleteKey()
        val raw = ByteArray(RAW_BYTES).also(random::nextBytes)
        val passphrase = raw.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        raw.fill(0)
        store.write(wrapper.wrap(passphrase))
        return passphrase
    }

    companion object {
        private const val RAW_BYTES = 32
        private const val TAG = "ReplylyDbKey"
        private const val KEY_ALIAS = "replyly_db_passphrase_wrap"
        private const val WRAPPED_FILE = "db_passphrase.wrapped"

        /** The app's provider: Android Keystore key, wrapped bytes in no-backup storage. */
        fun create(context: Context): KeystoreDatabaseKeyProvider {
            val app = context.applicationContext
            return KeystoreDatabaseKeyProvider(
                wrapper = AesGcmPassphraseWrapper(AndroidKeystoreKeySource(KEY_ALIAS)),
                store = FileWrappedPassphraseStore(File(app.noBackupFilesDir, WRAPPED_FILE)),
                onNewPassphrase = { reason ->
                    val wiped = EncryptedDatabases.wipeAll(app)
                    Log.w(TAG, "new database passphrase reason=$reason wipedDatabases=$wiped")
                },
            )
        }
    }
}

/** Every SQLite DB of the app is encrypted with the one passphrase, so a new passphrase wipes them all. */
object EncryptedDatabases {
    private val sidecarSuffixes = listOf("-journal", "-wal", "-shm")

    /** @return how many DBs were deleted. */
    fun wipeAll(context: Context): Int =
        context
            .databaseList()
            .filterNot { name -> sidecarSuffixes.any(name::endsWith) }
            .count { context.deleteDatabase(it) }
}
