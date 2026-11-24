package com.paragrudani.replyly.snooze

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.min

/** Turns shake listening on or off (`ShakeListener`; a fake in tests). */
fun interface ShakeSwitch {
    fun setActive(active: Boolean)
}

/**
 * The snooze while the accessibility service runs (#56). Every store change (a new snooze, a
 * shake, "End snooze now" in Settings, the timer) calls [refresh] at once, so the button hides or
 * comes back without waiting for an accessibility event. A timer runs to the snoozed-until time
 * (none for [Long.MAX_VALUE]) and then ends the snooze. Shake listening ([shake]) is on only while
 * snoozed, the screen is on and the phone is unlocked ([locked] false; like Wispr Flow).
 *
 * The timer waits on the main dispatcher, which counts uptime: deep sleep pauses it. So it waits
 * in steps of at most [MAX_WAIT_MS] and re-reads the wall clock ([now]) after each one; the end
 * is never later than that after the screen comes back on, and a manual clock change applies too.
 *
 * [zone] is read at each choice, so "Until tomorrow (6 AM)" follows a time zone change.
 * [log] gets event names and enum values only: never times, packages or sensor values.
 * Main thread only ([scope] is the overlay's main-thread scope).
 */
class SnoozeKeeper(
    private val store: SnoozeStore,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
    private val scope: CoroutineScope,
    private val shake: ShakeSwitch,
    private val refresh: () -> Unit,
    /** After a shake ended the snooze: the haptic and "Replyly is back". */
    private val onShakeEnded: () -> Unit,
    private val log: (String) -> Unit,
    private val is24Hour: () -> Boolean = { false },
    /** The keyguard is showing (`KeyguardManager.isKeyguardLocked`); read on every change. */
    private val locked: () -> Boolean = { false },
) {
    private var screenOn = false
    private var job: Job? = null

    private val snoozed: Boolean get() = store.isSnoozed(now())

    fun start(screenOn: Boolean) {
        stop()
        this.screenOn = screenOn
        job =
            scope.launch {
                store.until.collectLatest { until ->
                    updateShake()
                    refresh()
                    if (until == null || until == Long.MAX_VALUE) return@collectLatest
                    while (true) {
                        val left = until - now()
                        if (left <= 0) break
                        delay(min(left, MAX_WAIT_MS))
                    }
                    end(SnoozeEndReason.TIMER)
                }
            }
    }

    /**
     * Screen on/off (the overlay's receivers). On "on", a snooze whose end passed while the phone
     * slept ends now (the timer's next step could be up to [MAX_WAIT_MS] away).
     */
    fun setScreenOn(on: Boolean) {
        screenOn = on
        val until = store.until.value
        if (on && until != null && !snoozed) {
            end(SnoozeEndReason.TIMER)
        } else {
            updateShake()
        }
    }

    /** The keyguard went away (`ACTION_USER_PRESENT`): shake listening may start. */
    fun onUnlocked() = updateShake()

    /** The user chose [choice] on the snooze card: stores the end and returns the toast text. */
    fun snooze(choice: SnoozeChoice): String {
        val zone = zone()
        val nowInstant = Instant.ofEpochMilli(now())
        val until = choice.untilMillis(nowInstant, zone)
        log("snooze start choice=${choice.name}")
        store.snooze(until)
        return SnoozeText.toast(choice, until, zone, is24Hour())
    }

    /** A double shake (from the listener): ends a running snooze, then the haptic and toast. */
    fun onShake() {
        if (!snoozed || !screenOn || locked()) return
        end(SnoozeEndReason.SHAKE)
        onShakeEnded()
    }

    /** Service stop: no timer, no sensor. */
    fun stop() {
        job?.cancel()
        job = null
        shake.setActive(false)
    }

    private fun end(reason: SnoozeEndReason) {
        if (store.until.value == null) return
        log("snooze end reason=${reason.name}")
        // Off at once, before the store's change reaches the collector.
        shake.setActive(false)
        store.end()
    }

    private fun updateShake() = shake.setActive(snoozed && screenOn && !locked())

    companion object {
        /** The longest single wait of the timer, so sleep or a clock change delays the end at most this. */
        const val MAX_WAIT_MS = 60_000L
    }
}

/** Why a snooze ended (log values). */
enum class SnoozeEndReason { TIMER, SHAKE, APP }
