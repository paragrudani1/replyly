package com.paragrudani.replyly.policy

import android.util.Log
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The spec 4 rules (ticket #1). [settings] returns the latest settings, or `null` while they are
 * still loading; then every app counts as blocklisted, so a blocklisted chat is never read in
 * the first moments after the service starts.
 *
 * Order: secure window, blocklisted app, banking app, password field, blocklisted chat. Each check
 * runs only when its input is known, so the package-only early check stays cheap.
 */
class DefaultSensitiveScreenPolicy(
    private val settings: () -> ReplylySettings?,
    private val sensitivePackages: Set<String> = SensitiveApps.PACKAGES,
) : SensitiveScreenPolicy {
    override fun evaluate(context: ScreenContext): SensitivityVerdict {
        val current = settings()
        val reason =
            when {
                context.secureWindow || context.snapshot?.secureWindow == true -> BlockReason.SECURE_WINDOW
                current == null || context.packageName in current.blockedPackages -> BlockReason.BLOCKLISTED_APP
                context.packageName in sensitivePackages -> BlockReason.BANKING_APP
                context.snapshot?.allNodes()?.any { it.password } == true -> BlockReason.PASSWORD_FIELD
                context.chatKey?.let { it.encoded in current.blockedChatKeys } == true -> BlockReason.BLOCKLISTED_CHAT
                else -> null
            }
        return if (reason == null) SensitivityVerdict.Allowed else SensitivityVerdict.Blocked(reason)
    }

    companion object {
        private const val TAG = "ReplylyPolicy"

        /** Keeps the latest settings in memory, so [evaluate] never waits for disk. */
        fun create(
            repository: SettingsRepository,
            scope: CoroutineScope,
        ): DefaultSensitiveScreenPolicy {
            val latest: StateFlow<ReplylySettings?> =
                repository.settings
                    .map<ReplylySettings, ReplylySettings?> { it }
                    .catch { e -> Log.e(TAG, "settings unavailable: ${e::class.simpleName}") }
                    .stateIn(scope, SharingStarted.Eagerly, null)
            return DefaultSensitiveScreenPolicy(settings = { latest.value })
        }
    }
}
