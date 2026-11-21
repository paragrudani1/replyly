package com.paragrudani.replyly.llm

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppLabelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an installed app is named by its label, an unknown one by its package`() {
        val app =
            ApplicationInfo().apply {
                packageName = "com.Slack.clone"
                nonLocalizedLabel = "Slack Clone"
            }
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = app.packageName
                applicationInfo = app
            },
        )
        val label = packageManagerAppLabel(context.packageManager)

        assertThat(label("com.Slack.clone")).isEqualTo("Slack Clone")
        assertThat(label("com.example.missing")).isEqualTo("com.example.missing")
    }

    @Test
    fun `the manifest makes launcher apps visible, without QUERY_ALL_PACKAGES`() {
        // Robolectric does not enforce package visibility, so check the declaration itself.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")

        assertThat(queries).contains("android.intent.action.MAIN")
        assertThat(queries).contains("android.intent.category.LAUNCHER")
        assertThat(manifest).doesNotContain("android.permission.QUERY_ALL_PACKAGES")
    }
}
