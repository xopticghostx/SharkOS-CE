package com.sharkhome.launcher.dashcam

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.sharkhome.launcher.car.VehicleRepository
import com.sharkhome.launcher.system.LocalAdb
import com.sharkhome.launcher.system.LocalAdb.Companion.q
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** The four outside cameras. Input ids come from the car's camera service (verified 16 Nov 2026). */
enum class Cam(val inputId: Int, val folder: String, val label: String) {
    FRONT(8, "front", "Front"),
    REAR(5, "rear", "Rear"),
    DRIVER(9, "driver", "Driver side"),
    PASSENGER(4, "passenger", "Passenger side");

    companion object {
        fun fromFolder(f: String) = entries.firstOrNull { it.folder == f }
    }
}

enum class StartMode(val label: String) { MANUAL("Manual"), AUTO("Automatic") }

enum class Quality(val label: String, val fps: Int, val kbps: Int, val note: String) {
    STANDARD("Standard", 15, 3000, "15 fps · about 22 MB a minute per camera"),
    HIGH("High", 20, 5000, "20 fps · about 37 MB a minute per camera"),
}

data class DashCamSettings(
    val startMode: StartMode = StartMode.MANUAL,
    val cams: Set<Cam> = setOf(Cam.FRONT, Cam.REAR),
    val storageGb: Int = 25,
    val quality: Quality = Quality.STANDARD,
    /** "Save moment" keeps this many minutes before the tap. */
    val saveMinutes: Int = 3,
)

data class Clip(val cam: Cam, val file: File, val startMs: Long, val durationMs: Long) {
    val endMs get() = startMs + durationMs
}

/** One drive (a recording session) or a saved moment ("last 3 minutes"). */
data class Trip(
    val id: String,
    val dir: File,
    val startMs: Long,
    val endMs: Long,
    val clips: List<Clip>,
    val kept: Boolean,
    val moment: Boolean,
    val active: Boolean,
    val distanceKm: Double?,
) {
    val sizeBytes get() = clips.sumOf { it.file.length() }
    val cams get() = clips.map { it.cam }.distinct().sortedBy { it.ordinal }
    val durationMs get() = (endMs - startMs).coerceAtLeast(0)
    fun clipsFor(cam: Cam) = clips.filter { it.cam == cam }.sortedBy { it.startMs }
}

data class RecStatus(
    val running: Boolean = false,
    val trip: String? = null,
    val frames: Map<String, Long> = emptyMap(),
    val bytes: Long = 0,
    val error: String = "",
    val startedMs: Long = 0,
)

data class StorageInfo(val loopBytes: Long, val limitBytes: Long, val freeBytes: Long)

/**
 * Dash cam recorder control. The recording itself runs in a small native helper started as the adb shell user (the
 * only account allowed to open the car's cameras); this class installs/starts/stops it, watches its status file and
 * manages the clips it writes under /sdcard/SharkDashCam.
 */
