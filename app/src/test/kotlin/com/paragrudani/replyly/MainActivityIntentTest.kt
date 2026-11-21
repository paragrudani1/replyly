package com.paragrudani.replyly

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityIntentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the settings intent asks for settings and reuses a running activity`() {
        val intent = MainActivity.settingsIntent(context)

        assertThat(MainActivity.opensSettings(intent)).isTrue()
        assertThat(intent.component?.className).isEqualTo(MainActivity::class.java.name)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertThat(intent.flags and flags).isEqualTo(flags)
    }

    @Test
    fun `a launcher start does not jump to settings`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        assertThat(MainActivity.opensSettings(launcher)).isFalse()
        assertThat(MainActivity.opensSettings(null)).isFalse()
    }
}
