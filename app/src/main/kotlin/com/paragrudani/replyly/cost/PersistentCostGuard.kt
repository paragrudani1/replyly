package com.paragrudani.replyly.cost

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.paragrudani.replyly.llm.TokenUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth

/**
 * [CostGuard] persisted in its own Preferences DataStore. Days and months are in [clock]'s zone:
 * the request count resets at local midnight, the token count on the 1st.
 *
 * @param dailyRequestLimit the limit from settings; a change applies at once.
 * @param ticks re-evaluates [status] without a request, so the screen shows the new day after
 *   midnight. Defaults to one tick per local midnight.
 */
class PersistentCostGuard(
    private val dataStore: DataStore<Preferences>,
    private val dailyRequestLimit: Flow<Int>,
    private val clock: Clock,
    scope: CoroutineScope,
    ticks: Flow<Unit> = midnightTicks(clock),
) : CostGuard {
    override val status: StateFlow<CostStatus> =
        combine(dataStore.data, dailyRequestLimit, ticks.onStart { emit(Unit) }) { prefs, limit, _ ->
            prefs.counters().rolledTo(today()).toStatus(limit)
        }.stateIn(scope, SharingStarted.Eagerly, CostCounters.empty(today()).toStatus(DEFAULT_LIMIT))

    override suspend fun tryAcquire(): Boolean {
        val limit = dailyRequestLimit.first()
        var acquired = false
        // edit() runs one transaction at a time, so concurrent callers cannot overshoot.
        dataStore.edit { prefs ->
            val current = prefs.counters().rolledTo(today())
            acquired = current.requests < limit
            prefs.write(if (acquired) current.copy(requests = current.requests + 1) else current)
        }
        return acquired
    }

    override suspend fun recordUsage(usage: TokenUsage) {
        dataStore.edit { prefs ->
            val current = prefs.counters().rolledTo(today())
            prefs.write(
                current.copy(
                    promptTokens = current.promptTokens + usage.promptTokens,
                    completionTokens = current.completionTokens + usage.completionTokens,
                ),
            )
        }
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun Preferences.counters(): CostCounters {
        val day = this[DAY]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val month = this[MONTH]?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        if (day == null || month == null) return CostCounters.empty(today())
        return CostCounters(
            day = day,
            requests = this[REQUESTS] ?: 0,
            month = month,
            promptTokens = this[PROMPT_TOKENS] ?: 0,
            completionTokens = this[COMPLETION_TOKENS] ?: 0,
        )
    }

    private fun MutablePreferences.write(counters: CostCounters) {
        this[DAY] = counters.day.toString()
        this[REQUESTS] = counters.requests
        this[MONTH] = counters.month.toString()
        this[PROMPT_TOKENS] = counters.promptTokens
        this[COMPLETION_TOKENS] = counters.completionTokens
    }

    private companion object {
        const val DEFAULT_LIMIT = 200
        val DAY = stringPreferencesKey("day")
        val REQUESTS = intPreferencesKey("requests_today")
        val MONTH = stringPreferencesKey("month")
        val PROMPT_TOKENS = longPreferencesKey("prompt_tokens_month")
        val COMPLETION_TOKENS = longPreferencesKey("completion_tokens_month")
    }
}

/** Emits just after each local midnight of [clock]'s zone. */
fun midnightTicks(clock: Clock): Flow<Unit> =
    flow {
        while (true) {
            val now = clock.instant()
            val nextMidnight =
                LocalDate
                    .now(clock)
                    .plusDays(1)
                    .atStartOfDay(clock.zone)
                    .toInstant()
            delay(Duration.between(now, nextMidnight).toMillis() + 1_000)
            emit(Unit)
        }
    }
