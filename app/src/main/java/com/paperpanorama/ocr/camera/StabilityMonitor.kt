package com.paperpanorama.ocr.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Accel + gyro gate: [isShaky] for hold-still, [movedSinceMark] after pan.
 * Both sensors can set and clear shaky (no sticky gyro).
 */
class StabilityMonitor(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile
    var isShaky: Boolean = false
        private set

    @Volatile
    var movedSinceMark: Boolean = true
        private set

    private val window = FloatArray(12)
    private var idx = 0
    private var filled = 0
    private var lastMag = 0f
    private var haveLast = false
    private var accelShaky = false
    private var gyroShaky = false

    fun start() {
        accel?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        gyro?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun markCapturePoint() {
        movedSinceMark = false
    }

    fun allowImmediateCapture() {
        movedSinceMark = true
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val ox = event.values[0]
                val oy = event.values[1]
                val oz = event.values[2]
                val ang = sqrt(ox * ox + oy * oy + oz * oz)
                if (ang > GYRO_MOVE) movedSinceMark = true
                gyroShaky = ang > GYRO_SHAKY
                publishShaky()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val mag = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2],
                )
                if (haveLast && abs(mag - lastMag) > MOVE_DELTA) {
                    movedSinceMark = true
                }
                lastMag = mag
                haveLast = true

                window[idx] = mag
                idx = (idx + 1) % window.size
                if (filled < window.size) filled++
                if (filled >= window.size) {
                    val mean = window.average().toFloat()
                    var varSum = 0f
                    for (v in window) {
                        val d = v - mean
                        varSum += d * d
                    }
                    accelShaky = (varSum / window.size) > SHAKY_VARIANCE
                    if (accelShaky) movedSinceMark = true
                }
                publishShaky()
            }
        }
    }

    private fun publishShaky() {
        isShaky = accelShaky || gyroShaky
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val SHAKY_VARIANCE = 0.12f
        private const val MOVE_DELTA = 0.55f
        private const val GYRO_MOVE = 0.35f
        private const val GYRO_SHAKY = 1.2f
    }
}
