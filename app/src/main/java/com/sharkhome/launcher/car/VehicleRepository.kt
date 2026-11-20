package com.sharkhome.launcher.car

import android.content.Context
import android.os.Build
import android.util.Log
import com.sharkhome.launcher.settings.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class Reading(val key: String, val raw: Double?, val value: Double?, val source: String?, val error: String?)

data class WriteResult(val key: String, val sent: Boolean, val confirmed: Boolean, val message: String)

/**
 * One place for every car value. Polls all signals in [CarCodes] every 1.0 s, and writes
 * with an immediate optimistic UI update plus coalesced hardware write and read-back.
 */
class VehicleRepository(
    private val ctx: Context,
    private val scope: CoroutineScope,
    @Volatile var codes: CarCodes,
    val carAdapter: CarAdapterBackend,
    val bydAuto: BydAutoBackend,
    private val prefs: Prefs,
) {
    private val _readings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    val readings: StateFlow<Map<String, Reading>> = _readings

    private val _lastWrite = MutableStateFlow<WriteResult?>(null)
    val lastWrite: StateFlow<WriteResult?> = _lastWrite

    private val _chargeMinutesLeft = MutableStateFlow<Int?>(null)
    /** Minutes until charging finishes, or null when not charging / unknown. */
    val chargeMinutesLeft: StateFlow<Int?> = _chargeMinutesLeft

    private val rangeEstimator = RangeEstimator(ctx)
    private val _realRange = MutableStateFlow<RealRange?>(null)
    /** Range left from this car's own measured use (see [RangeEstimator]); null until the first poll. */
    val realRange: StateFlow<RealRange?> = _realRange

    private val writeJobs = ConcurrentHashMap<String, Job>()
    private val writeHoldUntil = ConcurrentHashMap<String, Long>()

    private var started = false

    /** True while the app's screen is showing. In the background the slow pass runs every 10 s and the fast one stops. */
    @Volatile var uiVisible = false

    /** When each key was last written, so a poll pass that started earlier can't put the old value back. */
    private val writtenAt = ConcurrentHashMap<String, Long>()
    private var presetJob: Job? = null

    /** Runs a multi-step change (a climate preset) in the app scope; a newer one replaces one still running. */
    @Synchronized
    fun runExclusive(block: suspend () -> Unit) {
        presetJob?.cancel()
        presetJob = scope.launch { block() }
    }

    fun start() {
        if (started) return
        started = true
        carAdapter.connect()
        // Everything else: once a second (a full pass reads ~50 signals, several with fallbacks).
        scope.launch(Dispatchers.IO) {
            var n = 0
            while (isActive) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                pollOnce()
                val took = android.os.SystemClock.elapsedRealtime() - t0
                if (n++ % 60 == 0) Log.e(TAG, "poll: full pass took $took ms")
                delay(if (uiVisible) (1000L - took).coerceAtLeast(250L) else 10_000L)
            }
        }
        // Things that move while driving (speed, indicators, brake lights, gear) get their own fast loop, so they
        // don't wait for the slow pass. Indicators flash at ~1.5 Hz and the speed should look live.
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (uiVisible) pollFast()
                delay(FAST_MS)
            }
        }
    }

    private fun pollFast() {
        val fresh = FAST_KEYS.mapNotNull { key ->
            val sig = codes.signals[key] ?: return@mapNotNull null
            val hold = writeHoldUntil[key]
            if (hold != null && System.currentTimeMillis() < hold) null else key to read(sig)
        }.toMap()
        if (fresh.isEmpty()) return
        _readings.update { cur ->
            // Only publish when something actually changed, so the UI doesn't recompose 6 times a second for nothing.
            if (fresh.all { (k, r) -> cur[k]?.raw == r.raw }) cur else cur + fresh
        }
    }

    fun reprobe() {
        carAdapter.reconnect()
        bydAuto.retry()
        scope.launch(Dispatchers.IO) {
            delay(1200)
            pollOnce()
        }
    }

    /** Swaps in a freshly loaded [CarCodes] (e.g. from the external override file) and triggers an immediate poll. */
    fun reloadCodes(newCodes: CarCodes) {
        codes = newCodes
        scope.launch(Dispatchers.IO) { pollOnce() }
    }

    fun pollOnce() {
        val now = System.currentTimeMillis()
        val fresh = codes.signals.filterKeys { started.not() || it !in FAST_KEYS }.mapValues { (key, sig) ->
            val holdUntil = writeHoldUntil[key]
            val existing = _readings.value[key]
            if (holdUntil != null && now < holdUntil && existing != null) {
                existing
            } else {
                read(sig)
            }
        }
        _readings.update { current ->
            val checkNow = System.currentTimeMillis()
            // Merge into the current map: the fast loop owns FAST_KEYS, so keep its values.
            current + fresh.mapValues { (key, polled) ->
                val holdUntil = writeHoldUntil[key]
                val cur = current[key]
                val written = writtenAt[key]
                if (cur != null && ((holdUntil != null && checkNow < holdUntil) || (written != null && written >= now))) cur else polled
            }
        }
        _chargeMinutesLeft.value = readChargeRest()
        _realRange.value = runCatching { rangeEstimator.update(_readings.value) }.getOrNull()
    }

    /**
     * getChargingRestTime returns [hours, minutes]; 255 means "not charging" (read [255, 255] while plugged in on standby,
     * 7 Nov). The signal map can't express a pair, so it's read here.
     */
    private fun readChargeRest(): Int? {
        val targets = listOfNotNull(carAdapter.target("body"), bydAuto.target("charging.BYDAutoChargingDevice"))
        for (t in targets) {
            val v = runCatching { Reflect.invoke(t, "getChargingRestTime", emptyList()) }.getOrNull() ?: continue
            val parts: List<Int> = when (v) {
                is IntArray -> v.toList()
                is List<*> -> v.mapNotNull { (it as? Number)?.toInt() }
                is Array<*> -> v.mapNotNull { (it as? Number)?.toInt() }
                else -> continue
            }
            if (parts.size < 2) continue
            val (h, m) = parts
            return if (h !in 0..99 || m !in 0..59) null else h * 60 + m
        }
        return null
    }

    fun value(key: String): Double? = _readings.value[key]?.value

    fun isClimateSynced(): Boolean = prefs.climateSync

    fun setClimateSync(synced: Boolean) {
        prefs.climateSync = synced
    }

    /**
     * Optimistically updates [key] immediately so the UI responds with zero lag, coalesces rapid
     * consecutive taps/drags on the same key, and writes the final target value to the vehicle.
     */
    fun setAsync(key: String, value: Double, debounceMs: Long = 0L) {
        val sig = codes.signals[key] ?: return
        val clamped = when {
            key.startsWith("climate.temp") -> value.roundToInt().coerceIn(17, 32).toDouble()
            key == "climate.fan" -> value.roundToInt().coerceIn(1, 7).toDouble()
            key == "energy.socTarget" -> value.roundToInt().coerceIn(25, 70).toDouble()
            else -> value
        }
        val raw = (clamped - sig.offset) / sig.scale
        writeHoldUntil[key] = System.currentTimeMillis() + debounceMs + 1500L
        _readings.update { map ->
            val prev = map[key]
            map + (key to Reading(key, raw, clamped, prev?.source ?: "optimistic", null))
        }
        writeJobs.remove(key)?.cancel()
        writeJobs[key] = scope.launch(Dispatchers.IO) {
            if (debounceMs > 0L) delay(debounceMs)
            writeHoldUntil[key] = System.currentTimeMillis() + 1200L
            write(key, clamped)
        }
    }

    /**
     * Atomically nudges [key] by [delta] on top of the current (or already-optimistic) value,
     * updating the UI immediately and coalescing rapid taps into a single vehicle write.
     */
    fun nudgeAsync(key: String, delta: Double) {
        // Unknown current value (car service not connected yet): don't guess a temperature and send it.
        val cur = _readings.value[key]?.value ?: return
        val target = cur + delta
        setAsync(key, target, debounceMs = 250L)
        if (key.startsWith("climate.temp") && prefs.climateSync) {
            val otherKey = if (key == "climate.tempDriver") "climate.tempPassenger" else "climate.tempDriver"
            setAsync(otherKey, target, debounceMs = 250L)
        }
    }

    /**
     * Optimistically updates a labeled signal (e.g., toggle or mode button) and dispatches the write.
     */
    fun setLabelAsync(key: String, label: String) {
        val raw = rawFor(key, label)
        val sig = codes.signals[key]
        if (raw != null && sig != null) {
            val v = raw * sig.scale + sig.offset
            writeHoldUntil[key] = System.currentTimeMillis() + 1500L
            _readings.update { map ->
                val prev = map[key]
                map + (key to Reading(key, raw.toDouble(), v, prev?.source ?: "optimistic", null))
            }
        }
        writeJobs.remove(key)?.cancel()
        writeJobs[key] = scope.launch(Dispatchers.IO) {
            writeLabel(key, label)
        }
    }

    /** Label for the current raw value, including Off-road modes learned on this car. */
    fun labelFor(key: String, raw: Double?): String? {
        if (raw == null) return null
        return codes.signals[key]?.labelOf(raw)
    }

    fun rawFor(key: String, label: String): Int? {
        return codes.signals[key]?.labels?.get(label)?.takeIf { it >= 0 }
    }

    private fun targets(src: Source): List<Pair<String, Any>> = when (src.backend) {
        "caradapter" ->
            if (src.target == "*") carAdapter.connected().mapNotNull { a -> a.iface?.let { "caradapter.${a.name}" to it } }
            else listOfNotNull((carAdapter.target(src.target) ?: carAdapter.probeExtra(src.target))?.let { "caradapter.${src.target}" to it })
        else -> listOfNotNull(bydAuto.target(src.target)?.let { "bydauto.${src.target}" to it })
    }

    fun read(sig: Signal): Reading {
        var lastErr: String? = null
        var fallbackZero: Reading? = null
        val preferNonZero = sig.key == "vehicle.speedKmh"
        for (src in sig.read) {
            val ts = targets(src)
            if (ts.isEmpty()) {
                if (lastErr == null) lastErr = "${src.label}: not available"
                continue
            }
            for ((label, target) in ts) {
                for (m in src.methods) {
                    if (Reflect.method(target, m, src.args.size) == null) continue
                    try {
                        val raw0 = Reflect.toNumber(Reflect.invoke(target, m, src.args)) ?: continue
                        if (raw0 >= 2147483640.0 || raw0 <= -2147483640.0) continue
                        val raw = if (sig.key == "chassis.steeringAngle" && raw0 > 32767.0 && raw0 < 65535.0) {
                            raw0 - 65536.0
                        } else {
                            raw0
                        }
                        val r = Reading(sig.key, raw, raw * sig.scale + sig.offset, "$label.$m", null)
                        if (preferNonZero && raw <= 0.0) {
                            if (fallbackZero == null) fallbackZero = r
                            continue
                        }
                        return r
                    } catch (t: Throwable) {
                        lastErr = "$label.$m: ${t.message ?: t.javaClass.simpleName}"
                    }
                }
            }
        }
        if (fallbackZero != null) return fallbackZero
        return Reading(sig.key, null, null, null, lastErr ?: "No matching method on this head unit")
    }

    suspend fun write(key: String, value: Double): WriteResult {
        val sig = codes.signals[key]
            ?: return WriteResult(key, false, false, "Unknown signal $key").also { _lastWrite.value = it }
        val raw = (value - sig.offset) / sig.scale
        return attempt(sig, value, sig.write, appendValue = raw, wantLabel = null)
    }

    suspend fun writeLabel(key: String, label: String): WriteResult {
        // No signal is refused here (owner's decision, GitHub #7, 20 Nov 2026): the app only writes what the user sets.
        val raw = rawFor(key, label)
            ?: return WriteResult(key, false, false, "No code known for \"$label\" yet").also { _lastWrite.value = it }
        val sig = codes.signals[key] ?: return WriteResult(key, false, false, "Unknown signal $key")
        val value = raw * sig.scale + sig.offset
        val calls = sig.writeLabels[label]
        return if (calls != null) attempt(sig, value, calls, appendValue = null, wantLabel = label)
        else attempt(sig, value, sig.write, appendValue = raw.toDouble(), wantLabel = label)
    }

    /**
     * Tries each write method in order and reads the value back after each one.
     * Moves on to the next method if the car doesn't confirm, because some firmware methods are
     * empty stubs that accept the call and do nothing.
     */
    private suspend fun attempt(
        sig: Signal,
        value: Double,
        sources: List<Source>,
        appendValue: Double?,
        wantLabel: String?,
    ): WriteResult =
        withContext(Dispatchers.IO) {
            val key = sig.key
            var err: String? = null
            var sent: WriteResult? = null
            var lastBack: Reading? = null
            for (src in sources) {
                val args: List<Any> = if (appendValue != null) src.args + appendValue else src.args
                for ((label, target) in targets(src)) {
                    for (m in src.methods) {
                        if (Reflect.method(target, m, args.size) == null) continue
                        // A newer tap cancels this write: stop here instead of sending the old value by other methods.
                        ensureActive()
                        try {
                            val ret = Reflect.invoke(target, m, args)
                            var back = read(sig)
                            var confirmed = false
                            for (poll in 0 until (sig.confirmMs / 180).coerceAtLeast(3)) {
                                delay(180)
                                back = read(sig)
                                val backValue = back.value
                                val backLabel = labelFor(key, back.raw)
                                confirmed = (backValue != null && abs(backValue - value) < max(0.01, abs(sig.scale) / 2)) ||
                                    (wantLabel != null && backLabel == wantLabel)
                                if (confirmed) break
                            }
                            lastBack = back
                            val backLabel = labelFor(key, back.raw)
                            // Error level: this head unit's logcat drops info/debug/warn lines for app tags.
                            Log.e(TAG, "write $key: $label.$m$args -> $ret, read back ${back.raw} ($backLabel, want ${wantLabel ?: value}) confirmed=$confirmed")
                            if (confirmed) {
                                writtenAt[key] = System.currentTimeMillis()
                                writeHoldUntil.remove(key)
                                _readings.update { it + (key to back) }
                                return@withContext WriteResult(key, true, true, "Confirmed by vehicle").also { _lastWrite.value = it }
                            }
                            if (sent == null) sent = WriteResult(key, true, false, "Sent — the car hasn't reported the new value yet ($label.$m)")
                        } catch (c: kotlinx.coroutines.CancellationException) {
                            Log.e(TAG, "write $key: superseded by a newer value, stopped")
                            throw c
                        } catch (t: Throwable) {
                            err = "$label.$m: ${t.message ?: t.javaClass.simpleName}"
                            Log.e(TAG, "write $key: $label.$m$args failed", t)
                        }
                    }
                }
            }
            if (sent != null) {
                writeHoldUntil[key] = System.currentTimeMillis() + 3000L
            } else {
                writeHoldUntil.remove(key)
                if (lastBack != null) {
                    _readings.update { it + (key to lastBack) }
                }
            }
            (sent ?: WriteResult(key, false, false, err ?: "This head unit has no write method for $key")).also { _lastWrite.value = it }
        }

    /** Remembers the car's current raw road-mode value as [mode] (used by long-press on the Off-road page). */
    fun learnMode(mode: String): Boolean {
        val raw = _readings.value[ROAD_MODE]?.raw ?: return false
        // One code, one mode: drop any other name that held this code, so the tile that is active is unambiguous.
        val code = raw.toInt()
        Log.e(TAG, "terrain: learned $mode = $code")
        prefs.learnedModes = prefs.learnedModes.filterValues { it != code } + (mode to code)
        return true
    }

    /** Plain-text report of everything the car layer can see, for tuning shark6_codes.json. */
    fun report(): String = buildString {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        appendLine("SharkOS-CE probe report — $now")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Firmware: ${Build.DISPLAY}")
        appendLine("Signal map: ${codes.origin}")
        appendLine()
        appendLine("== CarAdapter (${CarAdapterBackend.PKG}) ==")
        appendLine("Status: ${carAdapter.status.value}")
        appendLine("Root interface: ${carAdapter.rootDescriptor ?: "-"}")
        for (a in carAdapter.adapters.values) {
            appendLine("- ${a.name}: ${if (a.iface != null) "OK  ${a.descriptor}" else "FAILED  ${a.error}"}")
            a.iface?.let { iface -> Reflect.inventory(iface).forEach { appendLine("    $it") } }
        }
        appendLine()
        appendLine("== bydauto devices ==")
        val devices = codes.signals.values.flatMap { it.read + it.write + it.writeLabels.values.flatten() }.filter { it.backend == "bydauto" }.map { it.target }.distinct()
        devices.forEach { bydAuto.target(it) }
        for ((name, inst) in bydAuto.loaded()) {
            appendLine("- $name: OK")
            Reflect.inventory(inst).forEach { appendLine("    $it") }
        }
        for ((name, err) in bydAuto.failed()) appendLine("- $name: FAILED  $err")
        appendLine()
        appendLine("== Signals ==")
        for (r in _readings.value.values) {
            val label = labelFor(r.key, r.raw)?.let { " ($it)" } ?: ""
            if (r.raw != null) appendLine("${r.key} = ${r.value} raw=${r.raw}$label  via ${r.source}")
            else appendLine("${r.key} = —  ${r.error}")
        }
        appendLine()
        appendLine("Learned off-road modes: ${prefs.learnedModes}")
    }

    companion object {
        const val ROAD_MODE = "drive.roadMode"
        /** ESC may be switched off from the Drive tab only at or below this speed. */
        const val TAG = "SharkCar"
        /** Read every [FAST_MS] instead of once a second. */
        val FAST_KEYS = setOf("vehicle.speedKmh", "light.turnSignal", "light.brakeLamp", "vehicle.brake", "vehicle.gear", "vehicle.powerKw", "pedal.accelerator", "pedal.brake", "chassis.steeringAngle")
        const val FAST_MS = 150L
    }
}
