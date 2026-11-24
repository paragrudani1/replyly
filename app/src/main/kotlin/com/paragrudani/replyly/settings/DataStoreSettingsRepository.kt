package com.paragrudani.replyly.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [SettingsRepository] on a Preferences DataStore. A missing key reads as the spec default.
 * Open the store with [SettingsMigrations.ALL] (AppGraph does).
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    override val settings: Flow<ReplylySettings> = dataStore.data.map { it.toSettings() }.distinctUntilChanged()

    override suspend fun update(transform: (ReplylySettings) -> ReplylySettings) {
        // edit() is one transaction at a time, so concurrent updates never overwrite each other.
        dataStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    private fun Preferences.toSettings(): ReplylySettings {
        val defaults = ReplylySettings()
        return ReplylySettings(
            endpoint = this[ENDPOINT] ?: defaults.endpoint,
            model = this[MODEL] ?: defaults.model,
            historyMessageCount = this[HISTORY_COUNT] ?: defaults.historyMessageCount,
            aboutMe = this[ABOUT_ME] ?: defaults.aboutMe,
            blockedChatKeys = this[BLOCKED_CHATS] ?: defaults.blockedChatKeys,
            blockedPackages = this[BLOCKED_PACKAGES] ?: defaults.blockedPackages,
            dailyRequestLimit = this[DAILY_LIMIT] ?: defaults.dailyRequestLimit,
            retentionDays = this[RETENTION_DAYS] ?: defaults.retentionDays,
            myNames = this[MY_NAMES] ?: defaults.myNames,
            myXHandle = this[MY_X_HANDLE] ?: defaults.myXHandle,
            myXVoice = this[MY_X_VOICE] ?: defaults.myXVoice,
            xPostLength = this[X_POST_LENGTH] ?: defaults.xPostLength,
            useDynamicColor = this[DYNAMIC_COLOR] ?: defaults.useDynamicColor,
            showBubble = this[SHOW_BUBBLE] ?: defaults.showBubble,
            bubbleSize = this[BUBBLE_SIZE]?.let(ReplylySettings::bubbleSizeStep) ?: defaults.bubbleSize,
            bubbleOpacity = this[BUBBLE_OPACITY]?.let(ReplylySettings::bubbleOpacityStep) ?: defaults.bubbleOpacity,
            shrinkWhenIdle = this[SHRINK_WHEN_IDLE] ?: defaults.shrinkWhenIdle,
        )
    }

    private fun MutablePreferences.write(settings: ReplylySettings) {
        this[ENDPOINT] = settings.endpoint
        this[MODEL] = settings.model
        this[HISTORY_COUNT] = settings.historyMessageCount
        this[ABOUT_ME] = settings.aboutMe
        this[BLOCKED_CHATS] = settings.blockedChatKeys
        this[BLOCKED_PACKAGES] = settings.blockedPackages
        this[DAILY_LIMIT] = settings.dailyRequestLimit
        this[RETENTION_DAYS] = settings.retentionDays
        this[MY_NAMES] = settings.myNames
        this[MY_X_HANDLE] = settings.myXHandle
        this[MY_X_VOICE] = settings.myXVoice
        this[X_POST_LENGTH] = settings.xPostLength
        this[DYNAMIC_COLOR] = settings.useDynamicColor
        this[SHOW_BUBBLE] = settings.showBubble
        this[BUBBLE_SIZE] = ReplylySettings.bubbleSizeStep(settings.bubbleSize)
        this[BUBBLE_OPACITY] = ReplylySettings.bubbleOpacityStep(settings.bubbleOpacity)
        this[SHRINK_WHEN_IDLE] = settings.shrinkWhenIdle
    }

    private companion object {
        val ENDPOINT = stringPreferencesKey("endpoint")
        val MODEL = stringPreferencesKey("model")
        val HISTORY_COUNT = intPreferencesKey("history_message_count")
        val ABOUT_ME = stringPreferencesKey("about_me")
        val BLOCKED_CHATS = stringSetPreferencesKey("blocked_chat_keys")
        val BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_packages")
        val DAILY_LIMIT = intPreferencesKey("daily_request_limit")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
        val MY_NAMES = stringPreferencesKey("my_names")
        val MY_X_HANDLE = stringPreferencesKey("my_x_handle")
        val MY_X_VOICE = stringPreferencesKey("my_x_voice")
        val X_POST_LENGTH = intPreferencesKey("x_post_length")
        val DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val SHOW_BUBBLE = booleanPreferencesKey("show_bubble")
        val BUBBLE_SIZE = floatPreferencesKey("bubble_size")
        val BUBBLE_OPACITY = intPreferencesKey("bubble_opacity")
        val SHRINK_WHEN_IDLE = booleanPreferencesKey("shrink_when_idle")
    }
}
