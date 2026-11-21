package com.paragrudani.replyly.ui.debug

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.testing.Fixtures
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class DebugDumpStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val store by lazy { DebugDumpStore(folder.root.resolve("debug-dumps")) }

    private fun snapshot(
        path: String,
        at: String,
    ) = Fixtures.snapshot(path).copy(capturedAt = Instant.parse(at))

    @Test
    fun `saved dumps list newest first and read back unchanged`() {
        val older = snapshot("whatsapp/synthetic-group.json", "2026-11-21T10:00:00Z")
        val newer = snapshot("telegram/synthetic-group.json", "2026-11-21T11:00:00Z")
        store.save(older)
        store.save(newer)

        val entries = store.list()

        assertThat(entries.map { it.packageName }).containsExactly("org.telegram.messenger", "com.whatsapp").inOrder()
        assertThat(entries[0].capturedAt).isEqualTo(newer.capturedAt)
        assertThat(entries[0].sizeBytes).isGreaterThan(0L)
        assertThat(store.read(entries[1].name)).isEqualTo(older)
    }

    @Test
    fun `two dumps in the same millisecond both survive`() {
        val snapshot = snapshot("whatsapp/synthetic-group.json", "2026-11-21T10:00:00Z")
        store.save(snapshot)
        store.save(snapshot)

        assertThat(store.list().map { it.name }.toSet()).hasSize(2)
    }

    @Test
    fun `delete removes one dump`() {
        val entry = store.save(snapshot("whatsapp/synthetic-group.json", "2026-11-21T10:00:00Z"))

        assertThat(store.delete(entry.name)).isTrue()
        assertThat(store.list()).isEmpty()
    }

    @Test
    fun `names outside the dump folder are refused`() {
        assertThrows(IllegalArgumentException::class.java) { store.readText("../shared_prefs/x.xml") }
        assertThrows(IllegalArgumentException::class.java) { store.delete("dump-1-com.whatsapp.json/../../x") }
    }

    @Test
    fun `stray files in the folder are ignored`() {
        folder.root
            .resolve("debug-dumps")
            .apply { mkdirs() }
            .resolve("notes.txt")
            .writeText("x")

        assertThat(store.list()).isEmpty()
    }
}
