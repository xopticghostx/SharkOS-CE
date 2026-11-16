package com.sharkhome.launcher.car

import com.sharkhome.launcher.media.AudioRepository
import com.sharkhome.launcher.settings.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Advanced Settings Startup Memory: remembers BYD convenience settings the car resets on every start
 * (lane assist, speed-limit warning, driver monitoring, …) and puts them back after start-up.
 *
 * Safety rule: anything that touches braking, stability or emergency intervention is refused,
 * whatever the signal map says.
 */
class StartupMemory(
    private val scope: CoroutineScope,
    private val vehicle: VehicleRepository,
    private val audio: AudioRepository,
    private val prefs: Prefs,
) {
    data class Item(val key: String, val title: String, val options: List<String>, val note: String? = null)

    data class Result(val key: String, val ok: Boolean, val message: String, val at: Long)

    private val _results = MutableStateFlow<Map<String, Result>>(emptyMap())
    val results: StateFlow<Map<String, Result>> = _results

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _lastRun = MutableStateFlow<String?>(null)
    val lastRun: StateFlow<String?> = _lastRun

    private val lock = Mutex()
    /** One manual change at a time, so two quick taps can't interleave their writes. */
    private val setLock = Mutex()

    fun remembered(): Map<String, String> = prefs.advancedMemory

    fun isRemembered(key: String) = prefs.advancedMemory.containsKey(key)

    /** Tick/untick "Remember". Ticking stores the car's current setting. */
    fun setRemember(key: String, on: Boolean) {
        val m = prefs.advancedMemory.toMutableMap()
        if (!on) {
            m.remove(key)
        } else if (key == SOUND_EQ) {
            if (prefs.eqSnapshot == null) audio.saveSnapshot()
            m[key] = "snapshot"
        } else {
            val r = vehicle.readings.value[key]
            m[key] = vehicle.labelFor(key, r?.raw) ?: (ALL_ITEMS.first { it.key == key }.options.first())
        }
        prefs.advancedMemory = m
    }

    /**
     * Traffic Sign Recognition panel rule: the three speed-limit options are always remembered (stored with whatever the car
     * shows now, or "off"); the master switch is remembered only if its own Remember tick is set.
     */
    fun ensureTrafficSignMemory() {
        val m = prefs.advancedMemory.toMutableMap()
        var changed = false
        for (key in TRAFFIC_SIGN_KEYS.drop(1)) if (key !in m) {
            m[key] = vehicle.labelFor(key, vehicle.readings.value[key]?.raw) ?: "off"
            changed = true
        }
        if (changed) prefs.advancedMemory = m
    }

    /** Remember [label] for [key] as the value restored at start-up. */
    fun rememberAs(key: String, label: String) { prefs.advancedMemory = prefs.advancedMemory + (key to label) }

    /** Sets a value now; if it's remembered, the new choice becomes the one restored at start-up. */
    suspend fun set(key: String, label: String): WriteResult {
        if (blocked(key)) return WriteResult(key, false, false, "Refused: safety systems are never changed")
        val res = setLock.withLock { vehicle.writeLabel(key, label) }
        _results.value = _results.value + (key to Result(key, res.confirmed, res.message, System.currentTimeMillis()))
        if (res.sent && isRemembered(key)) prefs.advancedMemory = prefs.advancedMemory + (key to label)
        return res
    }

    /** Restores every remembered setting. Waits for the car service, retries, and checks the car's reply. */
    fun restoreAll(reason: String) {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
              try {
                val wanted = prefs.advancedMemory
                if (wanted.isEmpty()) return@withLock
                _running.value = true
                // The car service can take a while after a cold start.
                var waited = 0
                while (vehicle.carAdapter.connected().isEmpty() && waited < 90_000) {
                    delay(2000)
                    waited += 2000
                }
                delay(3000) // let the car finish applying its own defaults first
                vehicle.pollOnce()
                for ((key, label) in wanted) {
                    _results.value = _results.value + (key to restoreOne(key, label))
                }
                _lastRun.value = "Last restore: $reason, ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()).lowercase(Locale.getDefault())}"
              } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
              } catch (t: Throwable) {
                android.util.Log.e("SharkCar", "restore failed", t)
              } finally {
                _running.value = false
              }
            }
        }
    }

    private suspend fun restoreOne(key: String, label: String): Result {
        val now = System.currentTimeMillis()
        if (key == SOUND_EQ) {
            val ok = runCatching { audio.restoreSnapshot() }.getOrDefault(false)
            return Result(key, ok, if (ok) "Sound restored" else "No saved sound settings", now)
        }
        if (blocked(key)) return Result(key, false, "Refused: safety system", now)
        repeat(5) { attempt ->
            val current = vehicle.readings.value[key]
            if (current?.raw != null && vehicle.labelFor(key, current.raw) == label) {
                return Result(key, true, if (attempt == 0) "Already $label" else "Restored to $label", now)
            }
            val res = vehicle.writeLabel(key, label)
            if (res.confirmed) return Result(key, true, "Restored to $label", now)
            delay(3000)
            vehicle.pollOnce()
        }
        val err = vehicle.readings.value[key]?.error
        return Result(key, false, err ?: "The car didn't accept $label", now)
    }

    private fun blocked(key: String): Boolean {
        if (SAFETY.containsMatchIn(key)) return true
        // ESC can be switched from the Drive tab only (owner-authorised); never remembered or restored at start-up.
        if (key == "chassis.stability") return true
        val sig = vehicle.codes.signals[key] ?: return false
        val calls = sig.write + sig.writeLabels.values.flatten()
        return calls.any { src -> src.methods.any { SAFETY.containsMatchIn(it) } }
    }

    companion object {
        const val SOUND_EQ = "advanced.soundEq"

        /** Original Advanced Settings items ONLY. */
        val ITEMS = listOf(
            Item("advanced.laneAssist", "Lane Assist", listOf("off", "on")),
            Item("advanced.trafficSign", "Traffic Sign Recognition", listOf("off", "on")),
            Item("advanced.speedLimitWarning", "Intelligent Speed Limit Warning", listOf("off", "on")),
            Item("advanced.speedLimitControl", "Intelligent Speed Limit Control", listOf("off", "on")),
            Item("advanced.speedLimitTone", "Speed Limit Change Prompt Tone", listOf("off", "on")),
            Item("advanced.driverMonitoring", "Driver Monitoring", listOf("off", "on")),
            Item("advanced.childPresence", "Child Presence Detection", listOf("off", "on"), "Leave this on if children ever travel in the car."),
            Item("advanced.dashMap", "Full-screen Dash Map", listOf("Simple", "Full screen")),
        )

        /** Items shown together in one Traffic Sign Recognition panel (master switch first). */
        val TRAFFIC_SIGN_KEYS = listOf("advanced.trafficSign", "advanced.speedLimitWarning", "advanced.speedLimitControl", "advanced.speedLimitTone")

        /** Items moved to the dedicated Quick Settings screen & tab. */
        val QUICK_ITEMS = listOf(
            Item("drive.regenLevel", "Regenerative Braking", listOf("Standard", "High")),
            Item("chassis.brakeFeel", "Brake Pedal Feel", listOf("Comfort", "Sport")),
            Item("chassis.steerFeel", "Steering Assist Feel", listOf("Comfort", "Sport")),
            Item("body.cargoLight", "Cargo Bed Light", listOf("off", "on")),
            Item("advanced.hud", "Head-Up Display (HUD)", listOf("off", "on")),
            Item("advanced.hudSnow", "HUD Snow Mode", listOf("off", "on"), "High-contrast projection for snow or glare."),
            Item("advanced.blindSpot", "Blind Spot Detection", listOf("off", "on")),
            Item("advanced.wirelessCharge", "Wireless Phone Charger", listOf("off", "on")),
            Item("advanced.mirrorFold", "Auto-Fold Mirrors", listOf("off", "on")),
            Item("energy.socSave", "HEV SOC Hold (AI-Save)", listOf("off", "on")),
        )

        val ALL_ITEMS = ITEMS + QUICK_ITEMS

        /** ABS, ESC/ESP, traction, AEB, emergency steering/braking, airbags: never written. */
        private val SAFETY = Regex("(?i)abs|esc|esp(?!eed)|tcs|(?<!dis)traction|aeb|aes\\b|emergency|collision|airbag|ebd|brakeassist")
    }
}
