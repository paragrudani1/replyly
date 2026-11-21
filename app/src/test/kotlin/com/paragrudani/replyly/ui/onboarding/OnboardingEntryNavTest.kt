package com.paragrudani.replyly.ui.onboarding

import android.os.Bundle
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.onboarding.OnboardingEntry
import com.paragrudani.replyly.ui.finishOnboarding
import com.paragrudani.replyly.ui.replylyGraph
import com.paragrudani.replyly.ui.settings.SettingsRoute
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which page onboarding opens on, and where Back goes, comes from the route itself
 * ([OnboardingRoute.fromSettings]), never from the graph's start destination: that ID does not
 * change when onboarding is popped, so "Skip for now" then "Setup steps" used to look like a first run.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingEntryNavTest {
    private fun newNav() =
        TestNavHostController(ApplicationProvider.getApplicationContext()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
        }

    private val nav = newNav()

    private fun start(route: Any) {
        nav.graph = nav.createGraph(startDestination = route) { replylyGraph(nav) }
    }

    private fun currentEntry() = nav.currentBackStackEntry!!.onboardingEntry()

    /** The same call Settings makes for "Setup steps" (ReplylyNavHost). */
    private fun openSetupSteps() = nav.navigate(OnboardingRoute(fromSettings = true))

    @Test
    fun `the app opening on onboarding is a first run`() {
        start(OnboardingRoute())

        assertThat(currentEntry()).isEqualTo(OnboardingEntry.FirstRun)
    }

    @Test
    fun `setup steps from settings opens from settings`() {
        start(SettingsRoute)
        openSetupSteps()

        assertThat(currentEntry()).isEqualTo(OnboardingEntry.FromSettings)
    }

    @Test
    fun `first run, skip for now, then setup steps opens from settings and back returns to settings`() {
        start(OnboardingRoute())
        nav.finishOnboarding()
        openSetupSteps()

        assertThat(currentEntry()).isEqualTo(OnboardingEntry.FromSettings)
        nav.popBackStack()
        assertThat(nav.currentBackStackEntry!!.destination.route).isEqualTo(SettingsRoute::class.qualifiedName)
    }

    @Test
    fun `first run, step 5 opens settings, then setup steps opens from settings`() {
        start(OnboardingRoute())
        nav.navigate(SettingsRoute)
        openSetupSteps()

        assertThat(currentEntry()).isEqualTo(OnboardingEntry.FromSettings)
        nav.popBackStack()
        assertThat(nav.currentBackStackEntry!!.destination.route).isEqualTo(SettingsRoute::class.qualifiedName)
    }

    @Test
    fun `the route argument survives saved state (rotation, process death)`() {
        start(SettingsRoute)
        openSetupSteps()
        val saved: Bundle = nav.saveState()!!

        val restored = newNav()
        restored.restoreState(saved)
        restored.graph = restored.createGraph(startDestination = SettingsRoute) { replylyGraph(restored) }

        assertThat(restored.currentBackStackEntry!!.onboardingEntry()).isEqualTo(OnboardingEntry.FromSettings)
    }
}
