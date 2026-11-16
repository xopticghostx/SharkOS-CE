package com.sharkhome.launcher.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.sharkhome.launcher.settings.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2
import kotlin.math.sqrt

data class Motion(
    val speedKmh: Float? = null,
    val bearing: Float? = null,
    val altitudeM: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val pitch: Float? = null,   // nose up = positive
    val roll: Float? = null,    // right side down = positive
    val heading: Float? = null, // degrees from north
    /** Total tilt of the car from level (any direction), degrees. Works without knowing which way the sensor faces. */
    val tiltDeg: Float? = null,
    /** True once the sensor's forward direction is known, so [pitch] and [roll] mean something. */
    val tiltLearned: Boolean = false,
    val hasTiltSensor: Boolean = false,
    val hasGps: Boolean = false,
)

/**
 * GPS (speed, heading, altitude) plus the head unit's accelerometer for the inclinometer.
 * The screen is mounted at an angle, so pitch/roll are zeroed with [calibrate] on level ground.
 */
class MotionRepository(private val ctx: Context, private val prefs: Prefs) : SensorEventListener, LocationListener {

    private val _motion = MutableStateFlow(Motion())
    val motion: StateFlow<Motion> = _motion

    private val sm: SensorManager? = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val lm: LocationManager? = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var sensorsOn = false
    private var gpsOn = false

    private val gravity = FloatArray(3)
    private var rawPitch = 0f
    private var rawRoll = 0f

    // Learning "forward": while driving, accelerating pushes the sensor along the car's forward axis, so the horizontal
    // acceleration seen during each GPS speed change points forward (or backward when braking, which flips the speed
    // change too). Summing speed change x acceleration over many fixes gives the forward direction.
    private var baseX = 0f
    private var baseY = 0f
    private var haveBase = false
    private var sumAx = 0f
    private var sumAy = 0f
    private var sumN = 0
    private var lastSpeedMs = -1f
    private var learnX = 0f
    private var learnY = 0f
    private var learnEvents = 0
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    fun start() {
        val s = sm
        if (!sensorsOn && s != null) {
            val acc = s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (acc != null) {
                s.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
                s.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { s.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
                sensorsOn = true
                _motion.value = _motion.value.copy(hasTiltSensor = true)
            }
        }
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!gpsOn && granted && lm != null) {
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                    lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
                    gpsOn = true
                    _motion.value = _motion.value.copy(hasGps = true)
                }
            } catch (_: SecurityException) {
            }
        }
    }

    /** Stops the sensors and GPS while the app isn't on screen; [start] turns them back on. */
    fun stop() {
        if (sensorsOn) {
            runCatching { sm?.unregisterListener(this) }
            sensorsOn = false
        }
        if (gpsOn) {
            runCatching { lm?.removeUpdates(this) }
            gpsOn = false
        }
    }

    /** Treat the current attitude as level. */
    fun calibrate() {
        prefs.pitchOffset = rawPitch
        prefs.rollOffset = rawRoll
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val a = 0.12f
                for (i in 0..2) gravity[i] = gravity[i] + a * (event.values[i] - gravity[i])
                val gx = gravity[0]
                val gy = gravity[1]
                val gz = gravity[2]
                sumAx += event.values[0]
                sumAy += event.values[1]
                sumN++
                // This head unit's sensor lies flat: +z reads about +9.8 (up), x and y are horizontal (measured 14 Nov).
                val norm = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.001f)
                val tilt = Math.toDegrees(kotlin.math.acos((gz / norm).coerceIn(-1f, 1f).toDouble())).toFloat()
                val fx = prefs.tiltFwdX
                val fy = prefs.tiltFwdY
                val learned = fx != 0f || fy != 0f
                if (learned) {
                    // Along the car (nose up = positive) and across it (right side down = positive).
                    val along = gx * fx + gy * fy
                    val across = gx * fy - gy * fx
                    rawPitch = Math.toDegrees(atan2(along.toDouble(), gz.toDouble())).toFloat()
                    rawRoll = Math.toDegrees(atan2(-across.toDouble(), gz.toDouble())).toFloat()
                    _motion.value = _motion.value.copy(
                        tiltDeg = tilt, tiltLearned = true,
                        pitch = wrap180(rawPitch - prefs.pitchOffset), roll = wrap180(rawRoll - prefs.rollOffset),
                    )
                } else {
                    _motion.value = _motion.value.copy(tiltDeg = tilt, tiltLearned = false, pitch = null, roll = null)
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                val az = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
                val m = _motion.value
                // Prefer GPS bearing when moving; it isn't confused by the car's metal.
                if ((m.speedKmh ?: 0f) < 8f) _motion.value = m.copy(heading = az)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(location: Location) {
        learnForward(if (location.hasSpeed()) location.speed else null)
        val speed = if (location.hasSpeed()) location.speed * 3.6f else null
        val bearing = if (location.hasBearing()) location.bearing else null
        val m = _motion.value
        _motion.value = m.copy(
            speedKmh = speed,
            bearing = bearing,
            altitudeM = if (location.hasAltitude()) location.altitude else m.altitudeM,
            lat = location.latitude,
            lon = location.longitude,
            heading = if ((speed ?: 0f) >= 8f && bearing != null) bearing else m.heading,
        )
    }

    /** Called on every GPS fix with the speed in m/s. */
    private fun learnForward(speedMs: Float?) {
        val n = sumN
        val ax = if (n > 0) sumAx / n else 0f
        val ay = if (n > 0) sumAy / n else 0f
        sumAx = 0f; sumAy = 0f; sumN = 0
        if (speedMs == null) { lastSpeedMs = -1f; return }
        if (prefs.tiltFwdX != 0f || prefs.tiltFwdY != 0f) { lastSpeedMs = speedMs; return }
        if (speedMs < 0.3f) {
            // At rest: the horizontal part of the reading is just the tilt of the car, which is the baseline to subtract.
            baseX = if (haveBase) baseX + 0.2f * (ax - baseX) else ax
            baseY = if (haveBase) baseY + 0.2f * (ay - baseY) else ay
            haveBase = true
        } else if (haveBase && lastSpeedMs >= 0f) {
            val dv = speedMs - lastSpeedMs
            if (kotlin.math.abs(dv) >= 0.5f) {
                learnX += dv * (ax - baseX)
                learnY += dv * (ay - baseY)
                learnEvents++
                val mag = sqrt(learnX * learnX + learnY * learnY)
                // Enough clear pushes, all pointing the same way.
                if (learnEvents >= 6 && mag >= 4f) {
                    prefs.tiltFwdX = learnX / mag
                    prefs.tiltFwdY = learnY / mag
                    android.util.Log.e("SharkCar", "tilt: forward axis learned x=${prefs.tiltFwdX} y=${prefs.tiltFwdY} after $learnEvents events")
                }
            }
        }
        lastSpeedMs = speedMs
    }

    /** Forget the learned direction (e.g. after the head unit was re-mounted); it is learned again on the next drive. */
    fun resetForward() {
        prefs.tiltFwdX = 0f
        prefs.tiltFwdY = 0f
        learnX = 0f; learnY = 0f; learnEvents = 0
    }

    // Implemented explicitly: on Android 10 these are not default methods yet.
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}

/** Brings an angle into -180..180 degrees. */
private fun wrap180(deg: Float): Float {
    var d = deg % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}
