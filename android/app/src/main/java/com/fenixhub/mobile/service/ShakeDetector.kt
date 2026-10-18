package com.fenixhub.mobile.service

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Fires [onShake] after [requiredPeaks] hard jolts inside [windowMillis].
 *
 * A real shake reverses direction every ~100 ms, so the acceleration drops back
 * below the threshold between jolts; counting peaks works where requiring the
 * acceleration to stay high for a sustained time never would.
 */
class ShakeDetector(
    private val threshold: Float = 13f,
    private val requiredPeaks: Int = 3,
    private val windowMillis: Long = 1_000L,
    private val minPeakGapMillis: Long = 90L,
    private val debounceMillis: Long = 1_500L,
    private val onShake: () -> Unit,
) : SensorEventListener {
    private val peakTimes = LongArray(requiredPeaks)
    private var peakCount = 0
    private var lastPeakAt = 0L
    private var lastTriggerAt = 0L

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val linearAcceleration = abs(magnitude - SensorManager.GRAVITY_EARTH)
        if (linearAcceleration <= threshold) return

        val now = SystemClock.elapsedRealtime()
        // One physical jolt spans several samples; count it once.
        if (now - lastPeakAt < minPeakGapMillis) return
        lastPeakAt = now

        // Keep only the most recent peaks that are still inside the window.
        if (peakCount == requiredPeaks) {
            System.arraycopy(peakTimes, 1, peakTimes, 0, requiredPeaks - 1)
            peakCount--
        }
        peakTimes[peakCount++] = now

        if (peakCount == requiredPeaks
            && now - peakTimes[0] <= windowMillis
            && now - lastTriggerAt >= debounceMillis
        ) {
            lastTriggerAt = now
            peakCount = 0
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
