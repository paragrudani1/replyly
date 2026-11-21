package com.paragrudani.replyly.testing

import com.paragrudani.replyly.screen.ScreenSnapshot
import com.paragrudani.replyly.screen.ScreenSnapshotJson

/** Loads screen-tree fixtures from `app/src/test/resources/fixtures/`. */
object Fixtures {
    /** @param path relative to `fixtures/`, for example `"whatsapp/one_to_one.json"`. */
    fun text(path: String): String {
        val stream =
            Fixtures::class.java.classLoader!!.getResourceAsStream("fixtures/$path")
                ?: error("No fixture at app/src/test/resources/fixtures/$path")
        return stream.bufferedReader().use { it.readText() }
    }

    fun snapshot(path: String): ScreenSnapshot = ScreenSnapshotJson.decode(text(path))
}
