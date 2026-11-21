package com.paragrudani.replyly.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class AccessibilityNodeMapperTest {
    @Test
    fun `maps fields and children`() {
        val root =
            AccessibilityNodeInfo().apply {
                packageName = "com.whatsapp"
                className = "android.widget.FrameLayout"
                setBoundsInScreen(Rect(0, 0, 1080, 2340))
                isVisibleToUser = true
            }
        val input =
            AccessibilityNodeInfo().apply {
                packageName = "com.whatsapp"
                className = "android.widget.EditText"
                viewIdResourceName = "com.whatsapp:id/entry"
                text = "draft"
                hintText = "Message"
                contentDescription = "Message input"
                setBoundsInScreen(Rect(30, 2140, 900, 2290))
                isEditable = true
                isFocused = true
                isVisibleToUser = true
                isClickable = true
            }
        shadowOf(root).addChild(input)

        val snapshot = AccessibilityNodeMapper.snapshot(root, 1080, 2340, Instant.EPOCH)

        assertThat(snapshot.packageName).isEqualTo("com.whatsapp")
        assertThat(snapshot.root.bounds).isEqualTo(Bounds(0, 0, 1080, 2340))
        assertThat(snapshot.root.children)
            .containsExactly(
                ScreenNode(
                    className = "android.widget.EditText",
                    viewId = "com.whatsapp:id/entry",
                    text = "draft",
                    contentDescription = "Message input",
                    hint = "Message",
                    bounds = Bounds(30, 2140, 900, 2290),
                    editable = true,
                    focused = true,
                    visible = true,
                    clickable = true,
                ),
            )
    }

    @Test
    fun `password and invisible flags are kept`() {
        val node =
            AccessibilityNodeInfo().apply {
                isPassword = true
                isVisibleToUser = false
                isScrollable = true
            }

        val mapped = AccessibilityNodeMapper.map(node)

        assertThat(mapped.password).isTrue()
        assertThat(mapped.visible).isFalse()
        assertThat(mapped.scrollable).isTrue()
    }
}
