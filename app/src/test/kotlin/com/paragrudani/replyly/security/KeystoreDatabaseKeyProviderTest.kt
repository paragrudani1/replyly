package com.paragrudani.replyly.security

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Software AES key standing in for the Android Keystore, which Robolectric does not have. */
class FakeKeySource : SecretKeySource {
    var key: SecretKey? = null
    var created = 0
    var failWith: Exception? = null

    override fun get(): SecretKey? {
        failWith?.let { throw it }
        return key
    }

    override fun create(): SecretKey {
        failWith?.let { throw it }
        created++
        return KeyGenerator
            .getInstance("AES")
            .apply { init(256) }
            .generateKey()
            .also { key = it }
    }

    override fun delete() {
        key = null
    }
}

class AesGcmPassphraseWrapperTest {
    private val keys = FakeKeySource()
    private val wrapper = AesGcmPassphraseWrapper(keys)
    private val secret = "0123456789abcdef".toByteArray()

    @Test
    fun `unwrap returns what was wrapped`() {
        val wrapped = wrapper.wrap(secret)

        val result = wrapper.unwrap(wrapped)

        assertThat(result).isInstanceOf(UnwrapResult.Ok::class.java)
        assertThat((result as UnwrapResult.Ok).plain).isEqualTo(secret)
    }

    @Test
    fun `wrapped bytes do not contain the secret`() {
        val wrapped = wrapper.wrap(secret)

        assertThat(String(wrapped, Charsets.ISO_8859_1)).doesNotContain(String(secret))
    }

    @Test
    fun `each wrap uses a fresh IV and the key is created once`() {
        val first = wrapper.wrap(secret)
        val second = wrapper.wrap(secret)

        assertThat(first).isNotEqualTo(second)
        assertThat(keys.created).isEqualTo(1)
    }

    @Test
    fun `a missing key is reported as lost`() {
        val wrapped = wrapper.wrap(secret)
        keys.delete()

        assertThat(wrapper.unwrap(wrapped)).isEqualTo(UnwrapResult.KeyLost)
    }

    @Test
    fun `a different key is reported as corrupt`() {
        val wrapped = wrapper.wrap(secret)
        keys.create()

        assertThat(wrapper.unwrap(wrapped)).isEqualTo(UnwrapResult.Corrupt)
    }

    @Test
    fun `tampered or truncated bytes are reported as corrupt`() {
        val wrapped = wrapper.wrap(secret)
        val tampered = wrapped.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertThat(wrapper.unwrap(tampered)).isEqualTo(UnwrapResult.Corrupt)
        assertThat(wrapper.unwrap(wrapped.copyOf(5))).isEqualTo(UnwrapResult.Corrupt)
        assertThat(wrapper.unwrap(ByteArray(0))).isEqualTo(UnwrapResult.Corrupt)
        assertThat(wrapper.unwrap(byteArrayOf(9, 12) + ByteArray(40))).isEqualTo(UnwrapResult.Corrupt)
    }
}

class KeystoreDatabaseKeyProviderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val keys = FakeKeySource()
    private val resets = mutableListOf<NewPassphraseReason>()
    private val storeFile by lazy { File(temp.root, "db_passphrase.wrapped") }

    private fun provider() =
        KeystoreDatabaseKeyProvider(
            wrapper = AesGcmPassphraseWrapper(keys),
            store = FileWrappedPassphraseStore(storeFile),
            onNewPassphrase = { resets += it },
        )

    @Test
    fun `first use creates a passphrase and stores only the wrapped bytes`() {
        val passphrase = provider().getOrCreatePassphrase()

        assertThat(passphrase.size).isEqualTo(64)
        assertThat(String(passphrase, Charsets.US_ASCII)).matches("[0-9a-f]{64}")
        assertThat(storeFile.exists()).isTrue()
        assertThat(String(storeFile.readBytes(), Charsets.ISO_8859_1)).doesNotContain(String(passphrase))
        assertThat(resets).containsExactly(NewPassphraseReason.NO_SAVED_PASSPHRASE)
    }

    @Test
    fun `the same passphrase comes back, also after a restart`() {
        val first = provider().getOrCreatePassphrase()

        val again = provider().getOrCreatePassphrase()

        assertThat(again).isEqualTo(first)
        assertThat(resets).hasSize(1)
    }

    @Test
    fun `callers get a copy they may clear`() {
        val provider = provider()
        val first = provider.getOrCreatePassphrase()
        val expected = first.copyOf()

        first.fill(0)

        assertThat(provider.getOrCreatePassphrase()).isEqualTo(expected)
    }

    @Test
    fun `a lost keystore key wipes the databases and starts over`() {
        val old = provider().getOrCreatePassphrase()
        keys.delete()

        val fresh = provider().getOrCreatePassphrase()

        assertThat(fresh).isNotEqualTo(old)
        assertThat(resets).containsExactly(NewPassphraseReason.NO_SAVED_PASSPHRASE, NewPassphraseReason.KEY_LOST)
        assertThat(provider().getOrCreatePassphrase()).isEqualTo(fresh)
    }

    @Test
    fun `unreadable wrapped bytes wipe the databases and start over`() {
        val old = provider().getOrCreatePassphrase()
        storeFile.writeBytes(byteArrayOf(1, 2, 3))

        val fresh = provider().getOrCreatePassphrase()

        assertThat(fresh).isNotEqualTo(old)
        assertThat(resets.last()).isEqualTo(NewPassphraseReason.SAVED_PASSPHRASE_UNREADABLE)
        assertThat(provider().getOrCreatePassphrase()).isEqualTo(fresh)
    }

    @Test
    fun `a missing wrapped file with a surviving key starts over`() {
        provider().getOrCreatePassphrase()
        storeFile.delete()

        provider().getOrCreatePassphrase()

        assertThat(resets).containsExactly(
            NewPassphraseReason.NO_SAVED_PASSPHRASE,
            NewPassphraseReason.NO_SAVED_PASSPHRASE,
        )
    }

    @Test
    fun `a transient keystore failure throws without wiping anything`() {
        val old = provider().getOrCreatePassphrase()
        val savedBytes = storeFile.readBytes()
        keys.failWith = KeyStoreException("busy")

        assertThrows(DatabaseKeyUnavailableException::class.java) { provider().getOrCreatePassphrase() }

        assertThat(storeFile.readBytes()).isEqualTo(savedBytes)
        assertThat(resets).hasSize(1)
        keys.failWith = null
        assertThat(provider().getOrCreatePassphrase()).isEqualTo(old)
    }
}

class FileWrappedPassphraseStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `reads back what was written, and nothing after delete`() {
        val store = FileWrappedPassphraseStore(File(temp.root, "sub/wrapped"))
        assertThat(store.read()).isNull()

        store.write(byteArrayOf(1, 2, 3))
        store.write(byteArrayOf(4, 5))

        assertThat(store.read()).isEqualTo(byteArrayOf(4, 5))
        store.delete()
        assertThat(store.read()).isNull()
    }
}
