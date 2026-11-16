package com.sharkhome.launcher.weather

import android.util.Log
import com.sharkhome.launcher.sensors.MotionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class DayForecast(val dateIso: String, val code: Int, val maxC: Double, val minC: Double, val rainPct: Int?)

data class Weather(
    val tempC: Double,
    val feelsC: Double?,
    val code: Int,
    val windKmh: Double?,
    val humidity: Int?,
    val isDay: Boolean,
    val days: List<DayForecast>,
    val fetchedAt: Long,
)

/**
 * Current weather and a 5-day forecast from Open-Meteo (free, no account or key). Uses the car's GPS position,
 * rounded to 2 decimals (about 1 km), and refreshes every 30 minutes or when the car has moved ~10 km.
 */
class WeatherRepository(private val scope: CoroutineScope, private val motion: MotionRepository) {

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather

    private val _status = MutableStateFlow("Waiting for GPS position…")
    val status: StateFlow<String> = _status

    private var started = false
    @Volatile private var failed = false
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val m = motion.motion.value
                val lat = m.lat
                val lon = m.lon
                if (lat != null && lon != null) {
                    val moved = lastLat.isNaN() || kotlin.math.abs(lat - lastLat) > 0.1 || kotlin.math.abs(lon - lastLon) > 0.1
                    val stale = (_weather.value?.fetchedAt ?: 0L) < System.currentTimeMillis() - 30 * 60_000L
                    if (moved || stale) fetch(lat, lon)
                }
                // After a failed fetch (no data connection) wait 5 minutes rather than retrying every 15 s.
                delay(if (failed) 300_000L else if (_weather.value == null) 15_000L else 60_000L)
            }
        }
    }

    fun refreshNow() {
        val m = motion.motion.value
        val lat = m.lat ?: return
        val lon = m.lon ?: return
        scope.launch(Dispatchers.IO) { fetch(lat, lon) }
    }

    private fun fetch(lat: Double, lon: Double) {
        val la = String.format(Locale.US, "%.2f", lat)
        val lo = String.format(Locale.US, "%.2f", lon)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=5"
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "SharkOS-CE")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            val cur = o.getJSONObject("current")
            val d = o.getJSONObject("daily")
            val dates = d.getJSONArray("time")
            val days = (0 until dates.length()).map { i ->
                DayForecast(
                    dates.getString(i),
                    d.getJSONArray("weather_code").optInt(i),
                    d.getJSONArray("temperature_2m_max").optDouble(i),
                    d.getJSONArray("temperature_2m_min").optDouble(i),
                    d.optJSONArray("precipitation_probability_max")?.let { a -> if (a.isNull(i)) null else a.optInt(i) },
                )
            }
            _weather.value = Weather(
                tempC = cur.getDouble("temperature_2m"),
                feelsC = cur.optDouble("apparent_temperature").takeIf { !it.isNaN() },
                code = cur.optInt("weather_code"),
                windKmh = cur.optDouble("wind_speed_10m").takeIf { !it.isNaN() },
                humidity = cur.optInt("relative_humidity_2m", -1).takeIf { it >= 0 },
                isDay = cur.optInt("is_day", 1) == 1,
                days = days,
                fetchedAt = System.currentTimeMillis(),
            )
            lastLat = lat
            lastLon = lon
            failed = false
            _status.value = "Updated"
        } catch (t: Throwable) {
            failed = true
            Log.e(TAG, "weather fetch failed: ${t.javaClass.simpleName}: ${t.message}")
            _status.value = "No weather (${t.javaClass.simpleName})"
        }
    }

    companion object {
        private const val TAG = "SharkWeather"

        /** WMO weather code -> short description. */
        fun describe(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 66 -> "Rain"
            65, 67 -> "Heavy rain"
            71, 73, 75, 77 -> "Snow"
            80, 81 -> "Showers"
            82 -> "Heavy showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Storm with hail"
            else -> "—"
        }
    }
}
