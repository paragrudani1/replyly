package com.paragrudani.replyly.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.testing.TestDataStores
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The Keystore key itself only exists on a device; here a software AES key stands in for it. */
class EncryptedApiKeyStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val stores by lazy { TestDataStores(tmp.root) }
    private var key: SecretKey = newKey()
    private val cipher = AesGcmCipher { key }
    private val apiKey = "sk-or-v1-abcdef0123456789"

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store() = EncryptedApiKeyStore(stores.open(ReplylyDataStores.API_KEY), cipher)

    @After fun tearDown() = stores.closeAll()

    @Test
    fun `starts without a key`() =
        runBlocking<Unit> {
            val store = store()
            assertThat(store.get()).isNull()
            store.hasKey.test { assertThat(awaitItem()).isFalse() }
        }

    @Test
    fun `stores the key and reports it as saved`() =
        runBlocking<Unit> {
            val store = store()
            store.set(apiKey)
            assertThat(store.get()).isEqualTo(apiKey)
            store.hasKey.test { assertThat(awaitItem()).isTrue() }
        }

    @Test
    fun `surrounding whitespace from a paste is dropped`() =
        runBlocking<Unit> {
            val store = store()
            store.set("  $apiKey\n")
            assertThat(store.get()).isEqualTo(apiKey)
        }

    @Test
    fun `the file on disk never holds the plain key`() =
        runBlocking<Unit> {
            store().set(apiKey)
            stores.closeAll()
            val onDisk = stores.file(ReplylyDataStores.API_KEY).readBytes().toString(Charsets.ISO_8859_1)
            assertThat(onDisk).doesNotContain(apiKey)
            assertThat(onDisk).doesNotContain("abcdef0123")
        }

    @Test
    fun `the key survives a restart`() =
        runBlocking<Unit> {
            store().set(apiKey)
            val reopened = EncryptedApiKeyStore(stores.reopen(ReplylyDataStores.API_KEY), cipher)
            assertThat(reopened.get()).isEqualTo(apiKey)
        }

    @Test
    fun `a blank key clears it`() =
        runBlocking<Unit> {
            val store = store()
            store.set(apiKey)
            store.set("   ")
            assertThat(store.get()).isNull()
            store.hasKey.test { assertThat(awaitItem()).isFalse() }
        }

    @Test
    fun `a lost keystore key reads as no key and clears the stale blob`() =
        runBlocking<Unit> {
            val store = store()
            store.set(apiKey)
            key = newKey() // what a wiped Keystore (reinstall, new device) looks like

            assertThat(store.get()).isNull()
            store.hasKey.test { assertThat(awaitItem()).isFalse() }
        }
}
