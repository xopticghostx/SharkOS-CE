package com.sharkhome.launcher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.sharkhome.launcher.car.Reading
import com.sharkhome.launcher.car.VehicleRepository
import com.sharkhome.launcher.container
import com.sharkhome.launcher.settings.ClimatePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Snapshot of car readings plus write helpers, for one recomposition. */
class Car(
    private val repo: VehicleRepository,
    private val scope: CoroutineScope,
    val readings: Map<String, Reading>,
    /** Range from the car's measured use; null before the first poll. */
    val real: com.sharkhome.launcher.car.RealRange? = null,
) {
    /** EV / fuel / combined km to show: the measured estimate, or BYD's own figure until that exists. */
    fun evKm(): Double? = real?.evKm ?: n("vehicle.evRangeKm")
    fun fuelKm(): Double? = real?.fuelKm ?: n("vehicle.fuelRangeKm")
    fun totalKm(): Double? = real?.totalKm ?: bydTotalKm()
    fun bydTotalKm(): Double? {
        n("vehicle.rangeKm")?.let { return it }
        val ev = n("vehicle.evRangeKm")
        val fuel = n("vehicle.fuelRangeKm")
        return if (ev == null && fuel == null) null else (ev ?: 0.0) + (fuel ?: 0.0)
    }

    fun n(key: String): Double? = readings[key]?.value
    fun label(key: String): String? = repo.labelFor(key, readings[key]?.raw)
    fun on(key: String): Boolean = label(key) == "on"
    fun available(key: String): Boolean = readings[key]?.raw != null

    fun readable(key: String): String = label(key) ?: n(key)?.let { "Mode ${it.toInt()}" } ?: "—"

    fun set(key: String, value: Double) {
        repo.setAsync(key, value)
    }

    fun setLabel(key: String, label: String) {
        repo.setLabelAsync(key, label)
    }

    /** Reads the live value, not this snapshot, so two taps in one frame still toggle twice. */
    /** Runs several writes in order (each waits for the one before). A newer call replaces one still running. */
    fun steps(block: suspend (VehicleRepository) -> Unit) = repo.runExclusive { block(repo) }

    fun toggle(key: String) = setLabel(key, if (repo.labelFor(key, repo.readings.value[key]?.raw) == "on") "off" else "on")

    fun nudge(key: String, delta: Double) {
        repo.nudgeAsync(key, delta)
    }

    /** Applies a climate preset step by step, so each write gets its own read-back. */
    fun apply(p: ClimatePreset) {
        repo.runExclusive {
            if (!p.power) {
                repo.writeLabel("climate.power", "off")
            } else {
                repo.writeLabel("climate.power", "on")
                repo.setClimateSync(p.sync)
                repo.writeLabel("climate.dualSync", if (p.sync) "on" else "off")
                repo.write("climate.tempDriver", p.tempDriver)
                repo.write("climate.tempPassenger", if (p.sync) p.tempDriver else p.tempPassenger)
                repo.writeLabel("climate.ac", if (p.ac || p.maxCool) "on" else "off")
                repo.writeLabel("climate.maxCool", if (p.maxCool) "on" else "off")
                if (p.auto) {
                    repo.writeLabel("climate.auto", "on")
                } else {
                    repo.writeLabel("climate.auto", "off")
                    repo.write("climate.fan", p.fan.toDouble())
                    if (p.airflow != null) repo.writeLabel("climate.airflow", p.airflow)
                }
                repo.writeLabel("climate.recirc", if (p.recirc) "on" else "off")
                repo.writeLabel("climate.defrostFront", if (p.defrostFront) "on" else "off")
            }
            repo.writeLabel("climate.defrostRear", if (p.defrostRear) "on" else "off")
            repo.writeLabel("seat.steeringWheelHeat", if (p.steeringWheelHeat) "on" else "off")
            repo.writeLabel("seat.heatDriver", p.seatHeatDriver)
            repo.writeLabel("seat.ventDriver", p.seatVentDriver)
            repo.writeLabel("seat.heatPassenger", p.seatHeatPassenger)
            repo.writeLabel("seat.ventPassenger", p.seatVentPassenger)
        }
    }

    /** The current settings, captured as a new preset. */
    fun capture(name: String): ClimatePreset {
        val powerOn = label("climate.power") != "off"
        val tDriver = n("climate.tempDriver") ?: 21.0
        val syncOn = label("climate.dualSync")?.let { it == "on" } ?: repo.isClimateSynced()
        val tPassenger = if (syncOn) tDriver else (n("climate.tempPassenger") ?: tDriver)
        val autoOn = powerOn && on("climate.auto")
        return ClimatePreset(
            name = name,
            power = powerOn,
            sync = syncOn,
            tempDriver = tDriver,
            tempPassenger = tPassenger,
            fan = (n("climate.fan") ?: 3.0).toInt().coerceIn(1, 7),
            auto = autoOn,
            ac = powerOn && on("climate.ac"),
            maxCool = powerOn && on("climate.maxCool"),
            recirc = powerOn && on("climate.recirc"),
            defrostFront = powerOn && on("climate.defrostFront"),
            defrostRear = on("climate.defrostRear"),
            airflow = if (powerOn && !autoOn) label("climate.airflow")?.takeIf { it in setOf("face", "faceFeet", "feet") } else null,
            steeringWheelHeat = on("seat.steeringWheelHeat"),
            seatHeatDriver = label("seat.heatDriver") ?: "off",
            seatVentDriver = label("seat.ventDriver") ?: "off",
            seatHeatPassenger = label("seat.heatPassenger") ?: "off",
            seatVentPassenger = label("seat.ventPassenger") ?: "off",
        )
    }
}

@Composable
fun rememberCar(): Car {
    val c = LocalContext.current.container
    val readings by c.vehicle.readings.collectAsState()
    val real by c.vehicle.realRange.collectAsState()
    val scope = rememberCoroutineScope()
    return Car(c.vehicle, scope, readings, real)
}

/** Re-reads settings whenever any preference changes. */
@Composable
fun prefsTick(): Int {
    val c = LocalContext.current.container
    val tick by c.prefs.changes.collectAsState()
    return tick
}
