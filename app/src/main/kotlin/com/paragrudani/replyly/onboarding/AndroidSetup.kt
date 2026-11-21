package com.paragrudani.replyly.onboarding

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import com.paragrudani.replyly.appGraph
import com.paragrudani.replyly.service.AccessibilityHostHolder
import com.paragrudani.replyly.service.ReplylyAccessibilityService

class AndroidSetupProbe(
    private val context: Context,
    private val hostHolder: AccessibilityHostHolder,
) : SetupProbe {
    private val ownService = ComponentName(context, ReplylyAccessibilityService::class.java)

    override fun isAccessibilityServiceEnabled(): Boolean {
        val enabled =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
        // Entries are "package/class" joined by ':'; the class may be in short ".Foo" form.
        return enabled
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it.trim()) }
            .any { it == ownService }
    }

    override fun isAccessibilityServiceConnected(): Boolean = hostHolder.host.value != null

    override fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
}

/** Plain SharedPreferences: tiny, synchronous, and nothing secret in it. */
class PrefsManualStepStore(
    context: Context,
) : ManualStepStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun isSleepingAppsConfirmed(): Boolean = prefs.getBoolean(KEY_SLEEPING_APPS, false)

    override fun setSleepingAppsConfirmed(confirmed: Boolean) {
        prefs.edit { putBoolean(KEY_SLEEPING_APPS, confirmed) }
    }

    private companion object {
        const val PREFS = "onboarding"
        const val KEY_SLEEPING_APPS = "sleeping_apps_confirmed"
    }
}

/** The checker wired to the real system and the app's bindings. */
fun onboardingChecker(context: Context): OnboardingChecker {
    val graph = context.appGraph
    return OnboardingChecker(
        probe = AndroidSetupProbe(context.applicationContext, graph.accessibilityHostHolder),
        manualSteps = PrefsManualStepStore(context.applicationContext),
        apiKeyStore = graph.apiKeyStore,
        profiles = graph.profileRepository,
    )
}
