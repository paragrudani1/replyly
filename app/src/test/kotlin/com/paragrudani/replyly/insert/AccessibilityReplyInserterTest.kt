package com.paragrudani.replyly.insert

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.screen.Bounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AccessibilityReplyInserterTest {
    private val ref = InputFieldRef(7, "com.whatsapp", "com.whatsapp:id/entry", Bounds(30, 1300, 900, 1420))
    private val field = FakeField()
    private var source: InputFieldSource? = FakeFieldSource(mutableMapOf(7 to listOf(field)))

    private fun inserter() = AccessibilityReplyInserter(source = { source }, dispatcher = Dispatchers.Unconfined)

    @Test
    fun `replaces the field's text and puts the cursor at the end`() =
        runTest {
            val inserter = inserter()
            inserter.rememberTarget(ref)

            val result = inserter.insert("Haan, 6 baje call karta hoon")

            assertThat(result).isEqualTo(InsertResult.Inserted)
            assertThat(field.text).isEqualTo("Haan, 6 baje call karta hoon")
            assertThat(field.selection).isEqualTo(28..28)
            assertThat(field.calls).containsExactly("setText", "setSelection").inOrder()
        }

    @Test
    fun `field gone - NoFocusedField, nothing touched`() =
        runTest {
            source = FakeFieldSource(mutableMapOf(7 to emptyList()))
            val inserter = inserter()
            inserter.rememberTarget(ref)

            assertThat(inserter.insert("hi")).isEqualTo(InsertResult.NoFocusedField)
        }

    @Test
    fun `no remembered target - NoFocusedField`() =
        runTest {
            val inserter = inserter()

            assertThat(inserter.insert("hi")).isEqualTo(InsertResult.NoFocusedField)
            assertThat(field.calls).isEmpty()
        }

    @Test
    fun `app refuses the text - Failed with the refused reason, no selection attempt`() =
        runTest {
            field.acceptsText = false
            val inserter = inserter()
            inserter.rememberTarget(ref)

            assertThat(inserter.insert("hi")).isEqualTo(InsertResult.Failed(InsertFailures.REFUSED))
            assertThat(field.calls).containsExactly("setText")
        }

    @Test
    fun `cursor move refused - still inserted, the text is in`() =
        runTest {
            field.acceptsSelection = false
            val inserter = inserter()
            inserter.rememberTarget(ref)

            assertThat(inserter.insert("ok")).isEqualTo(InsertResult.Inserted)
            assertThat(field.text).isEqualTo("ok")
        }

    @Test
    fun `accessibility service off - Failed with the service-off reason`() =
        runTest {
            source = null
            val inserter = inserter()
            inserter.rememberTarget(ref)

            assertThat(inserter.insert("hi")).isEqualTo(InsertResult.Failed(InsertFailures.SERVICE_OFF))
        }
}
