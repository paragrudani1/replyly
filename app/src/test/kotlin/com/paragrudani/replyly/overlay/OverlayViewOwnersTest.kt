package com.paragrudani.replyly.overlay

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OverlayViewOwnersTest {
    class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private val view = View(ApplicationProvider.getApplicationContext())

    @Test
    fun `attach provides all three owners on the view tree and resumes`() {
        val owners = OverlayViewOwners()

        owners.attach(view)

        assertThat(view.findViewTreeLifecycleOwner()).isSameInstanceAs(owners)
        assertThat(view.findViewTreeSavedStateRegistryOwner()).isSameInstanceAs(owners)
        assertThat(view.findViewTreeViewModelStoreOwner()).isSameInstanceAs(owners)
        assertThat(owners.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
    }

    @Test
    fun `destroy ends the lifecycle and clears view models`() {
        val owners = OverlayViewOwners()
        owners.attach(view)
        val probe = ViewModelProvider(owners)[Probe::class.java]

        owners.destroy()

        assertThat(owners.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)
        assertThat(probe.cleared).isTrue()
    }
}
