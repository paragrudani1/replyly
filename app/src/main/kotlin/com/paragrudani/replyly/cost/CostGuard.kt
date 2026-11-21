package com.paragrudani.replyly.cost

import com.paragrudani.replyly.llm.TokenUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class CostStatus(
    val requestsToday: Int,
    val dailyRequestLimit: Int,
    val promptTokensThisMonth: Long,
    val completionTokensThisMonth: Long,
) {
    val limitReached: Boolean get() = requestsToday >= dailyRequestLimit
    val totalTokensThisMonth: Long get() = promptTokensThisMonth + completionTokensThisMonth
}

/**
 * Daily request limit and monthly token counter (ticket #6, persisted, day and month in local
 * time). The limit comes from settings. Called by the generator (#4), shown in settings (#6).
 */
interface CostGuard {
    val status: StateFlow<CostStatus>

    /** Counts one request and returns true, or returns false if today's limit is reached. */
    suspend fun tryAcquire(): Boolean

    suspend fun recordUsage(usage: TokenUsage)
}

/** Stub until ticket #6. Not persisted, never resets. */
class InMemoryCostGuard(
    dailyRequestLimit: Int = 200,
) : CostGuard {
    private val state = MutableStateFlow(CostStatus(0, dailyRequestLimit, 0, 0))
    override val status: StateFlow<CostStatus> = state

    override suspend fun tryAcquire(): Boolean {
        var acquired = false
        state.update {
            acquired = !it.limitReached
            if (acquired) it.copy(requestsToday = it.requestsToday + 1) else it
        }
        return acquired
    }

    override suspend fun recordUsage(usage: TokenUsage) {
        state.update {
            it.copy(
                promptTokensThisMonth = it.promptTokensThisMonth + usage.promptTokens,
                completionTokensThisMonth = it.completionTokensThisMonth + usage.completionTokens,
            )
        }
    }
}
