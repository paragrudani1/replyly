package com.paragrudani.replyly.ui.settings

import android.content.Context
import androidx.core.content.edit

/** Where the last app version that the user saw is kept. */
interface LastSeenVersionStore {
    fun get(): Long?

    fun set(versionCode: Long)
}

/** Plain SharedPreferences: one number, nothing secret, read on the main thread once per screen. */
class PrefsLastSeenVersionStore(
    context: Context,
) : LastSeenVersionStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(): Long? = if (prefs.contains(KEY)) prefs.getLong(KEY, 0) else null

    override fun set(versionCode: Long) = prefs.edit { putLong(KEY, versionCode) }

    private companion object {
        const val PREFS = "whats_new"
        const val KEY = "last_seen_version_code"
    }
}

/**
 * Decides whether the "What's new" sheet shows: once per update, never on a fresh install, never
 * when there are no notes. [consume] records the current version, so the next call says no.
 * The notes live in `res/values/whats_new.xml`.
 */
class WhatsNew(
    private val store: LastSeenVersionStore,
    private val currentVersion: Long,
    /** True when the app was installed, not updated (`firstInstallTime == lastUpdateTime`). */
    private val freshInstall: Boolean,
    private val hasNotes: Boolean,
) {
    enum class Decision { Show, RecordSilently, Nothing }

    /** @return true if the sheet should show now. Either way, this version then counts as seen. */
    fun consume(): Boolean {
        val decision = decide(currentVersion, store.get(), freshInstall, hasNotes)
        if (decision != Decision.Nothing) store.set(currentVersion)
        return decision == Decision.Show
    }

    companion object {
        fun decide(
            currentVersion: Long,
            lastSeen: Long?,
            freshInstall: Boolean,
            hasNotes: Boolean,
        ): Decision =
            when {
                lastSeen == currentVersion -> Decision.Nothing

                // Nothing stored: a new install (onboarding explains everything), or an update
                // from a build before this sheet existed.
                lastSeen == null && freshInstall -> Decision.RecordSilently

                lastSeen != null && lastSeen > currentVersion -> Decision.RecordSilently

                !hasNotes -> Decision.RecordSilently

                else -> Decision.Show
            }
    }
}
