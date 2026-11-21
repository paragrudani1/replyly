package com.paragrudani.replyly.onboarding

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.screen.ScreenSnapshot
import com.paragrudani.replyly.service.AccessibilityHost
import com.paragrudani.replyly.service.AccessibilityHostHolder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidSetupProbeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val holder = AccessibilityHostHolder()
    private val probe = AndroidSetupProbe(context, holder)

    private fun setEnabledServices(value: String?) {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
    }

    @Test
    fun `not enabled when the setting is empty`() {
        setEnabledServices(null)

        assertThat(probe.isAccessibilityServiceEnabled()).isFalse()
    }

    @Test
    fun `enabled when listed with the full class name among others`() {
        setEnabledServices(
            "com.other/com.other.Svc:com.paragrudani.replyly/com.paragrudani.replyly.service.ReplylyAccessibilityService",
        )

        assertThat(probe.isAccessibilityServiceEnabled()).isTrue()
    }

    @Test
    fun `enabled when listed with the short class name`() {
        setEnabledServices("com.paragrudani.replyly/.service.ReplylyAccessibilityService")

        assertThat(probe.isAccessibilityServiceEnabled()).isTrue()
    }

    @Test
    fun `another app's service with the same class name does not count`() {
        setEnabledServices("com.evil/com.paragrudani.replyly.service.ReplylyAccessibilityService")

        assertThat(probe.isAccessibilityServiceEnabled()).isFalse()
    }

    @Test
    fun `connected only while a host is attached`() {
        assertThat(probe.isAccessibilityServiceConnected()).isFalse()

        val host = FakeHost()
        holder.attach(host)
        assertThat(probe.isAccessibilityServiceConnected()).isTrue()

        holder.detach(host)
        assertThat(probe.isAccessibilityServiceConnected()).isFalse()
    }

    @Test
    fun `battery follows the power manager allowlist`() {
        val power = context.getSystemService(PowerManager::class.java)
        assertThat(probe.isIgnoringBatteryOptimizations()).isFalse()

        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, true)

        assertThat(probe.isIgnoringBatteryOptimizations()).isTrue()
    }

    @Test
    fun `sleeping apps confirmation survives a new store instance`() {
        PrefsManualStepStore(context).setSleepingAppsConfirmed(true)

        assertThat(PrefsManualStepStore(context).isSleepingAppsConfirmed()).isTrue()

        PrefsManualStepStore(context).setSleepingAppsConfirmed(false)
        assertThat(PrefsManualStepStore(context).isSleepingAppsConfirmed()).isFalse()
    }

    private class FakeHost : AccessibilityHost {
        override val service: AccessibilityService get() = error("not used")

        override fun captureActiveWindow(): ScreenSnapshot? = null
    }
}
