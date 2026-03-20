package com.fenixhub.mobile.service

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

class ShakeDetector(
    private val threshold: Float = 15f,
    private val sustainMillis: Long = 200L,
    private val debounceMillis: Long = 1_000L,
    private val onShake: () -> Unit,
) : SensorEventListener {
    private var aboveThresholdSince = 0L
    private var lastTriggerAt = 0L

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val linearAcceleration = abs(magnitude - SensorManager.GRAVITY_EARTH)
        val now = SystemClock.elapsedRealtime()

        if (linearAcceleration > threshold) {
            if (aboveThresholdSince == 0L) {
                aboveThresholdSince = now
            }
            if (now - aboveThresholdSince >= sustainMillis && now - lastTriggerAt >= debounceMillis) {
                lastTriggerAt = now
                aboveThresholdSince = 0L
                onShake()
            }
        } else {
            aboveThresholdSince = 0L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
