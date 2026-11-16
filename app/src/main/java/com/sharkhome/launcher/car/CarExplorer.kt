package com.sharkhome.launcher.car

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only car explorer. Finds every getter the head unit offers (car adapters, BYD device classes,
 * Android settings tables), polls them, and records every change, so you can flick a switch in the
 * car (indicator, high beam, a setting in BYD's menus) and see exactly which value moved.
 *
 * It NEVER calls a setter: only methods whose names start with get/is/has are called, and only
 * with no arguments or a single small index argument.
 */
class CarExplorer(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val carAdapter: CarAdapterBackend,
    private val bydAuto: BydAutoBackend,
) {
    data class Probe(
        val id: String,          // e.g. "caradapter.body.getTurnLightState" or "...getDoorState[2]"
        val source: String,      // e.g. "caradapter.body"
        val name: String,        // method or setting name, with [index] for indexed getters
        val call: (() -> Any?)?, // null for settings rows (read in bulk)
    )

    data class Value(
        val text: String,
        val changedAt: Long,     // SystemClock.elapsedRealtime(); 0 = never changed since discovery
        val changes: Int,
        val error: Boolean,
    )

    data class Change(val at: Long, val wall: Long, val id: String, val old: String, val new: String, val note: String? = null)

    data class SourceInfo(val name: String, val ok: Boolean, val detail: String, val readable: Int, val methods: List<String>)

    data class ServiceEntry(val name: String, val descriptor: String, val interesting: Boolean)

    data class Component(val pkg: String, val kind: String, val name: String, val exported: Boolean, val permission: String?, val interesting: Boolean)

    private val _sources = MutableStateFlow<List<SourceInfo>>(emptyList())
    val sources: StateFlow<List<SourceInfo>> = _sources

    private val _values = MutableStateFlow<Map<String, Value>>(emptyMap())
    val values: StateFlow<Map<String, Value>> = _values

    private val _probes = MutableStateFlow<List<Probe>>(emptyList())
    val probes: StateFlow<List<Probe>> = _probes

    /**
     * The change log is saved to this file as it grows, so marks survive the car switching off: BYD's power
     * service force-stops SharkOS-CE at every standby, which lost a whole drive of marks on 8 Nov.
     */
    private val logFile = File(ctx.filesDir, "explorer-log.tsv")

    private val _log = MutableStateFlow(loadLog())
    val log: StateFlow<List<Change>> = _log

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    private val _status = MutableStateFlow("Not started")
    val status: StateFlow<String> = _status

    @Volatile var intervalMs: Long = 1000
    @Volatile var includeIndexed: Boolean = false
    @Volatile var includeSettings: Boolean = true

    private var job: Job? = null
    private val slow = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Starts polling (call when the explorer screen opens); [stop] when it closes. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            if (_probes.value.isEmpty()) discover()
            while (isActive) {
                val t0 = SystemClock.elapsedRealtime()
                pollOnce()
                val took = SystemClock.elapsedRealtime() - t0
                _status.value = "${_probes.value.size} values · read in $took ms" + if (_recording.value) " · recording" else ""
                delay((intervalMs - took).coerceAtLeast(100L))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun rediscover() {
        scope.launch(Dispatchers.IO) {
            carAdapter.reconnect()
            bydAuto.retry()
            delay(1500)
            discover()
        }
    }

    fun setRecording(on: Boolean) {
        if (on && !_recording.value) {
            // A new recording replaces the old one; export first to keep it.
            _log.value = emptyList()
            synchronized(logFile) { runCatching { logFile.delete() } }
        }
        _recording.value = on
        if (on) mark("Recording started") else mark("Recording stopped")
    }

    /** Adds a note to the change log, e.g. "left indicator on", so changes can be matched to what you did. */
    fun mark(note: String) {
        append(Change(SystemClock.elapsedRealtime(), System.currentTimeMillis(), "-", "", "", note))
    }

    fun clearLog() {
        _log.value = emptyList()
        synchronized(logFile) { runCatching { logFile.delete() } }
    }

    /** Reads back the change log saved before the app was last stopped. */
    private fun loadLog(): List<Change> = synchronized(logFile) {
        runCatching {
            if (!logFile.isFile) return@runCatching emptyList()
            logFile.readLines().mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 5) return@mapNotNull null
                val wall = f[0].toLongOrNull() ?: return@mapNotNull null
                Change(0L, wall, f[1], f[2], f[3], f[4].ifEmpty { null })
            }.takeLast(MAX_LOG)
        }.getOrDefault(emptyList())
    }

    private fun saveLine(c: Change) {
        fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
        val line = "${c.wall}\t${clean(c.id)}\t${clean(c.old)}\t${clean(c.new)}\t${clean(c.note ?: "")}\n"
        synchronized(logFile) { runCatching { logFile.appendText(line) } }
    }

    // ---------------------------------------------------------------- discovery

    private fun discover() {
        _status.value = "Looking for car interfaces…"
        val probes = ArrayList<Probe>()
        val infos = ArrayList<SourceInfo>()

        // 1. Shark 6 car adapters (CarAdapterService). Try the known names plus a wider guess list.
        for (name in (CarAdapterBackend.ADAPTERS + EXTRA_ADAPTERS).distinct()) {
            val info = carAdapter.adapters[name]
            val iface = info?.iface ?: carAdapter.probeExtra(name)
            if (iface == null) {
                if (info != null) infos.add(SourceInfo("caradapter.$name", false, info.error ?: "not available", 0, emptyList()))
                continue
            }
            addTarget("caradapter.$name", iface, probes, infos)
        }

        // 2. BYD device classes (HomeSpace's route). Names are from BYD's SDK; missing ones are listed as failed.
        for (dev in BYD_DEVICES) {
            val inst = bydAuto.target(dev)
            if (inst == null) {
                infos.add(SourceInfo("bydauto.$dev", false, bydAuto.failed()[dev] ?: "not available", 0, emptyList()))
                continue
            }
            addTarget("bydauto.$dev", inst, probes, infos)
        }

        // 3. Android settings tables. BYD keeps some menu settings here (display modes, assist toggles).
        if (includeSettings) {
            for (table in listOf("system", "global", "secure")) {
                val rows = readSettings(table)
                if (rows == null) {
                    infos.add(SourceInfo("settings.$table", false, "not readable", 0, emptyList()))
                    continue
                }
                rows.keys.sorted().forEach { k -> probes.add(Probe("settings.$table.$k", "settings.$table", k, null)) }
                infos.add(SourceInfo("settings.$table", true, "${rows.size} keys", rows.size, emptyList()))
            }
        }

        slow.clear()
        _probes.value = probes
        _sources.value = infos
        _values.value = emptyMap()
        _status.value = "${probes.size} values from ${infos.count { it.ok }} sources"
    }

    private fun addTarget(label: String, target: Any, probes: MutableList<Probe>, infos: MutableList<SourceInfo>) {
        val methods = Reflect.methodsOf(target)
            .filter { it.declaringClass != Any::class.java && !it.declaringClass.name.startsWith("android.os.") }
            .distinctBy { it.name + it.parameterTypes.joinToString { p -> p.name } }
            .sortedBy { it.name }
        var readable = 0
        for (m in methods) {
            if (!isGetter(m)) continue
            when (m.parameterTypes.size) {
                0 -> {
                    probes.add(Probe("$label.${m.name}", label, m.name) { call(target, m) })
                    readable++
                }
                1 -> if (includeIndexed && isIndex(m.parameterTypes[0])) {
                    for (i in 0..7) {
                        probes.add(Probe("$label.${m.name}[$i]", label, "${m.name}[$i]") { call(target, m, i) })
                    }
                    readable++
                }
            }
        }
        val all = methods.map { m -> "${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }}): ${m.returnType.simpleName}" }
        infos.add(SourceInfo(label, true, target.javaClass.name, readable, all))
    }

    private fun isGetter(m: Method): Boolean {
        if (m.returnType == Void.TYPE) return false
        val n = m.name
        if (n in SKIP) return false
        return n.startsWith("get") || n.startsWith("is") || n.startsWith("has")
    }

    private fun isIndex(t: Class<*>) = t == Int::class.javaPrimitiveType || t == Int::class.javaObjectType

    private fun call(target: Any, m: Method, vararg args: Any): Any? = try {
        runCatching { m.isAccessible = true }
        m.invoke(target, *args)
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }

    // ---------------------------------------------------------------- polling

    private fun pollOnce() {
        val now = SystemClock.elapsedRealtime()
        val old = _values.value
        val next = HashMap<String, Value>(old.size + 64)
        val settings = HashMap<String, Map<String, String>?>()
        val changes = ArrayList<Change>()

        for (p in _probes.value) {
            if ((slow[p.id] ?: 0) >= 3) {
                next[p.id] = old[p.id] ?: Value("skipped (too slow)", 0, 0, true)
                continue
            }
            var err = false
            val text = if (p.call == null) {
                val table = p.source.removePrefix("settings.")
                val rows = settings.getOrPut(table) { readSettings(table) }
                rows?.get(p.name) ?: "(removed)"
            } else {
                val t0 = SystemClock.elapsedRealtime()
                val s = try {
                    show(p.call.invoke())
                } catch (t: Throwable) {
                    err = true
                    "! ${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}"
                }
                if (SystemClock.elapsedRealtime() - t0 > 250) slow[p.id] = (slow[p.id] ?: 0) + 1
                s
            }
            val prev = old[p.id]
            next[p.id] = when {
                prev == null -> Value(text, 0, 0, err)
                prev.text == text -> prev
                else -> {
                    changes.add(Change(now, System.currentTimeMillis(), p.id, prev.text, text))
                    Value(text, now, prev.changes + 1, err)
                }
            }
        }
        _values.value = next
        if (_recording.value) changes.forEach { append(it) }
    }

    private fun append(c: Change) {
        val l = _log.value
        _log.value = if (l.size >= MAX_LOG) l.drop(l.size - MAX_LOG + 1) + c else l + c
        saveLine(c)
    }

    private fun show(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> v.contentToString()
        is LongArray -> v.contentToString()
        is FloatArray -> v.contentToString()
        is DoubleArray -> v.contentToString()
        is BooleanArray -> v.contentToString()
        is ByteArray -> v.joinToString(" ", "[", "]") { "%02x".format(it) }
        is Array<*> -> v.joinToString(", ", "[", "]") { show(it) }
        is Float -> if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> v.toString().take(200)
    }

    private fun readSettings(table: String): Map<String, String>? {
        val uri = when (table) {
            "system" -> Settings.System.CONTENT_URI
            "global" -> Settings.Global.CONTENT_URI
            else -> Settings.Secure.CONTENT_URI
        }
        return try {
            ctx.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                val out = HashMap<String, String>()
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    out[name] = c.getString(1) ?: "null"
                }
                out
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- system services (for finding the cluster/nav link)

    /** Every binder service the system will list, with its interface name. Read-only: no service methods are called. */
    fun listServices(): List<ServiceEntry> {
        val names: List<String> = try {
            val sm = Class.forName("android.os.ServiceManager")
            val raw = sm.getMethod("listServices").invoke(null) as Array<*>
            raw.filterIsInstance<String>()
        } catch (_: Throwable) {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("service", "list")).inputStream.bufferedReader().readLines()
                    .mapNotNull { line -> Regex("""^\d+\s+([^:]+):""").find(line)?.groupValues?.get(1)?.trim() }
            }.getOrDefault(emptyList())
        }
        val getService = runCatching { Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java) }.getOrNull()
        return names.sorted().map { n ->
            val desc = runCatching { (getService?.invoke(null, n) as? IBinder)?.interfaceDescriptor }.getOrNull() ?: ""
            ServiceEntry(n, desc, INTERESTING.containsMatchIn(n) || INTERESTING.containsMatchIn(desc))
        }
    }

    /** Services, receivers and providers inside BYD / TS packages, to find where the cluster gets its map. */
    @Suppress("DEPRECATION")
    fun listCarComponents(): List<Component> {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_ACTIVITIES
        val pkgs: List<PackageInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            else pm.getInstalledPackages(0)
        }.getOrDefault(emptyList())
        val out = ArrayList<Component>()
        for (p in pkgs.map { it.packageName }.filter { n -> CAR_PACKAGES.any { n.startsWith(it) } }.sorted()) {
            val info = runCatching {
                if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(p, PackageManager.PackageInfoFlags.of(flags.toLong()))
                else pm.getPackageInfo(p, flags)
            }.getOrNull() ?: continue
            info.services?.forEach { out.add(Component(p, "service", it.name, it.exported, it.permission, INTERESTING.containsMatchIn(it.name))) }
            info.receivers?.forEach { out.add(Component(p, "receiver", it.name, it.exported, it.permission, INTERESTING.containsMatchIn(it.name))) }
            info.providers?.forEach { out.add(Component(p, "provider", it.authority ?: it.name, it.exported, it.readPermission, INTERESTING.containsMatchIn(it.name))) }
            info.activities?.forEach { out.add(Component(p, "activity", it.name, it.exported, it.permission, INTERESTING.containsMatchIn(it.name))) }
        }
        return out
    }

    // ---------------------------------------------------------------- export

    /** Saves the change log, current values and the full method lists as text, for working out the signal map. */
    fun export(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val text = buildString {
            appendLine("SharkOS-CE car explorer — $stamp")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${Build.DISPLAY}")
            appendLine()
            appendLine("== Change log (time, value, old -> new) ==")
            for (c in _log.value) {
                if (c.note != null) appendLine("${fmt.format(Date(c.wall))}  ---- ${c.note} ----")
                else appendLine("${fmt.format(Date(c.wall))}  ${c.id}  ${c.old} -> ${c.new}")
            }
            appendLine()
            appendLine("== Current values ==")
            val v = _values.value
            for (p in _probes.value) appendLine("${p.id} = ${v[p.id]?.text ?: "-"}")
            appendLine()
            appendLine("== Sources and every method they offer ==")
            for (s in _sources.value) {
                appendLine("- ${s.name}: ${if (s.ok) "OK ${s.detail}" else "FAILED ${s.detail}"}")
                s.methods.forEach { appendLine("    $it") }
            }
        }
        val name = "shark-explorer-$stamp.txt"
        val places = ArrayList<String>()
        ctx.getExternalFilesDir(null)?.let { dir -> runCatching { File(dir, name).writeText(text) }.onSuccess { places.add("app folder") } }
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri: Uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no Downloads")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            places.add("Downloads/$name")
        }
        return if (places.isEmpty()) "Couldn't save the export" else "Saved to ${places.joinToString(" and ")}"
    }

    suspend fun exportAsync(): String = withContext(Dispatchers.IO) { export() }

    companion object {
        const val MAX_LOG = 5000

        /** Extra CarAdapterService adapter names beyond the six the app uses first. Unknown names just fail. */
        val EXTRA_ADAPTERS = listOf("light", "drive", "audio", "cloud", "general", "sensor", "assist", "adas", "instrument", "energy", "charge", "chassis", "door", "window", "battery_service", "diagnostic")

        /** BYD SDK device classes worth trying. Ones this firmware doesn't have show as failed. */
        val BYD_DEVICES = listOf(
            "ac.BYDAutoAcDevice",
            "statistic.BYDAutoStatisticDevice",
            "light.BYDAutoLightDevice",
            "bodywork.BYDAutoBodyworkDevice",
            "instrument.BYDAutoInstrumentDevice",
            "speed.BYDAutoSpeedDevice",
            "gearbox.BYDAutoGearboxDevice",
            "engine.BYDAutoEngineDevice",
            "energy.BYDAutoEnergyDevice",
            "charging.BYDAutoChargingDevice",
            "tyre.BYDAutoTyreDevice",
            "setting.BYDAutoSettingDevice",
            "sensor.BYDAutoSensorDevice",
            "radar.BYDAutoRadarDevice",
            "doorlock.BYDAutoDoorLockDevice",
            "safetybelt.BYDAutoSafetyBeltDevice",
            "adas.BYDAutoADASDevice",
            "power.BYDAutoPowerDevice",
            "panorama.BYDAutoPanoramaDevice",
        )

        private val SKIP = setOf("getClass", "hashCode", "toString", "asBinder", "getInterfaceDescriptor", "getDefaultImpl", "getInstance", "getTransactionName", "getMaxTransactionId")

        private val CAR_PACKAGES = listOf("com.ts.", "com.byd.", "com.bsj.", "com.desay", "com.autonavi", "com.ecarx")

        /** Names that hint at the driver display, navigation hand-off or projection. */
        val INTERESTING = Regex("(?i)navi|cluster|instrument|meter|hud|ipc|dash|carplay|android ?auto|projection|guidance|turn|map")
    }
}
