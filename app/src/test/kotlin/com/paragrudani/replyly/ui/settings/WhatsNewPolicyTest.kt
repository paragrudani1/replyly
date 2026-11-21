package com.paragrudani.replyly.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WhatsNewPolicyTest {
    private class FakeStore(
        var value: Long? = null,
    ) : LastSeenVersionStore {
        var writes = 0

        override fun get(): Long? = value

        override fun set(versionCode: Long) {
            value = versionCode
            writes++
        }
    }

    @Test
    fun `a fresh install records the version silently`() {
        val store = FakeStore()
        val shown = WhatsNew(store, currentVersion = 5, freshInstall = true, hasNotes = true).consume()
        assertThat(shown).isFalse()
        assertThat(store.value).isEqualTo(5)
    }

    @Test
    fun `an update from a version older than this feature shows once`() {
        val store = FakeStore()
        assertThat(WhatsNew(store, currentVersion = 5, freshInstall = false, hasNotes = true).consume()).isTrue()
        assertThat(store.value).isEqualTo(5)
        assertThat(WhatsNew(store, currentVersion = 5, freshInstall = false, hasNotes = true).consume()).isFalse()
    }

    @Test
    fun `a newer version than last seen shows, then not again`() {
        val store = FakeStore(4)
        assertThat(WhatsNew(store, currentVersion = 5, freshInstall = false, hasNotes = true).consume()).isTrue()
        assertThat(WhatsNew(store, currentVersion = 5, freshInstall = false, hasNotes = true).consume()).isFalse()
        assertThat(WhatsNew(store, currentVersion = 6, freshInstall = false, hasNotes = true).consume()).isTrue()
    }

    @Test
    fun `the same version shows nothing and writes nothing`() {
        val store = FakeStore(5)
        assertThat(WhatsNew(store, currentVersion = 5, freshInstall = false, hasNotes = true).consume()).isFalse()
        assertThat(store.writes).isEqualTo(0)
    }

    @Test
    fun `a downgrade shows nothing but remembers the installed version`() {
        val store = FakeStore(9)
        assertThat(WhatsNew(store, currentVersion = 5, freshInstall = false, hasNotes = true).consume()).isFalse()
        assertThat(store.value).isEqualTo(5)
    }

    @Test
    fun `no notes means no sheet, and the version still counts as seen`() {
        val store = FakeStore(4)
        assertThat(WhatsNew(store, currentVersion = 5, freshInstall = false, hasNotes = false).consume()).isFalse()
        assertThat(store.value).isEqualTo(5)
    }

    @Test
    fun `decide is pure and covers every case`() {
        assertThat(
            WhatsNew.decide(5, null, freshInstall = true, hasNotes = true),
        ).isEqualTo(WhatsNew.Decision.RecordSilently)
        assertThat(WhatsNew.decide(5, null, freshInstall = false, hasNotes = true)).isEqualTo(WhatsNew.Decision.Show)
        assertThat(WhatsNew.decide(5, 4, freshInstall = true, hasNotes = true)).isEqualTo(WhatsNew.Decision.Show)
        assertThat(WhatsNew.decide(5, 5, freshInstall = false, hasNotes = true)).isEqualTo(WhatsNew.Decision.Nothing)
        assertThat(
            WhatsNew.decide(5, 6, freshInstall = false, hasNotes = true),
        ).isEqualTo(WhatsNew.Decision.RecordSilently)
        assertThat(
            WhatsNew.decide(5, 4, freshInstall = false, hasNotes = false),
        ).isEqualTo(WhatsNew.Decision.RecordSilently)
    }
}
