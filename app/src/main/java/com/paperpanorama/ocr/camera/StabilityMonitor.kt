package com.paperpanorama.ocr.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Soft stability gate from accelerometer variance.
 */
class StabilityMonitor(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    var isShaky: Boolean = false
        private set

    private val window = FloatArray(12)
    private var idx = 0
    private var filled = 0

    fun start() {
        accel?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val mag = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2],
        )
        window[idx] = mag
        idx = (idx + 1) % window.size
        if (filled < window.size) filled++
        if (filled < window.size) return
        val mean = window.average().toFloat()
        var varSum = 0f
        for (v in window) {
            val d = v - mean
            varSum += d * d
        }
        val variance = varSum / window.size
        isShaky = variance > 0.12f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
