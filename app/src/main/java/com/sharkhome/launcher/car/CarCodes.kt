package com.sharkhome.launcher.car

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Source(
    val backend: String,          // "caradapter" or "bydauto"
    val target: String,           // adapter name ("hvac", "*") or bydauto device class
    val methods: List<String>,
    val args: List<Double>,
) {
    val label: String get() = "$backend.$target"
}

data class Signal(
    val key: String,
    val read: List<Source>,
    val write: List<Source>,
    val scale: Double,
    val offset: Double,
    val labels: Map<String, Int>,
    /** Per-label calls with fixed arguments (no value appended), e.g. "on" -> start(0), "off" -> stop(0). */
    val writeLabels: Map<String, List<Source>> = emptyMap(),
    /** All raw integer codes that map to a given label when reading (e.g. Lane Assist on = 1, 2, or 3). */
    val readLabels: Map<String, List<Int>> = emptyMap(),
    /** How long to wait for the car to report a written value (ms). Some ADAS switches answer 3-5 s later. */
    val confirmMs: Int = 540,
) {
    fun labelOf(raw: Double?): String? {
        if (raw == null) return null
        val r = raw.toInt()
        readLabels.entries.firstOrNull { (_, codes) -> codes.any { it >= 0 && it == r } }?.let { return it.key }
        return labels.entries.firstOrNull { it.value >= 0 && it.value == r }?.key
    }
}

/** The editable map of car signals (assets/shark6_codes.json, or an override file on the head unit). */
class CarCodes(val signals: Map<String, Signal>, val origin: String) {

    companion object {
        const val FILE_NAME = "shark6_codes.json"

        fun overrideFile(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

        fun load(ctx: Context): CarCodes {
            val override = overrideFile(ctx)
            if (override != null && override.isFile) {
                runCatching { return parse(override.readText(), override.absolutePath) }
            }
            val text = ctx.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
            return parse(text, "built-in")
        }

        fun parse(text: String, origin: String): CarCodes {
            val root = JSONObject(text)
            val sigs = root.getJSONObject("signals")
            val out = LinkedHashMap<String, Signal>()
            for (key in sigs.keys()) {
                val o = sigs.getJSONObject(key)
                val labelsObj = o.optJSONObject("labels")
                val labels = LinkedHashMap<String, Int>()
                val readLabels = LinkedHashMap<String, List<Int>>()
                if (labelsObj != null) {
                    for (name in labelsObj.keys()) {
                        val arr = labelsObj.optJSONArray(name)
                        if (arr != null && arr.length() > 0) {
                            val list = (0 until arr.length()).map { arr.getInt(it) }
                            labels[name] = list.first()
                            readLabels[name] = list
                        } else {
                            val code = labelsObj.getInt(name)
                            labels[name] = code
                            readLabels[name] = listOf(code)
                        }
                    }
                }
                out[key] = Signal(
                    key = key,
                    read = sources(o.optJSONArray("read")),
                    write = sources(o.optJSONArray("write")),
                    scale = o.optDouble("scale", 1.0),
                    offset = o.optDouble("offset", 0.0),
                    labels = labels,
                    writeLabels = writeLabels(o.optJSONObject("writeLabels")),
                    readLabels = readLabels,
                    confirmMs = o.optInt("confirmMs", 540),
                )
            }
            return CarCodes(out, origin)
        }

        private fun writeLabels(obj: JSONObject?): Map<String, List<Source>> {
            if (obj == null) return emptyMap()
            val out = LinkedHashMap<String, List<Source>>()
            for (name in obj.keys()) out[name] = sources(obj.optJSONArray(name))
            return out
        }

        private fun sources(arr: JSONArray?): List<Source> {
            if (arr == null) return emptyList()
            val list = ArrayList<Source>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val backend = if (o.has("caradapter")) "caradapter" else "bydauto"
                val m = o.getJSONArray("methods")
                val methods = (0 until m.length()).map { m.getString(it) }
                val a = o.optJSONArray("args")
                val args = if (a == null) emptyList() else (0 until a.length()).map { a.getDouble(it) }
                list.add(Source(backend, o.getString(backend), methods, args))
            }
            return list
        }
    }
}
