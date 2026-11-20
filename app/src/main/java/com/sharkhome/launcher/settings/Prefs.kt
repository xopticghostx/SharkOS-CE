package com.sharkhome.launcher.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class ClimatePreset(
    val name: String,
    val power: Boolean = true,
    val sync: Boolean = true,
    val tempDriver: Double,
    val tempPassenger: Double = tempDriver,
    val fan: Int,
    val auto: Boolean = false,
    val ac: Boolean,
    val maxCool: Boolean = false,
    val recirc: Boolean = false,
    val defrostFront: Boolean = false,
    val defrostRear: Boolean = false,
    val airflow: String? = null,          // null = unchanged/auto, or "face" / "faceFeet" / "feet"
    val steeringWheelHeat: Boolean = false,
    val seatHeatDriver: String = "off",   // off / low / high
    val seatVentDriver: String = "off",
    val seatHeatPassenger: String = "off",
    val seatVentPassenger: String = "off",
    val builtIn: Boolean = false,
) {
    val summary: String
        get() = buildList {
            if (!power) {
                add("Climate off")
            } else {
                if (sync || tempDriver == tempPassenger) {
                    add("${"%.0f".format(tempDriver)}°")
                } else {
                    add("${"%.0f".format(tempDriver)}°/${"%.0f".format(tempPassenger)}°")
                }
                if (auto) add("Auto") else add("fan $fan")
                if (maxCool) add("max cooling") else if (ac) add("A/C")
                if (recirc) add("recirc")
                when (airflow) {
                    "face" -> add("face")
                    "faceFeet" -> add("face+feet")
                    "feet" -> add("feet")
                }
                if (defrostFront) add("front demist")
            }
            if (defrostRear) add("rear demist")
            if (steeringWheelHeat) add("wheel heat")
            if (seatHeatDriver == seatHeatPassenger && seatVentDriver == seatVentPassenger) {
                if (seatHeatDriver != "off") add("seats heat $seatHeatDriver")
                if (seatVentDriver != "off") add("seats vent $seatVentDriver")
            } else {
                if (seatHeatDriver != "off") add("D heat $seatHeatDriver")
                if (seatVentDriver != "off") add("D vent $seatVentDriver")
                if (seatHeatPassenger != "off") add("P heat $seatHeatPassenger")
                if (seatVentPassenger != "off") add("P vent $seatVentPassenger")
            }
        }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("power", power)
        .put("sync", sync)
        .put("tempDriver", tempDriver)
        .put("tempPassenger", tempPassenger)
        .put("fan", fan)
        .put("auto", auto)
        .put("ac", ac)
        .put("maxCool", maxCool)
        .put("recirc", recirc)
        .put("defrostFront", defrostFront)
        .put("defrostRear", defrostRear)
        .put("airflow", airflow ?: "")
        .put("steeringWheelHeat", steeringWheelHeat)
        .put("seatHeatDriver", seatHeatDriver)
        .put("seatVentDriver", seatVentDriver)
        .put("seatHeatPassenger", seatHeatPassenger)
        .put("seatVentPassenger", seatVentPassenger)

    companion object {
        private val VALID_AIRFLOW = setOf("face", "faceFeet", "feet")
        private val VALID_SEAT = setOf("off", "low", "high")
        private fun cleanSeat(v: String) = if (v in VALID_SEAT) v else "off"

        fun fromJson(o: JSONObject): ClimatePreset {
            val tDriver = if (o.has("tempDriver")) o.getDouble("tempDriver") else o.optDouble("temp", 21.0)
            val tPassenger = if (o.has("tempPassenger")) o.getDouble("tempPassenger") else tDriver
            val legacyHeat = cleanSeat(o.optString("seatHeat", "off"))
            val legacyVent = cleanSeat(o.optString("seatVent", "off"))
            return ClimatePreset(
                name = o.getString("name"),
                power = o.optBoolean("power", true),
                sync = o.optBoolean("sync", tDriver == tPassenger),
                tempDriver = tDriver,
                tempPassenger = tPassenger,
                fan = o.optInt("fan", 3),
                auto = o.optBoolean("auto", false),
                ac = o.optBoolean("ac", false),
                maxCool = o.optBoolean("maxCool", false),
                recirc = o.optBoolean("recirc", false),
                defrostFront = o.optBoolean("defrostFront", false),
                defrostRear = o.optBoolean("defrostRear", false),
                airflow = o.optString("airflow", "").takeIf { it in VALID_AIRFLOW },
                steeringWheelHeat = o.optBoolean("steeringWheelHeat", false),
                seatHeatDriver = cleanSeat(o.optString("seatHeatDriver", legacyHeat)),
                seatVentDriver = cleanSeat(o.optString("seatVentDriver", legacyVent)),
                seatHeatPassenger = cleanSeat(o.optString("seatHeatPassenger", legacyHeat)),
                seatVentPassenger = cleanSeat(o.optString("seatVentPassenger", legacyVent)),
            )
        }

        val BUILT_IN = listOf(
            ClimatePreset(
                name = "Quick cool",
                tempDriver = 18.0,
                fan = 6,
                ac = true,
                maxCool = true,
                seatVentDriver = "high",
                seatVentPassenger = "high",
                builtIn = true,
            ),
            ClimatePreset(
                name = "Quick heat",
                tempDriver = 26.0,
                fan = 5,
                ac = false,
                seatHeatDriver = "high",
                seatHeatPassenger = "high",
                builtIn = true,
            ),
            ClimatePreset(
                name = "Demist",
                tempDriver = 22.0,
                fan = 5,
                ac = true,
                defrostFront = true,
                defrostRear = true,
                builtIn = true,
            ),
        )

        fun nextName(existing: List<ClimatePreset>): String {
            val used = existing.map { it.name.trim().lowercase() }.toSet()
            var n = 1
            while ("my preset $n" in used) n++
            return "My preset $n"
        }
    }
}

