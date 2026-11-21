package com.paragrudani.replyly.history

import com.paragrudani.replyly.settings.SettingsRepository
import com.paragrudani.replyly.settings.current
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Retention for history (spec 5; ARCHITECTURE "Purges"): deletes messages first seen more than
 * `retentionDays` ago. Passive capture calls [purgeNow] when the service connects and
 * [purgeIfDue] after each capture, so it runs at most once a day while the service lives. No
 * WorkManager: if the service does not run, nothing new is stored either.
 */
class HistoryRetention(
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val interval: Duration = Duration.ofDays(1),
) {
    private val lock = Mutex()
    private var lastPurge: Instant? = null

    /** @return number of messages deleted. */
    suspend fun purgeNow(): Int = lock.withLock { purge() }

    /** Purges if the last purge was [interval] or longer ago (or never). @return number deleted. */
    suspend fun purgeIfDue(): Int =
        lock.withLock {
            val last = lastPurge
            if (last != null && Duration.between(last, clock.instant()) < interval) 0 else purge()
        }

    private suspend fun purge(): Int {
        val now = clock.instant()
        val days =
            settings
                .current()
                .retentionDays
                .coerceAtLeast(1)
                .toLong()
        val deleted = history.purgeOlderThan(now - Duration.ofDays(days))
        lastPurge = now
        return deleted
    }
}