class DashCamEngine(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val adb: LocalAdb,
    private val vehicle: VehicleRepository,
) {
    private val sp = ctx.getSharedPreferences("dashcam", Context.MODE_PRIVATE)
    val root = File(Environment.getExternalStorageDirectory(), "SharkDashCam")
    private val shellRoot = "/sdcard/SharkDashCam"
    private val helperDir = "/data/user_de/0/com.android.shell/sharkcam"
    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<DashCamSettings> = _settings
    private val _status = MutableStateFlow(RecStatus())
    val status: StateFlow<RecStatus> = _status
    private val _busy = MutableStateFlow<String?>(null)
    /** Short message while starting/stopping, or null. */
    val busy: StateFlow<String?> = _busy
    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult

    private var startedAtMs = 0L
    private var parkedSinceMs = 0L
    private var nextAutoTryMs = 0L
    private var autoStarted = false

    init {
        scope.launch(Dispatchers.IO) {
            while (true) {
                refreshStatus()
                autoStep()
                delay(2000)
            }
        }
    }

    // ------------------------------------------------------------ settings

    private fun loadSettings(): DashCamSettings {
        val cams = sp.getString("cams", null)?.split(",")?.mapNotNull { Cam.fromFolder(it) }?.toSet()
        return DashCamSettings(
            startMode = StartMode.entries.firstOrNull { it.name == sp.getString("startMode", null) } ?: StartMode.MANUAL,
            cams = if (cams.isNullOrEmpty()) setOf(Cam.FRONT, Cam.REAR) else cams,
            storageGb = sp.getInt("storageGb", 25),
            quality = Quality.entries.firstOrNull { it.name == sp.getString("quality", null) } ?: Quality.STANDARD,
            saveMinutes = sp.getInt("saveMinutes", 3),
        )
    }

    fun update(block: (DashCamSettings) -> DashCamSettings) {
        val s = block(_settings.value)
        sp.edit().putString("startMode", s.startMode.name).putString("cams", s.cams.joinToString(",") { it.folder })
            .putInt("storageGb", s.storageGb).putString("quality", s.quality.name).putInt("saveMinutes", s.saveMinutes).apply()
        _settings.value = s
    }

    // ------------------------------------------------------------ status

    private fun refreshStatus() {
        val f = File(root, "status.json")
        val s = runCatching {
            val o = JSONObject(f.readText())
            val fresh = System.currentTimeMillis() / 1000 - o.optLong("updated") < 6
            val fr = o.optJSONObject("cams")
            val frames = fr?.keys()?.asSequence()?.associateWith { fr.optLong(it) } ?: emptyMap()
            RecStatus(
                running = o.optBoolean("running") && fresh,
                trip = o.optString("trip").ifEmpty { null },
                frames = frames,
                bytes = o.optLong("bytes"),
                error = o.optString("error"),
                startedMs = parseId(o.optString("trip")) ?: 0,
            )
        }.getOrDefault(RecStatus())
        _status.value = s
        if (s.running) updateTripMeta(s.trip)
    }

    private fun parseId(id: String): Long? = runCatching { fmt.parse(id.take(15))?.time }.getOrNull()

    // ------------------------------------------------------------ start / stop

    suspend fun start(): Boolean {
        // Started from the screen (not by the D/R rule): leave it running until the user stops it.
        if (_status.value.running) return true
        if (_busy.value != null) return false
        _busy.value = "Starting the cameras…"
        _lastResult.value = null
        try {
            if (adb.state.value != LocalAdb.State.CONNECTED && !adb.connect()) {
                _lastResult.value = "Couldn't reach the car's shell. Turn on Wireless debugging and accept the prompt, then try again."
                return false
            }
            val lib = ctx.applicationInfo.nativeLibraryDir + "/libsharkcam_exe.so"
            val s = _settings.value
            val cams = s.cams.joinToString(" ") { "${it.inputId}:${it.folder}" }
            val limitMb = s.storageGb * 1024L
            val cmd = "if pidof sc > /dev/null; then echo already-running; else mkdir -p $helperDir $shellRoot && cp ${q(lib)} $helperDir/sc && chmod 755 $helperDir/sc && cd $helperDir && " +
                "(nohup ./sc record $shellRoot 60 ${s.quality.fps} ${s.quality.kbps} $limitMb $cams > $helperDir/log.txt 2>&1 < /dev/null &) ; echo started; fi"
            val r = adb.shell(cmd)
            Log.e(TAG, "start -> ${r.output.take(200)}")
            // Wait for the helper to report in.
            repeat(12) {
                delay(500)
                refreshStatus()
                if (_status.value.running) {
                    startedAtMs = System.currentTimeMillis()
                    beginTripMeta(_status.value.trip)
                    return true
                }
            }
            val log = runCatching { adb.shell("tail -n 3 $helperDir/log.txt").output.trim() }.getOrDefault("")
            _lastResult.value = "The recorder didn't start. ${log.take(160)}"
            return false
        } finally {
            _busy.value = null
        }
    }

    suspend fun stop() {
        if (_busy.value != null) return
        _busy.value = "Stopping and saving the last clip…"
        try {
            val pid = runCatching { JSONObject(File(root, "status.json").readText()).optInt("pid") }.getOrDefault(0)
            val cmd = if (pid > 0) "kill -TERM $pid" else "kill -TERM \$(pidof sc)"
            adb.shell(cmd)
            repeat(16) {
                delay(500)
                refreshStatus()
                if (!_status.value.running) return
            }
        } finally {
            _busy.value = null
        }
    }

    // ------------------------------------------------------------ auto start

    private suspend fun autoStep() {
        if (_settings.value.startMode != StartMode.AUTO || _busy.value != null) return
        val raw = vehicle.readings.value["vehicle.gear"]?.raw ?: return
        val gear = vehicle.labelFor("vehicle.gear", raw) ?: return
        val now = System.currentTimeMillis()
        val running = _status.value.running
        if ((gear == "D" || gear == "R")) {
            parkedSinceMs = 0
            if (!running && now >= nextAutoTryMs) {
                if (start()) autoStarted = true else nextAutoTryMs = now + 60_000
            }
        } else if (gear == "P" && running && autoStarted) {
            if (parkedSinceMs == 0L) parkedSinceMs = now
            // Keep recording for two minutes after parking so a drive isn't cut mid-manoeuvre.
            if (now - parkedSinceMs > 120_000) { stop(); parkedSinceMs = 0; autoStarted = false }
        }
    }

    // ------------------------------------------------------------ trip metadata (distance)

    private val metaFile get() = File(root, "trips.json")
    private fun odometer(): Double? = vehicle.readings.value["vehicle.odometerKm"]?.value

    private fun readMeta(): JSONObject = runCatching { JSONObject(metaFile.readText()) }.getOrDefault(JSONObject())

    private fun beginTripMeta(trip: String?) {
        val id = trip ?: return
        val odo = odometer() ?: return
        val m = readMeta()
        if (!m.has(id)) m.put(id, JSONObject().put("start", odo).put("end", odo))
        runCatching { root.mkdirs(); metaFile.writeText(m.toString()) }
    }

    private fun updateTripMeta(trip: String?) {
        val id = trip ?: return
        val odo = odometer() ?: return
        val m = readMeta()
        val e = m.optJSONObject(id) ?: JSONObject().put("start", odo)
        if (e.optDouble("end") != odo) {
            e.put("end", odo); m.put(id, e)
            runCatching { metaFile.writeText(m.toString()) }
        }
    }

    // ------------------------------------------------------------ clips

    /** Reads all trips (and saved moments) from disk. */
    suspend fun scan(): List<Trip> = withContext(Dispatchers.IO) {
        val meta = readMeta()
        val activeId = _status.value.trip.takeIf { _status.value.running }
        val out = ArrayList<Trip>()
        for ((base, moment) in listOf(File(root, "loop") to false, File(root, "saved") to true)) {
            base.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val clips = ArrayList<Clip>()
                dir.listFiles()?.filter { it.isDirectory }?.forEach camDir@{ cd ->
                    val cam = Cam.fromFolder(cd.name) ?: return@camDir
                    cd.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }?.forEach { f ->
                        val start = parseId(f.name.removeSuffix(".mp4")) ?: return@forEach
                        val dur = (f.lastModified() - start).coerceIn(1_000, 70_000)
                        clips += Clip(cam, f, start, dur)
                    }
                }
                if (clips.isEmpty()) return@forEach
                val start = clips.minOf { it.startMs }
                val end = clips.maxOf { it.endMs }
                val e = meta.optJSONObject(dir.name)
                val dist = e?.let { (it.optDouble("end") - it.optDouble("start")).takeIf { d -> !d.isNaN() && d >= 0 } }
                out += Trip(dir.name, dir, start, end, clips, kept = !moment && File(dir, ".keep").exists(), moment = moment,
                    active = !moment && dir.name == activeId, distanceKm = dist)
            }
        }
        out.sortedByDescending { it.startMs }
    }

    fun storage(trips: List<Trip>): StorageInfo {
        val loop = trips.filter { !it.moment && !it.kept }.sumOf { it.sizeBytes }
        val free = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0L)
        return StorageInfo(loop, _settings.value.storageGb * 1024L * 1024 * 1024, free)
    }

    /** True only for a trip/moment folder (or something inside one) under SharkDashCam/loop or /saved. Nothing broader is ever removed. */
    private fun safeToRemove(f: File): Boolean {
        val rel = runCatching { f.canonicalFile.relativeTo(root.canonicalFile).path.replace("\\", "/") }.getOrNull() ?: return false
        val parts = rel.split('/')
        return parts.size >= 2 && parts[0] in setOf("loop", "saved") && parts[1].isNotEmpty() && !parts.any { it == ".." || it.isEmpty() }
    }

    /** The recorder runs as the shell user, so files it wrote can only be removed by the shell user too. */
    private suspend fun shellPath(f: File) = "/sdcard/" + f.relativeTo(Environment.getExternalStorageDirectory()).path

    suspend fun setKept(t: Trip, kept: Boolean) = withContext(Dispatchers.IO) {
        val k = File(t.dir, ".keep")
        if (kept) runCatching { k.writeText("keep") }
        else if (!runCatching { k.delete() }.getOrDefault(false) && k.exists() && safeToRemove(k)) runCatching { adb.shell("rm -f ${q(shellPath(k))}") }
        Unit
    }

    suspend fun delete(t: Trip): Boolean = withContext(Dispatchers.IO) {
        if (t.active) return@withContext false
        runCatching { t.dir.deleteRecursively() }
        if (t.dir.exists() && safeToRemove(t.dir)) runCatching { adb.shell("rm -rf ${q(shellPath(t.dir))}") }
        !t.dir.exists()
    }

    /**
     * Saves "an event": copies the last [minutes] minutes (all cameras) into Saved. While recording, the recorder is
     * first told to finish the clips it is writing, so the saved footage runs right up to the moment of the tap.
     * Returns the number of clips saved.
     */
    suspend fun saveLastMinutes(minutes: Int = _settings.value.saveMinutes): Int = withContext(Dispatchers.IO) {
        if (_status.value.running) {
            val pid = runCatching { JSONObject(File(root, "status.json").readText()).optInt("pid") }.getOrDefault(0)
            if (pid > 0) {
                runCatching { adb.shell("kill -USR1 $pid") }
                delay(2500)            // the clips close at the next key frame (within about a second)
            }
        }
        val now = System.currentTimeMillis()
        val from = now - minutes * 60_000L
        val picked = scan().filter { !it.moment }.flatMap { it.clips }.filter { it.endMs >= from && it.startMs <= now }
        if (picked.isEmpty()) return@withContext 0
        val dest = File(root, "saved/${fmt.format(now)}_last${minutes}min")
        var n = 0
        for (c in picked) {
            val d = File(dest, c.cam.folder).apply { mkdirs() }
            runCatching { c.file.copyTo(File(d, c.file.name), overwrite = true); n++ }
        }
        n
    }

    fun previewFile(cam: Cam) = File(root, "live/${cam.folder}.bmp")

    companion object {
        private const val TAG = "SharkDashCam"
    }
}
