package com.paragrudani.replyly.onboarding

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * System screens for each step, best first. Samsung screens are unverified on One UI (see
 * docs/device-checklist/ticket-7.md), so every list ends with a screen that always exists.
 */
object SetupIntents {
    private const val TAG = "ReplylyOnboarding"
    private const val DEVICE_CARE = "com.samsung.android.lool"

    // One UI 3+ and older One UI class names for Device care → Battery. Unverified on the S23.
    private val deviceCareBatteryActivities =
        listOf(
            "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.sm.ui.battery.BatteryActivity",
        )

    fun forStep(
        step: OnboardingStepId,
        packageName: String,
    ): List<Intent> =
        when (step) {
            OnboardingStepId.RestrictedSettings -> listOf(appInfo(packageName))
            OnboardingStepId.AccessibilityService -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            OnboardingStepId.BatteryUnrestricted -> listOf(requestUnrestricted(packageName), appInfo(packageName))
            OnboardingStepId.SleepingApps ->
                deviceCareBatteryActivities.map { Intent().setComponent(ComponentName(DEVICE_CARE, it)) } +
                    Intent(Intent.ACTION_POWER_USAGE_SUMMARY) +
                    appInfo(packageName)
            // Opens the in-app Settings route, not a system screen.
            OnboardingStepId.ApiKeyAndModel -> emptyList()
        }

    /** Starts the first intent that resolves. Returns false if none did. */
    fun launchFirst(
        intents: List<Intent>,
        start: (Intent) -> Unit,
    ): Boolean {
        for (intent in intents) {
            try {
                start(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "setup screen not found, trying next: ${e::class.simpleName}")
            } catch (e: SecurityException) {
                // Device care activities may be non-exported on some builds.
                Log.i(TAG, "setup screen not allowed, trying next: ${e::class.simpleName}")
            }
        }
        return false
    }

    private fun appInfo(packageName: String) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    // The spec asks for "Unrestricted" battery; this system dialog is the direct way to grant it.
    @SuppressLint("BatteryLife")
    private fun requestUnrestricted(packageName: String) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))
}
