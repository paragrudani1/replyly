package com.paragrudani.replyly.onboarding

/** The welcome flow's pages, in order. The last one is the setup checklist. */
enum class WelcomePage {
    /** The floating button, 3 replies in your style, you edit and send. */
    WhatItDoes,

    /** The privacy promise (spec sections 4, 7 and 8). */
    Privacy,

    /** The five S23 setup steps with live checks. */
    Setup,
}

/** How the user reached onboarding. */
enum class OnboardingEntry {
    /** The app opened on onboarding because a step is not done. */
    FirstRun,

    /** Settings → "Setup steps": the user has seen the intro and wants the checklist. */
    FromSettings,
    ;

    companion object {
        /** Opened from Settings ("Setup steps") is never a first run, even if the app started on onboarding. */
        fun of(fromSettings: Boolean): OnboardingEntry = if (fromSettings) FromSettings else FirstRun
    }
}

/** Page rules for the welcome flow, kept out of Compose so they are plain JVM tests. */
object WelcomeFlow {
    /** Where "Skip" goes: straight to the checklist (onboarding is not done until the steps are). */
    val SKIP_TARGET = WelcomePage.Setup

    fun startPage(entry: OnboardingEntry): WelcomePage =
        when (entry) {
            OnboardingEntry.FirstRun -> WelcomePage.WhatItDoes
            OnboardingEntry.FromSettings -> WelcomePage.Setup
        }

    fun next(page: WelcomePage): WelcomePage? = WelcomePage.entries.getOrNull(page.ordinal + 1)

    fun previous(page: WelcomePage): WelcomePage? = WelcomePage.entries.getOrNull(page.ordinal - 1)

    fun canSkip(page: WelcomePage): Boolean = page != SKIP_TARGET

    /**
     * The page the system Back gesture goes to, or null to leave the screen. Back retraces only
     * pages after [start]: from Settings the checklist is the start, so Back returns to Settings
     * even after a swipe to the intro pages.
     */
    fun systemBackTarget(
        current: WelcomePage,
        start: WelcomePage,
    ): WelcomePage? = if (current.ordinal > start.ordinal) previous(current) else null
}