/** All user settings in one SharedPreferences file. [changes] ticks on every write so Compose can react. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sharkhome", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        val editor = sp.edit()
        editor.block()
        editor.apply()
        _changes.update { it + 1 }
    }

    var driverOnRight: Boolean
        get() = sp.getBoolean("driverRight", true)
        set(v) = edit { putBoolean("driverRight", v) }


    var accent: Long
        get() = sp.getLong("accent2", com.sharkhome.launcher.ui.DEFAULT_ACCENT)
        set(v) = edit { putLong("accent2", v) }

    var wallpaperUri: String?
        get() = sp.getString("wallpaper", null)
        set(v) = edit { putString("wallpaper", v) }

    var videoUri: String?
        get() = sp.getString("video", null)
        set(v) = edit { putString("video", v) }

    /** Paint colour id from [com.sharkhome.launcher.ui.components.CarPaints]; null = the photo's own Deep Sea Blue. */
    var carColor: String?
        get() = sp.getString("carColor", null)
        set(v) = edit { if (v == null) remove("carColor") else putString("carColor", v) }

    var carImageUri: String?
        get() = sp.getString("carImage", null)
        set(v) = edit { putString("carImage", v) }

    var homeAddress: String
        get() = sp.getString("home", "") ?: ""
        set(v) = edit { putString("home", v) }

    var workAddress: String
        get() = sp.getString("work", "") ?: ""
        set(v) = edit { putString("work", v) }

    var navPackage: String?
        get() = sp.getString("navPkg", null)
        set(v) = edit { putString("navPkg", v) }

    var wadingDepthMm: Int
        get() = sp.getInt("wadingMm", 0)
        set(v) = edit { putInt("wadingMm", v) }

    var pitchOffset: Float
        get() = sp.getFloat("pitchOff2", 0f)
        set(v) = edit { putFloat("pitchOff2", v) }

    var rollOffset: Float
        get() = sp.getFloat("rollOff2", 0f)
        set(v) = edit { putFloat("rollOff2", v) }

    /**
     * Which way "forward" points in the head unit's sensor frame (unit vector in the horizontal x/y plane), learned
     * from the first gentle drive. (0, 0) = not learned yet. The old offsets (pitchOff/rollOff) were for the wrong axes.
     */
    var tiltFwdX: Float
        get() = sp.getFloat("tiltFwdX", 0f)
        set(v) = edit { putFloat("tiltFwdX", v) }

    var tiltFwdY: Float
        get() = sp.getFloat("tiltFwdY", 0f)
        set(v) = edit { putFloat("tiltFwdY", v) }

    var eqEnabled: Boolean
        get() = sp.getBoolean("eqOn", false)
        set(v) = edit { putBoolean("eqOn", v) }

    var bassEnabled: Boolean
        get() = sp.getBoolean("bassOn", false)
        set(v) = edit { putBoolean("bassOn", v) }

    var bassStrength: Int
        get() = sp.getInt("bassStrength", 0)
        set(v) = edit { putInt("bassStrength", v) }

    var eqLevels: List<Int>
        get() = sp.getString("eqLevels", "")!!.split(',').mapNotNull { it.trim().toIntOrNull() }
        set(v) = edit { putString("eqLevels", v.joinToString(",")) }

    var dock: List<String>
        get() = sp.getString("dock", "")!!.split('|').filter { it.isNotBlank() }
        set(v) = edit { putString("dock", v.joinToString("|")) }

    /** Off-road modes learned on the car: mode name -> raw code. */
    var learnedModes: Map<String, Int>
        get() {
            val o = runCatching { JSONObject(sp.getString("modes", "{}") ?: "{}") }.getOrElse { JSONObject() }
            val out = LinkedHashMap<String, Int>()
            for (k in o.keys()) out[k] = o.optInt(k, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: continue
            return out
        }
        set(v) = edit { putString("modes", JSONObject(v as Map<*, *>).toString()) }

    var customPresets: List<ClimatePreset>
        get() {
            val a = runCatching { JSONArray(sp.getString("presets", "[]") ?: "[]") }.getOrElse { JSONArray() }
            return (0 until a.length()).mapNotNull { runCatching { ClimatePreset.fromJson(a.getJSONObject(it)) }.getOrNull() }
        }
        set(v) = edit { putString("presets", JSONArray().apply { v.forEach { put(it.toJson()) } }.toString()) }

    /** Advanced Settings startup memory: signal key -> label to restore (e.g. "advanced.laneAssist" -> "off"). */
    var advancedMemory: Map<String, String>
        get() {
            val raw = sp.getString("advancedMemory", "{}") ?: "{}"
            val o = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            val out = LinkedHashMap<String, String>()
            for (k in o.keys()) {
                out[k] = o.getString(k)
            }
            return out
        }
        set(v) = edit { putString("advancedMemory", JSONObject(v as Map<*, *>).toString()) }

    /** Start SharkOS-CE's background helper when the head unit boots, and restore Advanced Settings. */
    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", true)
        set(v) = edit { putBoolean("autoStart", v) }

    /** Also bring SharkOS-CE to the front after start-up (if it isn't already the Home app). */
    var openOnStart: Boolean
        get() = sp.getBoolean("openOnStart", true)
        set(v) = edit { putBoolean("openOnStart", v) }

    /** Switch USB/wireless debugging straight back on when something turns it off (BYD's OTA app does on every restart). */
    var keepAdbOn: Boolean
        get() = sp.getBoolean("keepAdbOn", false)
        set(v) = edit { putBoolean("keepAdbOn", v) }

    /** Full-screen immersive mode to hide BYD's bottom system navigation bar. */
    var fullScreenMode: Boolean
        get() = sp.getBoolean("fullScreenMode", false) // off by default: BYD's dock (with its rotate button) stays reachable (GitHub #5)
        set(v) = edit { putBoolean("fullScreenMode", v) }

    /** Collapse the left navigation rail into a slim pull tab to maximise dashboard width. */
    var sidebarHidden: Boolean
        get() = sp.getBoolean("sidebarHidden", false)
        set(v) = edit { putBoolean("sidebarHidden", v) }

    /** Package names of stock APK installers the user switched off so SharkOS-CE answers install requests (comma separated). */
    var disabledInstallers: String
        get() = sp.getString("disabledInstallers", "") ?: ""
        set(v) = edit { putString("disabledInstallers", v) }

    /** Where the one navigation bar sits: "left", "right", "top" or "bottom". */
    var navPosition: String
        get() = sp.getString("navPosition", "left") ?: "left"
        set(v) = edit { putString("navPosition", v) }

    /** "auto" (follows BYD's light/dark theme via Android night mode), "light", "dark" or "shark" (dark in deep-sea blue). */
    var themeMode: String
        get() = sp.getString("theme", "auto") ?: "auto"
        set(v) = edit { putString("theme", v) }

    /** Use GPS speed instead of vehicle CAN speed on speedometer / dashboards. */
    var useGpsSpeed: Boolean
        get() = sp.getBoolean("useGpsSpeed", false)
        set(v) = edit { putBoolean("useGpsSpeed", v) }

    /** Sync passenger temperature with driver temperature. */
    var climateSync: Boolean
        get() = sp.getBoolean("climateSync", false)
        set(v) = edit { putBoolean("climateSync", v) }

    /** Map card display source: "auto" (AA when map/nav active, else OSM), "androidauto", or "osm". */
    var mapSource: String
        get() = sp.getString("mapSource", "auto") ?: "auto"
        set(v) = edit { putString("mapSource", v) }

    /**
     * How Android Auto maps are projected into the Map widget:
     * "cluster" = dedicated cluster AA map surface (setClusterSurface — Google Maps / Waze only, never media apps),
     * "main" = primary AA video surface (setSurface + touch — mirrors the active AA screen),
     * "tbt" = Turn-by-Turn HUD over local vector map.
     */
    var aaProjectionMode: String
        get() = sp.getString("aaProjectionMode", "cluster") ?: "cluster"
        set(v) = edit { putString("aaProjectionMode", v) }

    /** Customised list of gauges to display on the Gauges screen. */
    var gaugesList: List<String>
        get() {
            val raw = sp.getString("gaugesList", "") ?: ""
            if (raw.isBlank()) return listOf("speed", "battery", "fuel", "consumption", "pitch", "roll")
            return raw.split(',').filter { it.isNotBlank() }
        }
        set(v) = edit { putString("gaugesList", v.joinToString(",")) }

    /**
     * Home screen widgets: columns separated by ';', widgets by ','. Each widget is "type[=param]:size", e.g.
     * "gauge=speed:4". Size is the widget's share of its column's height (0 = only as tall as it needs).
     */
    var homeLayout: String
        get() = sp.getString("homeLayout", DEFAULT_HOME_LAYOUT) ?: DEFAULT_HOME_LAYOUT
        set(v) = edit { putString("homeLayout", v) }

    /**
     * Home screen widgets on the 12 × 8 grid (v0.6+): "type,x,y,w,h,param,hidden+hidden;…". Null until the driver first
     * edits the layout; until then the old [homeLayout] columns are converted on the fly.
     */
    var homeGrid: String?
        get() = sp.getString("homeGrid", null)
        set(v) = edit { putString("homeGrid", v) }

    /** Fuel tank size in litres, for the Fuel widget's "litres left" estimate (Shark 6: 60 L). */
    var fuelTankL: Double
        get() = sp.getFloat("fuelTankL", 60f).toDouble()
        set(v) = edit { putFloat("fuelTankL", v.toFloat()) }

    /** GitHub owner/repo used by the built-in release updater. */
    var githubRepo: String
        get() = sp.getString("githubRepo", com.sharkhome.launcher.system.AppUpdater.DEFAULT_REPO)
            ?: com.sharkhome.launcher.system.AppUpdater.DEFAULT_REPO
        set(v) = edit { putString("githubRepo", v) }

    /** Set once the favourites bar has been filled the first time, so emptying it later stays empty. */
    var dockSeeded: Boolean
        get() = sp.getBoolean("dockSeeded", false)
        set(v) = edit { putBoolean("dockSeeded", v) }

    /** Pre-tow checklist ticks (bit per item), kept between visits. */
    var towChecklist: Int
        get() = sp.getInt("towChecklist", 0)
        set(v) = edit { putInt("towChecklist", v) }

    /**
     * Test-tab user marks ("works", "fails", "later") keyed by test item id, persisted across restarts so the owner
     * can tick off results in the car and review them later.
     */
    var testMarks: Map<String, String>
        get() {
            val raw = sp.getString("testMarks", "") ?: ""
            if (raw.isBlank()) return emptyMap()
            return raw.split(';').mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx <= 0 || idx >= entry.lastIndex) null
                else entry.substring(0, idx) to entry.substring(idx + 1)
            }.toMap()
        }
        set(v) = edit {
            putString("testMarks", v.entries.joinToString(";") { "${it.key}=${it.value}" })
        }

    fun setTestMark(id: String, mark: String?) {
        val current = testMarks
        val next = if (mark.isNullOrBlank() || current[id] == mark) current - id else current + (id to mark)
        testMarks = next
    }

    /** The build whose one-time Ko-fi thank-you was last shown (0 = never). */
    var supportShownBuild: Int
        get() = sp.getInt("supportShownBuild", 0)
        set(v) = edit { putInt("supportShownBuild", v) }

    var autoCheckUpdates: Boolean
        get() = sp.getBoolean("autoCheckUpdates", true)
        set(v) = edit { putBoolean("autoCheckUpdates", v) }

    var lastUpdateCheckMs: Long
        get() = sp.getLong("lastUpdateCheckMs", 0L)
        set(v) = edit { putLong("lastUpdateCheckMs", v) }

    val presets: List<ClimatePreset>
        get() = (ClimatePreset.BUILT_IN + customPresets).filter { !it.name.contains("Pet", ignoreCase = true) }
}

const val DEFAULT_HOME_LAYOUT = "vehicle:10;climate:5,shortcuts:3;media:6,map:4"
