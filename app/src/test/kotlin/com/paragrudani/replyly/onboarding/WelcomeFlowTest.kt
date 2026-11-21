package com.paragrudani.replyly.onboarding

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WelcomeFlowTest {
    @Test
    fun `first run starts on what Replyly does`() {
        assertThat(WelcomeFlow.startPage(OnboardingEntry.FirstRun)).isEqualTo(WelcomePage.WhatItDoes)
    }

    @Test
    fun `setup steps from settings starts on the checklist`() {
        assertThat(WelcomeFlow.startPage(OnboardingEntry.FromSettings)).isEqualTo(WelcomePage.Setup)
    }

    @Test
    fun `pages run what it does, privacy, setup`() {
        assertThat(WelcomePage.entries)
            .containsExactly(WelcomePage.WhatItDoes, WelcomePage.Privacy, WelcomePage.Setup)
            .inOrder()
    }

    @Test
    fun `next walks forward and stops at the checklist`() {
        assertThat(WelcomeFlow.next(WelcomePage.WhatItDoes)).isEqualTo(WelcomePage.Privacy)
        assertThat(WelcomeFlow.next(WelcomePage.Privacy)).isEqualTo(WelcomePage.Setup)
        assertThat(WelcomeFlow.next(WelcomePage.Setup)).isNull()
    }

    @Test
    fun `previous walks back and stops at the first page`() {
        assertThat(WelcomeFlow.previous(WelcomePage.Setup)).isEqualTo(WelcomePage.Privacy)
        assertThat(WelcomeFlow.previous(WelcomePage.Privacy)).isEqualTo(WelcomePage.WhatItDoes)
        assertThat(WelcomeFlow.previous(WelcomePage.WhatItDoes)).isNull()
    }

    @Test
    fun `skip goes to the checklist, which has no skip of its own`() {
        assertThat(WelcomeFlow.canSkip(WelcomePage.WhatItDoes)).isTrue()
        assertThat(WelcomeFlow.canSkip(WelcomePage.Privacy)).isTrue()
        assertThat(WelcomeFlow.canSkip(WelcomePage.Setup)).isFalse()
        assertThat(WelcomeFlow.SKIP_TARGET).isEqualTo(WelcomePage.Setup)
    }

    @Test
    fun `system back steps through pages after the start page, then leaves`() {
        // First run: Back from the checklist returns to privacy, from the first page it leaves.
        assertThat(WelcomeFlow.systemBackTarget(WelcomePage.Setup, start = WelcomePage.WhatItDoes))
            .isEqualTo(WelcomePage.Privacy)
        assertThat(WelcomeFlow.systemBackTarget(WelcomePage.WhatItDoes, start = WelcomePage.WhatItDoes)).isNull()
    }

    @Test
    fun `system back from the checklist opened from settings returns to settings`() {
        assertThat(WelcomeFlow.systemBackTarget(WelcomePage.Setup, start = WelcomePage.Setup)).isNull()
        // Swiped back to privacy, then Back: the pages before the start are not a back stack.
        assertThat(WelcomeFlow.systemBackTarget(WelcomePage.Privacy, start = WelcomePage.Setup)).isNull()
    }

    @Test
    fun `onboarding opened from settings is not a first run`() {
        assertThat(OnboardingEntry.of(fromSettings = false)).isEqualTo(OnboardingEntry.FirstRun)
        assertThat(OnboardingEntry.of(fromSettings = true)).isEqualTo(OnboardingEntry.FromSettings)
    }
}
