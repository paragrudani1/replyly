package com.paragrudani.replyly.screen

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.testing.Fixtures
import org.junit.Test
import java.time.Instant

class ScreenSnapshotJsonTest {
    private val snapshot =
        ScreenSnapshot(
            packageName = "com.whatsapp",
            capturedAt = Instant.parse("2026-11-21T10:15:30Z"),
            screenWidth = 1080,
            screenHeight = 2340,
            root =
                ScreenNode(
                    className = "android.widget.FrameLayout",
                    bounds = Bounds(0, 0, 1080, 2340),
                    children =
                        listOf(
                            ScreenNode(
                                className = "android.widget.EditText",
                                viewId = "com.whatsapp:id/entry",
                                hint = "Message",
                                bounds = Bounds(30, 2140, 900, 2290),
                                editable = true,
                                focused = true,
                            ),
                        ),
                ),
        )

    @Test
    fun `round trip keeps every field`() {
        val decoded = ScreenSnapshotJson.decode(ScreenSnapshotJson.encode(snapshot))

        assertThat(decoded).isEqualTo(snapshot)
    }

    @Test
    fun `json is short and readable`() {
        val json = ScreenSnapshotJson.encode(snapshot)

        assertThat(json).contains("\"bounds\": \"[30,2140][900,2290]\"")
        assertThat(json).contains("\"capturedAt\": \"2026-11-21T10:15:30Z\"")
        // Defaults and nulls are left out.
        assertThat(json).doesNotContain("\"password\"")
        assertThat(json).doesNotContain("null")
    }

    @Test
    fun `fixture file is exactly what the encoder writes`() {
        val text = Fixtures.text("sample/minimal_chat.json")

        assertThat(ScreenSnapshotJson.encode(ScreenSnapshotJson.decode(text))).isEqualTo(text)
    }

    @Test
    fun `unknown fields from newer dumps are ignored`() {
        val json =
            """{"packageName":"a","capturedAt":"2026-01-01T00:00:00Z","screenWidth":1,"screenHeight":1,
            "futureField":42,"root":{"bounds":"[0,0][1,1]","alsoNew":true}}"""

        assertThat(ScreenSnapshotJson.decode(json).root.bounds).isEqualTo(Bounds(0, 0, 1, 1))
    }
}
