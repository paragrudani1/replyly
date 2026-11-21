package com.paragrudani.replyly.security

/**
 * The SQLCipher passphrase, kept wrapped by an Android Keystore key (ticket #3 implements it).
 * Every encrypted DB in the app (history: #3, feedback log: #6) opens with this one key.
 */
interface DatabaseKeyProvider {
    /** Creates the key on first use. The caller must not keep or log the returned bytes. */
    fun getOrCreatePassphrase(): ByteArray
}

/** Stub until ticket #3. Fails loudly so no DB is ever created with a throwaway key. */
object UnimplementedDatabaseKeyProvider : DatabaseKeyProvider {
    override fun getOrCreatePassphrase(): ByteArray =
        error("DatabaseKeyProvider is not implemented yet (ticket #3). Keep DB-backed bindings on stubs.")
}
