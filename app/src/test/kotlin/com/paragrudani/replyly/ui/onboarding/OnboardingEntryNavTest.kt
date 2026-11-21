package com.paragrudani.replyly.ui.onboarding

import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.ui.replylyGraph
import com.paragrudani.replyly.ui.settings.SettingsRoute
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which page onboarding opens on depends on how it was reached in the real app graph. */
@RunWith(RobolectricTestRunner::class)
class OnboardingEntryNavTest {
    private val nav =
        TestNavHostController(ApplicationProvider.getApplicationContext()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
        }

    private fun start(route: Any) {
        nav.graph = nav.createGraph(startDestination = route) { replylyGraph(nav) }
    }

    @Test
    fun `the app opening on onboarding is its start screen`() {
        start(OnboardingRoute)

        assertThat(nav.currentBackStackEntry!!.isStartDestination()).isTrue()
    }

    @Test
    fun `setup steps from settings is not the start screen`() {
        start(SettingsRoute)
        nav.navigate(OnboardingRoute)

        assertThat(nav.currentBackStackEntry!!.isStartDestination()).isFalse()
    }
}
