package com.paragrudani.replyly.security

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EncryptedDatabasesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun create(name: String) {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use(SQLiteDatabase::close)
    }

    @Test
    fun `a wipe deletes Replyly's own databases and leaves other databases alone`() {
        create("history.db")
        create("feedback.db")
        create("androidx.work.workdb")

        val wiped = EncryptedDatabases.wipeAll(context)

        assertThat(wiped).isEqualTo(2)
        assertThat(context.databaseList().toList()).contains("androidx.work.workdb")
        assertThat(context.databaseList().toList()).containsNoneOf("history.db", "feedback.db")
    }

    @Test
    fun `a wipe with no databases deletes nothing`() {
        assertThat(EncryptedDatabases.wipeAll(context)).isEqualTo(0)
    }
}
