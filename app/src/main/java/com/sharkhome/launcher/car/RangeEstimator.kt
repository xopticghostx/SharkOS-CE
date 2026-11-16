package com.sharkhome.launcher.car

import android.content.Context
import org.json.JSONArray

/**
 * Real range left, worked out from what this car has actually used.
 *
 * Why BYD's own figures are optimistic: its fuel average divides the litres burnt by ALL km driven, electric km
 * included (7 Nov: 354.5 L over 17,506 km shows as 2.0 L/100 km, but those litres were burnt over only 3,447 HEV km,
 * which is 10.3 L/100 km). Its EV range is close to the 100 km NEDC claim (99 km at 99%), where owners and reviewers
 * get 80 to 90 km before the engine takes over at 25%.
 *
 * What this does instead:
 *  - electric rate (kWh/100 km) = electricity used / km driven in EV mode
 *  - fuel rate (L/100 km)       = fuel used / km driven in HEV mode
 * both taken from the car's lifetime counters. A snapshot of the counters is saved every few km, so the rate follows
 * the recent driving (towing, highway, city) and falls back to the lifetime rate, then to typical published figures,
 * when there is little recent history.
 *
 *  - EV range   = (battery % - floor %) x 29.58 kWh / electric rate
 *  - fuel range = fuel % x 60 L / fuel rate
 */
data class RealRange(
    val evKm: Double?,
    val fuelKm: Double?,
    /** kWh per 100 km used for the estimate. */
    val elecRate: Double,
    /** L per 100 km used for the estimate. */
    val fuelRate: Double,
    /** Where each rate came from: "recent", "lifetime" or "typical". */
    val elecBasis: String,
    val fuelBasis: String,
) {
    val totalKm: Double? get() = if (evKm == null && fuelKm == null) null else (evKm ?: 0.0) + (fuelKm ?: 0.0)
}

class RangeEstimator(ctx: Context) {

    private data class Snap(val evKm: Double, val hevKm: Double, val kwh: Double, val fuelL: Double)

    private val sp = ctx.getSharedPreferences("sharkrange", Context.MODE_PRIVATE)
    private val snaps = ArrayList<Snap>()

    init {
        runCatching {
            val arr = JSONArray(sp.getString("snaps", "[]"))
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                snaps.add(Snap(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getDouble(3)))
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (s in snaps) arr.put(JSONArray().put(s.evKm).put(s.hevKm).put(s.kwh).put(s.fuelL))
        sp.edit().putString("snaps", arr.toString()).apply()
    }

    private fun current(r: Map<String, Reading>): Snap? {
        val ev = r["stat.evKm"]?.value ?: return null
        val hev = r["stat.hevKm"]?.value ?: return null
        val kwh = r["stat.totalElecKwh"]?.value ?: return null
        val fuel = r["stat.totalFuelL"]?.value ?: return null
        // Counters all read 0 for a moment after the head unit wakes.
        if (ev + hev < 1.0 || (kwh <= 0.0 && fuel <= 0.0)) return null
        return Snap(ev, hev, kwh, fuel)
    }

    /** Call on every slow poll. Saves a snapshot each [STEP_KM] driven. */
    @Synchronized
    fun update(r: Map<String, Reading>): RealRange {
        val cur = current(r)
        if (cur != null) {
            val last = snaps.lastOrNull()
            when {
                last == null -> { snaps.add(cur); save() }
                // A counter went backwards (trip data reset, or a different car): the old history is meaningless.
                cur.evKm < last.evKm || cur.hevKm < last.hevKm || cur.kwh < last.kwh - 0.5 || cur.fuelL < last.fuelL - 0.5 -> {
                    snaps.clear(); snaps.add(cur); save()
                }
                (cur.evKm + cur.hevKm) - (last.evKm + last.hevKm) >= STEP_KM -> {
                    snaps.add(cur)
                    while (snaps.size > MAX_SNAPS) snaps.removeAt(0)
                    save()
                }
            }
        }

        val (elecRate, elecBasis) = rate(
            cur, window = 300.0, priorKm = 60.0, typical = TYPICAL_KWH_100, bounds = 12.0..60.0,
            km = { it.evKm }, used = { it.kwh },
        )
        val (fuelRate, fuelBasis) = rate(
            cur, window = 400.0, priorKm = 80.0, typical = TYPICAL_L_100, bounds = 4.0..30.0,
            km = { it.hevKm }, used = { it.fuelL },
        )

        val soc = r["vehicle.batteryPct"]?.value
        val floor = r["stat.socFloor"]?.value?.takeIf { it in 5.0..50.0 } ?: SOC_FLOOR
        val fuelPct = r["vehicle.fuelPct"]?.value
        return RealRange(
            evKm = soc?.let { ((it - floor).coerceAtLeast(0.0) / 100.0) * BATTERY_KWH / elecRate * 100.0 },
            fuelKm = fuelPct?.let { (it.coerceIn(0.0, 100.0) / 100.0) * TANK_L / fuelRate * 100.0 },
            elecRate = elecRate,
            fuelRate = fuelRate,
            elecBasis = elecBasis,
            fuelBasis = fuelBasis,
        )
    }

    /**
     * Use per 100 km over the most recent [window] km of that kind of driving, pulled towards the lifetime rate by
     * [priorKm] so a short hop can't swing the answer.
     */
    private fun rate(
        cur: Snap?,
        window: Double,
        priorKm: Double,
        typical: Double,
        bounds: ClosedFloatingPointRange<Double>,
        km: (Snap) -> Double,
        used: (Snap) -> Double,
    ): Pair<Double, String> {
        if (cur == null) return typical to "typical"
        val lifetime = if (km(cur) >= 200.0) (used(cur) / km(cur) * 100.0).takeIf { it in bounds } else null
        val prior = lifetime ?: typical
        val start = snaps.firstOrNull { km(cur) - km(it) <= window }
        val dKm = start?.let { km(cur) - km(it) } ?: 0.0
        val dUsed = start?.let { used(cur) - used(it) } ?: 0.0
        if (dKm < 15.0 || dUsed < 0.0) return prior to (if (lifetime != null) "lifetime" else "typical")
        val blended = (dUsed + prior * priorKm / 100.0) / (dKm + priorKm) * 100.0
        return blended.coerceIn(bounds.start, bounds.endInclusive) to "recent"
    }

    companion object {
        /** Shark 6 Blade battery, kWh. */
        const val BATTERY_KWH = 29.58
        /** Shark 6 fuel tank, litres. */
        const val TANK_L = 60.0
        /** Battery % where the engine takes over in EV mode (car reports 25 from getSocTargetLowLimit). */
        const val SOC_FLOOR = 25.0
        /**
         * Typical real-world figures, used only until the car has its own history. Reviews and owner reports
         * (Nov 2026): 80 to 90 km of EV driving from 100% to 25% (about 26 kWh/100 km), and 7 to 8 L/100 km in hybrid
         * mode with the battery depleted (BYD's own figure is 7.9; tests range from 6 to 11).
         */
        const val TYPICAL_KWH_100 = 26.0
        const val TYPICAL_L_100 = 8.0
        private const val STEP_KM = 5.0
        private const val MAX_SNAPS = 240
    }
}
