package com.paragrudani.replyly.ui

import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.ui.onboarding.OnboardingRoute
import com.paragrudani.replyly.ui.settings.SettingsRoute
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReplylyNavHostTest {
    private val nav =
        TestNavHostController(ApplicationProvider.getApplicationContext()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
        }

    private fun start(route: Any) {
        nav.graph = nav.createGraph(startDestination = route) { replylyGraph(nav) }
    }

    private fun stack(): List<String?> =
        nav.currentBackStack.value
            .map { it.destination.route }
            .filter { it != null && it != nav.graph.route }

    private val settings = SettingsRoute::class.qualifiedName
    private val onboarding = OnboardingRoute::class.qualifiedName

    @Test
    fun `settings, setup steps, skip leaves exactly one settings`() {
        start(SettingsRoute)
        nav.navigate(OnboardingRoute)

        nav.finishOnboarding()

        assertThat(stack()).containsExactly(settings)
    }

    @Test
    fun `finishing onboarding at first launch replaces it with settings`() {
        start(OnboardingRoute)

        nav.finishOnboarding()

        assertThat(stack()).containsExactly(settings)
    }

    @Test
    fun `step 5 opens settings on top, so back returns to onboarding`() {
        start(OnboardingRoute)

        nav.navigate(SettingsRoute)

        assertThat(stack()).containsExactly(onboarding, settings).inOrder()
        nav.popBackStack()
        assertThat(nav.currentDestination?.route).isEqualTo(onboarding)
    }

    @Test
    fun `open settings from the panel closes screens above settings`() {
        start(SettingsRoute)
        nav.navigate(OnboardingRoute)

        nav.showSettings()

        assertThat(stack()).containsExactly(settings)
    }

    @Test
    fun `open settings before onboarding is finished puts settings on top`() {
        start(OnboardingRoute)

        nav.showSettings()

        assertThat(stack()).containsExactly(onboarding, settings).inOrder()
    }
}
