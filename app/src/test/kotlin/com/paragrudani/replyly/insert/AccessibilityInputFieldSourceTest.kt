package com.paragrudani.replyly.insert

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.screen.Bounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AccessibilityInputFieldSourceTest {
    private val entry =
        AccessibilityNodeInfo().apply {
            packageName = WHATSAPP
            viewIdResourceName = "com.whatsapp:id/entry"
            setBoundsInScreen(Rect(30, 2200, 900, 2320))
            isEditable = true
            isFocused = true
            shadowOf(this).setOnPerformActionListener { action, args ->
                performed += action to args
                true
            }
        }

    /** Robolectric hands out copies of child nodes, so record actions through a listener. */
    private val performed = mutableListOf<Pair<Int, Bundle?>>()
    private val label =
        AccessibilityNodeInfo().apply {
            packageName = WHATSAPP
            text = "not editable"
        }
    private val chatWindow = window(id = 7, type = AccessibilityWindowInfo.TYPE_APPLICATION, root = tree())
    private val keyboard = window(id = 9, type = AccessibilityWindowInfo.TYPE_INPUT_METHOD, root = null)
    private val source = AccessibilityInputFieldSource(windows = { listOf(chatWindow, keyboard) })

    private fun tree(): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo().apply { packageName = WHATSAPP }
        val bar = AccessibilityNodeInfo().apply { packageName = WHATSAPP }
        shadowOf(root).addChild(label)
        shadowOf(root).addChild(bar)
        shadowOf(bar).addChild(entry)
        return root
    }

    private fun window(
        id: Int,
        type: Int,
        root: AccessibilityNodeInfo?,
    ): AccessibilityWindowInfo =
        AccessibilityWindowInfo.obtain().also {
            shadowOf(it).setId(id)
            shadowOf(it).setType(type)
            if (root != null) shadowOf(it).setRoot(root)
        }

    @Test
    fun `finds the editable fields of a window, deep in the tree`() {
        val fields = source.fields(7, viewId = "com.whatsapp:id/entry")!!

        assertThat(fields).hasSize(1)
        with(fields.single()) {
            assertThat(packageName).isEqualTo(WHATSAPP)
            assertThat(viewId).isEqualTo("com.whatsapp:id/entry")
            assertThat(bounds).isEqualTo(Bounds(30, 2200, 900, 2320))
            assertThat(focused).isTrue()
            assertThat(password).isFalse()
        }
    }

    @Test
    fun `a window that is gone gives null`() {
        assertThat(source.fields(42, viewId = null)).isNull()
    }

    @Test
    fun `setText sends ACTION_SET_TEXT with the text, and nothing else`() {
        val field = source.fields(7, viewId = null)!!.single()

        assertThat(field.setText("ok, 6 pm")).isTrue()

        val (action, args) = performed.single()
        assertThat(action).isEqualTo(AccessibilityNodeInfo.ACTION_SET_TEXT)
        assertThat(args!!.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString())
            .isEqualTo("ok, 6 pm")
    }

    @Test
    fun `setSelection sends ACTION_SET_SELECTION with start and end`() {
        val field = source.fields(7, viewId = null)!!.single()

        field.setSelection(8, 8)

        val (action, args) = performed.single()
        assertThat(action).isEqualTo(AccessibilityNodeInfo.ACTION_SET_SELECTION)
        assertThat(args!!.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT)).isEqualTo(8)
        assertThat(args!!.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT)).isEqualTo(8)
    }

    @Test
    fun `the inserter finds the field again and inserts through the live nodes`() =
        runTest {
            val inserter = AccessibilityReplyInserter(source = { source }, dispatcher = Dispatchers.Unconfined)
            inserter.rememberTarget(InputFieldRef(7, WHATSAPP, "com.whatsapp:id/entry", Bounds(30, 1300, 900, 1420)))

            assertThat(inserter.insert("done")).isEqualTo(InsertResult.Inserted)
            assertThat(performed.map { it.first })
                .containsExactly(AccessibilityNodeInfo.ACTION_SET_TEXT, AccessibilityNodeInfo.ACTION_SET_SELECTION)
                .inOrder()
        }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
    }
}
