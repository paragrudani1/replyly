package com.paragrudani.replyly.ui.debug

import com.paragrudani.replyly.screen.ScreenSnapshot
import com.paragrudani.replyly.screen.ScreenSnapshotJson
import java.io.File
import java.time.Instant

/**
 * Debug dumps on disk: one [ScreenSnapshotJson] file per dump in [dir] (app-private storage, never
 * backed up), named `dump-<epochMillis>-<package>[-n].json`. Blocking IO: call it off the main thread.
 */
class DebugDumpStore(
    private val dir: File,
) {
    data class Entry(
        val name: String,
        val packageName: String,
        val capturedAt: Instant,
        val sizeBytes: Long,
    )

    fun save(snapshot: ScreenSnapshot): Entry {
        dir.mkdirs()
        val base = "dump-${snapshot.capturedAt.toEpochMilli()}-${snapshot.packageName.ifEmpty { "unknown" }}"
        val name =
            generateSequence(1) { it + 1 }
                .map { n -> if (n == 1) "$base.json" else "$base-$n.json" }
                .first { !File(dir, it).exists() }
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(ScreenSnapshotJson.encode(snapshot))
        check(tmp.renameTo(File(dir, name))) { "Could not store the dump" }
        return entryFor(File(dir, name))!!
    }

    /** Newest first. */
    fun list(): List<Entry> =
        (dir.listFiles() ?: emptyArray())
            .mapNotNull(::entryFor)
            .sortedWith(compareByDescending<Entry> { it.capturedAt }.thenByDescending { it.name })

    fun readText(name: String): String = file(name).readText()

    fun read(name: String): ScreenSnapshot = ScreenSnapshotJson.decode(readText(name))

    fun delete(name: String): Boolean = file(name).delete()

    private fun file(name: String): File {
        require(pattern.matches(name)) { "Not a dump name" }
        return File(dir, name)
    }

    private fun entryFor(file: File): Entry? {
        val match = pattern.matchEntire(file.name) ?: return null
        if (!file.isFile) return null
        val (millis, pkg) = match.destructured
        return Entry(file.name, pkg, Instant.ofEpochMilli(millis.toLong()), file.length())
    }

    private companion object {
        // Package names are letters, digits, '_' and '.', so '-' only separates the parts.
        val pattern = Regex("""dump-(\d+)-([A-Za-z0-9_.]+)(?:-\d+)?\.json""")
    }
}
