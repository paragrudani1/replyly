package com.paragrudani.replyly.snooze

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * The accelerometer while the bubble is snoozed and the screen is on (#56; [SnoozeKeeper] decides
 * when). Feeds [ShakeDetector] and calls [onShake] on a double shake. Local only: no network, and
 * no sensor value ever leaves this class (not logged, not stored).
 */
class ShakeListener(
    private val sensorManager: SensorManager,
    private val onShake: () -> Unit,
    private val detector: ShakeDetector = ShakeDetector(),
) : ShakeSwitch,
    SensorEventListener {
    private var registered = false

    override fun setActive(active: Boolean) {
        if (active == registered) return
        if (active) {
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            detector.reset()
            registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensorManager.unregisterListener(this)
            registered = false
            detector.reset()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered || event.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val values = event.values
        if (values.size < 3) return
        if (detector.onSample(event.timestamp / NANOS_PER_MILLI, values[0], values[1], values[2])) onShake()
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
