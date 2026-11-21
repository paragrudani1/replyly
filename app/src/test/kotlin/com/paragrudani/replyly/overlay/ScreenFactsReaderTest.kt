package com.paragrudani.replyly.overlay

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.screen.Bounds
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ScreenFactsReaderTest {
    private fun window(
        id: Int,
        type: Int,
        bounds: Rect,
        active: Boolean = false,
    ): AccessibilityWindowInfo =
        AccessibilityWindowInfo.obtain().also {
            shadowOf(it).setId(id)
            shadowOf(it).setType(type)
            shadowOf(it).setBoundsInScreen(bounds)
            shadowOf(it).setActive(active)
        }

    @Test
    fun `maps windows and the focused input without walking the tree`() {
        val focused =
            AccessibilityNodeInfo().apply {
                packageName = "com.whatsapp"
                viewIdResourceName = "com.whatsapp:id/entry"
                setBoundsInScreen(Rect(30, 1300, 900, 1420))
                isEditable = true
            }
        val reader =
            ScreenFactsReader(
                windows = {
                    listOf(
                        window(7, AccessibilityWindowInfo.TYPE_APPLICATION, Rect(0, 0, 1080, 2340), active = true),
                        window(9, AccessibilityWindowInfo.TYPE_INPUT_METHOD, Rect(0, 1440, 1080, 2340)),
                        window(3, AccessibilityWindowInfo.TYPE_SYSTEM, Rect(0, 0, 1080, 80)),
                    )
                },
                focusedInput = { focused },
                windowIdOf = { 7 },
            )

        val facts = reader.read()

        assertThat(facts.windows)
            .containsExactly(
                WindowFacts(7, WindowKind.APPLICATION, Bounds(0, 0, 1080, 2340), active = true),
                WindowFacts(9, WindowKind.INPUT_METHOD, Bounds(0, 1440, 1080, 2340)),
                WindowFacts(3, WindowKind.OTHER, Bounds(0, 0, 1080, 80)),
            )
        assertThat(facts.focusedInput)
            .isEqualTo(
                FocusedInput(
                    windowId = 7,
                    packageName = "com.whatsapp",
                    editable = true,
                    password = false,
                    viewId = "com.whatsapp:id/entry",
                    bounds = Bounds(30, 1300, 900, 1420),
                ),
            )
    }

    @Test
    fun `no focused node - no focused input`() {
        val reader = ScreenFactsReader(windows = { emptyList() }, focusedInput = { null })

        assertThat(reader.read()).isEqualTo(ScreenFacts(emptyList(), null))
    }

    @Test
    fun `a failing system call reads as nothing focused, nothing shown`() {
        val reader = ScreenFactsReader(windows = { throw IllegalStateException() }, focusedInput = { null })

        assertThat(reader.read()).isEqualTo(ScreenFacts(emptyList(), null))
    }
}
