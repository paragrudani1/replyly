package com.paragrudani.replyly.snooze

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.testing.TestDataStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import java.time.ZoneId

/** The accelerometer listener on Robolectric's sensor manager, driven by [SnoozeKeeper] (#56). */
@RunWith(RobolectricTestRunner::class)
class ShakeListenerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
    private val stores by lazy { TestDataStores(tmp.root) }
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { SnoozeStore(stores.open("snooze"), storeScope) }
    private var backs = 0
    private lateinit var listener: ShakeListener

    @Before
    fun addSensor() = shadowOf(sensorManager).addSensor(accelerometer)

    @After
    fun tearDown() {
        runBlocking { storeScope.coroutineContext[Job]?.cancelAndJoin() }
        stores.closeAll()
    }

    private suspend fun TestScope.keeper(screenOn: Boolean = true): SnoozeKeeper {
        withContext(Dispatchers.Default) { store.sync() }
        lateinit var keeper: SnoozeKeeper
        listener = ShakeListener(sensorManager, onShake = { keeper.onShake() })
        keeper =
            SnoozeKeeper(store, { testScheduler.currentTime }, { ZoneId.of("UTC") }, backgroundScope, listener, {}, {
                backs++
            }, {})
        keeper.start(screenOn)
        runCurrent()
        return keeper
    }

    private val listening get() = shadowOf(sensorManager).hasListener(listener)

    private fun push(
        timeMs: Long,
        g: Float,
    ) = shadowOf(sensorManager).sendSensorEventToListeners(
        SensorEventBuilder
            .newBuilder()
            .setSensor(accelerometer)
            .setValues(floatArrayOf(0f, 0f, g * ShakeDetector.GRAVITY))
            .setTimestamp(timeMs * 1_000_000)
            .build(),
    )

    @Test
    fun `not registered while not snoozed`() =
        runTest {
            keeper()
            assertThat(listening).isFalse()
        }

    @Test
    fun `registered while snoozed with the screen on, off when the snooze ends`() =
        runTest {
            val keeper = keeper()
            keeper.snooze(SnoozeChoice.ONE_HOUR)
            runCurrent()
            assertThat(listening).isTrue()

            store.end()
            runCurrent()
            assertThat(listening).isFalse()
        }

    @Test
    fun `screen off unregisters, screen on registers again`() =
        runTest {
            val keeper = keeper()
            keeper.snooze(SnoozeChoice.UNTIL_TURNED_ON)
            runCurrent()

            keeper.setScreenOn(false)
            assertThat(listening).isFalse()
            keeper.setScreenOn(true)
            assertThat(listening).isTrue()
        }

    @Test
    fun `not registered while the screen is off at connect`() =
        runTest {
            store.snooze(Long.MAX_VALUE)
            keeper(screenOn = false)
            assertThat(listening).isFalse()
        }

    @Test
    fun `service stop unregisters`() =
        runTest {
            val keeper = keeper()
            keeper.snooze(SnoozeChoice.ONE_HOUR)
            runCurrent()
            keeper.stop()
            assertThat(listening).isFalse()
        }

    @Test
    fun `a double shake ends the snooze and unregisters`() =
        runTest {
            val keeper = keeper()
            keeper.snooze(SnoozeChoice.ONE_HOUR)
            runCurrent()

            push(1_000, 1f)
            push(1_100, 3.2f)
            push(1_300, 1f)
            push(1_700, 3.2f)
            runCurrent()

            assertThat(store.until.value).isNull()
            assertThat(backs).isEqualTo(1)
            assertThat(listening).isFalse()
        }

    @Test
    fun `one shake does nothing`() =
        runTest {
            val keeper = keeper()
            keeper.snooze(SnoozeChoice.ONE_HOUR)
            runCurrent()

            push(1_000, 3.2f)
            push(2_500, 3.2f)
            runCurrent()

            assertThat(store.until.value).isNotNull()
            assertThat(backs).isEqualTo(0)
        }
}
