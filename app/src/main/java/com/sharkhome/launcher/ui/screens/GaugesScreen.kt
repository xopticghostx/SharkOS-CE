package com.sharkhome.launcher.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sharkhome.launcher.container
import com.sharkhome.launcher.sensors.Motion
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent
import com.sharkhome.launcher.ui.components.Car
import com.sharkhome.launcher.ui.components.Radius4
import com.sharkhome.launcher.ui.components.Shape10
import com.sharkhome.launcher.ui.components.SmallButton
import com.sharkhome.launcher.ui.components.Txt
import com.sharkhome.launcher.ui.components.fmt
import com.sharkhome.launcher.ui.components.prefsTick
import com.sharkhome.launcher.ui.components.rememberCar
import kotlin.math.abs

data class GaugeDef(
    val id: String,
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val decimals: Int = 0,
    val centred: Boolean = false,
    val customColor: Color? = null,
    val read: (Car, Motion) -> Double?,
)

val ALL_GAUGES = listOf(
    GaugeDef("speed", "Speed", "km/h", 0.0, 180.0) { car, motion ->
        car.n("vehicle.speedKmh")?.takeIf { it > 0.0 } ?: motion.speedKmh?.toDouble() ?: car.n("vehicle.speedKmh")
    },
    GaugeDef("gps_speed", "GPS Speed", "km/h", 0.0, 180.0) { _, motion ->
        motion.speedKmh?.toDouble()
    },
    GaugeDef("battery", "Battery", "%", 0.0, 100.0, customColor = C.Green) { car, _ ->
        car.n("vehicle.batteryPct")
    },
    GaugeDef("fuel", "Fuel", "%", 0.0, 100.0, customColor = C.Amber) { car, _ ->
        car.n("vehicle.fuelPct")
    },
    GaugeDef("power", "Drive Power", "kW", -60.0, 180.0, centred = true) { car, _ ->
        car.n("vehicle.powerKw")
    },
    GaugeDef("consumption", "Energy Use", "kWh/100 km", 0.0, 40.0, decimals = 1) { car, _ ->
        car.n("vehicle.consumption")
    },
    GaugeDef("fuel_avg", "Avg Fuel", "L/100 km", 0.0, 20.0, decimals = 1) { car, _ ->
        car.n("vehicle.fuelAvg")
    },
    GaugeDef("pitch", "Pitch", "°", -45.0, 45.0, centred = true) { _, motion ->
        motion.pitch?.toDouble()
    },
    GaugeDef("roll", "Roll", "°", -45.0, 45.0, centred = true) { _, motion ->
        motion.roll?.toDouble()
    },
    GaugeDef("heading", "Heading", "°", 0.0, 360.0) { _, motion ->
        motion.heading?.toDouble()
    },
    GaugeDef("altitude", "Altitude", "m", -100.0, 3000.0) { _, motion ->
        motion.altitudeM?.toDouble()
    },
    GaugeDef("charging_power", "Charging Power", "kW", 0.0, 60.0, decimals = 1, customColor = C.Green) { car, _ ->
        val plugged = (car.n("vehicle.chargingGun") ?: 0.0) > 0.0
        car.n("vehicle.chargingPower")?.takeIf { plugged && it in 0.0..200.0 } ?: 0.0
    },
    GaugeDef("discharge_power", "V2L Discharge", "kW", 0.0, 6.0, decimals = 2, customColor = Color(0xFF3E6AE1)) { car, _ ->
        val plugged = (car.n("vehicle.chargingGun") ?: 0.0) > 0.0
        if (plugged) car.n("vehicle.dischargePower") else 0.0
    },
    GaugeDef("tyre_fl", "Front Left Tyre", "psi", 20.0, 50.0) { car, _ -> car.n("tyre.fl") },
    GaugeDef("tyre_fr", "Front Right Tyre", "psi", 20.0, 50.0) { car, _ -> car.n("tyre.fr") },
    GaugeDef("tyre_rl", "Rear Left Tyre", "psi", 20.0, 50.0) { car, _ -> car.n("tyre.rl") },
    GaugeDef("tyre_rr", "Rear Right Tyre", "psi", 20.0, 50.0) { car, _ -> car.n("tyre.rr") },
    GaugeDef("temp_inside", "Cabin Temp", "°C", 15.0, 35.0) { car, _ ->
        car.n("climate.insideTemp") ?: car.n("climate.tempDriver")
    },
    GaugeDef("temp_outside", "Outside Temp", "°C", -10.0, 50.0) { car, _ -> car.n("climate.outsideTemp") },
    GaugeDef("battery_health", "Battery Health", "%", 50.0, 100.0, customColor = C.Green) { car, _ ->
        car.n("vehicle.batteryHealth")
    },
    GaugeDef("ev_range", "EV Range", "km", 0.0, 150.0) { car, _ -> car.n("vehicle.evRangeKm") },
    GaugeDef("fuel_range", "Fuel Range", "km", 0.0, 1000.0) { car, _ -> car.n("vehicle.fuelRangeKm") },
    GaugeDef("range", "Combined Range", "km", 0.0, 1100.0) { car, _ ->
        car.n("vehicle.rangeKm") ?: listOfNotNull(car.n("vehicle.evRangeKm"), car.n("vehicle.fuelRangeKm")).takeIf { it.isNotEmpty() }?.sum()
    },
    GaugeDef("ext_range", "Extended Range (65L)", "km", 0.0, 1200.0, customColor = C.Amber) { car, _ ->
        val ev = car.n("vehicle.evRangeKm") ?: 0.0
        val fuelPct = car.n("vehicle.fuelPct") ?: return@GaugeDef car.n("vehicle.rangeKm")
        val avgL100 = (car.n("vehicle.fuelAvg") ?: 7.5).coerceIn(4.0, 20.0)
        val extLitres = (fuelPct / 100.0) * 59.0 + 6.0
        ev + (extLitres / avgL100) * 100.0
    },
    // Powertrain & Motors
    GaugeDef("engine_rpm", "Engine Speed", "rpm", 0.0, 6000.0) { car, _ -> car.n("engine.rpm") },
    GaugeDef("motor_front", "Front Motor", "rpm", -15000.0, 15000.0, centred = true) { car, _ -> car.n("motor.frontRpm") },
    GaugeDef("motor_rear", "Rear Motor", "rpm", -30000.0, 30000.0, centred = true) { car, _ -> car.n("motor.rearRpm") },
    GaugeDef("torque_front", "Front Motor Torque", "Nm", -400.0, 400.0, centred = true) { car, _ -> car.n("motor.frontTorque") },
    GaugeDef("torque_rear", "Rear Motor Torque", "Nm", -400.0, 400.0, centred = true) { car, _ -> car.n("motor.rearTorque") },
    GaugeDef("temp_motor_front", "Front Motor Temp", "°C", 0.0, 120.0) { car, _ -> car.n("motor.frontTemp") },
    GaugeDef("temp_motor_rear", "Rear Motor Temp", "°C", 0.0, 120.0) { car, _ -> car.n("motor.rearTemp") },
    GaugeDef("volt_motor_front", "Front MCU Bus", "V", 250.0, 450.0) { car, _ -> car.n("motor.frontVolt") },
    GaugeDef("volt_motor_rear", "Rear MCU Bus", "V", 250.0, 450.0) { car, _ -> car.n("motor.rearVolt") },
    GaugeDef("curr_motor_front", "Front MCU Current", "A", -300.0, 300.0, centred = true) { car, _ -> car.n("motor.frontCurrent") },
    GaugeDef("curr_motor_rear", "Rear MCU Current", "A", -300.0, 300.0, centred = true) { car, _ -> car.n("motor.rearCurrent") },
    // 12V & Battery
    GaugeDef("battery_12v", "12V Battery", "V", 10.0, 15.5, decimals = 1, customColor = C.Green) { car, _ -> car.n("battery.voltage") },
    GaugeDef("battery_temp", "Battery Temp", "°C", -10.0, 60.0) { car, _ -> car.n("battery.temp") },
    GaugeDef("soc_target", "SOC Hold Target", "%", 25.0, 70.0) { car, _ -> car.n("energy.socTarget") },
    // Pedals & Steering
    GaugeDef("pedal_accel", "Throttle Pedal", "%", 0.0, 100.0) { car, _ -> car.n("pedal.accelerator") },
    GaugeDef("pedal_brake", "Brake Pedal", "%", 0.0, 100.0, customColor = C.Red) { car, _ -> car.n("pedal.brake") },
    GaugeDef("steering_angle", "Steering Angle", "°", -540.0, 540.0, decimals = 1, centred = true) { car, _ -> car.n("chassis.steeringAngle") },
    // Tyre Temperatures
    GaugeDef("tyre_temp_fl", "FL Tyre Temp", "°C", 0.0, 80.0) { car, _ -> car.n("tyre.tempFl") },
    GaugeDef("tyre_temp_fr", "FR Tyre Temp", "°C", 0.0, 80.0) { car, _ -> car.n("tyre.tempFr") },
    GaugeDef("tyre_temp_rl", "RL Tyre Temp", "°C", 0.0, 80.0) { car, _ -> car.n("tyre.tempRl") },
    GaugeDef("tyre_temp_rr", "RR Tyre Temp", "°C", 0.0, 80.0) { car, _ -> car.n("tyre.tempRr") },
    // Trip & Energy Split
    GaugeDef("trip_distance", "Trip Distance", "km", 0.0, 500.0, decimals = 1) { car, _ -> car.n("trip.distance") },
    GaugeDef("trip_time", "Trip Duration", "min", 0.0, 300.0) { car, _ -> car.n("trip.time") },
    GaugeDef("trip_elec", "Trip EV Energy", "kWh", 0.0, 50.0, decimals = 1) { car, _ -> car.n("trip.elec") },
    GaugeDef("trip_fuel", "Trip Fuel Used", "L", 0.0, 65.0, decimals = 1) { car, _ -> car.n("trip.fuel") },
    GaugeDef("avg_speed", "Average Speed", "km/h", 0.0, 130.0) { car, _ -> car.n("vehicle.avgSpeed") },
    GaugeDef("ev_mileage", "Lifetime EV", "km", 0.0, 100000.0) { car, _ -> car.n("vehicle.evMileage") },
    GaugeDef("hev_mileage", "Lifetime HEV", "km", 0.0, 100000.0) { car, _ -> car.n("vehicle.hevMileage") },
    GaugeDef("energy_drive_pct", "50km Drivetrain Share", "%", 0.0, 100.0) { car, _ -> car.n("energy.drivePct50km") },
    GaugeDef("energy_ac_pct", "50km Climate Share", "%", 0.0, 100.0) { car, _ -> car.n("energy.acPct50km") },
    GaugeDef("energy_aux_pct", "50km Aux Share", "%", 0.0, 100.0) { car, _ -> car.n("energy.auxPct50km") },
    GaugeDef("wireless_power", "Wireless Charger", "W", 0.0, 50.0) { car, _ -> car.n("cabin.wirelessPower") },
    GaugeDef("light_intensity", "Ambient Light", "lvl", 0.0, 10.0) { car, _ -> car.n("env.lightIntensity") },
)

private val DEFAULT_GAUGE_IDS = listOf("speed", "power", "battery", "fuel", "range", "consumption")

@Composable
fun GaugesScreen() {
    val ctx = LocalContext.current
    val c = ctx.container
    val car = rememberCar()
    val motion by c.motion.motion.collectAsState()
    prefsTick()
    val accent = LocalAccent.current

    val currentIds = remember(c.prefs.gaugesList) {
        val list = c.prefs.gaugesList
        if (list.size == 6) list else DEFAULT_GAUGE_IDS
    }

    var editingSlot by remember { mutableStateOf<Int?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Txt("Gauge layout", 22, FontWeight.Medium)
                    Txt("Tap a gauge to choose what it shows", 16, color = C.Text2)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallButton("Reset Defaults", {
                        c.prefs.gaugesList = DEFAULT_GAUGE_IDS
                    })
                }
            }

            // Slot 1 is the main dial; slots 2-6 are compact rows on the right (dark minimal layout, 8 Nov).
            fun defAt(i: Int) = ALL_GAUGES.firstOrNull { it.id == currentIds.getOrNull(i) } ?: ALL_GAUGES[i.coerceAtMost(ALL_GAUGES.size - 1)]
            fun colourFor(def: GaugeDef) = def.customColor ?: when (def.id) {
                "pitch" -> tiltColor(motion.pitch, accent)
                "roll" -> tiltColor(motion.roll, accent)
                else -> accent
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                val hero = defAt(0)
                HeroGauge(hero, hero.read(car, motion), colourFor(hero), Modifier.weight(1.15f).fillMaxHeight()) { editingSlot = 0 }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (i in 1..5) {
                        val def = defAt(i)
                        BarGaugeRow(def, def.read(car, motion), colourFor(def), Modifier.weight(1f).fillMaxWidth()) { editingSlot = i }
                    }
                }
            }
        }
        // Customizer modal sheet
        editingSlot?.let { slotIndex ->
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f))
                    .clickable { editingSlot = null },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.width(760.dp).fillMaxHeight(0.85f).clip(Shape10)
                        .background(C.Card).border(1.dp, C.Line, Shape10)
                        .clickable(enabled = false) {}
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Txt("Select Gauge for Slot ${slotIndex + 1}", 22, FontWeight.SemiBold)
                            Txt("Choose any vehicle signal or motion sensor", 14, color = C.Text2)
                        }
                        Box(
                            Modifier.size(48.dp).clip(Shape10).background(C.Card2).clickable { editingSlot = null },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Close, null, tint = C.Text, modifier = Modifier.size(20.dp))
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        items(ALL_GAUGES) { item ->
                            val isSelected = currentIds.getOrNull(slotIndex) == item.id
                            Column(
                                Modifier.fillMaxWidth().height(80.dp).clip(Radius4)
                                    .background(if (isSelected) accent.copy(alpha = 0.2f) else C.Card2)
                                    .border(1.dp, if (isSelected) accent else C.Line, Radius4)
                                    .clickable {
                                        val newIds = currentIds.toMutableList()
                                        if (slotIndex < newIds.size) newIds[slotIndex] = item.id
                                        c.prefs.gaugesList = newIds
                                        editingSlot = null
                                    }.padding(12.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Txt(item.label, 16, if (isSelected) FontWeight.SemiBold else FontWeight.Medium, if (isSelected) accent else C.Text)
                                val curVal = item.read(car, motion)
                                Txt(if (curVal != null) "${curVal.fmt(item.decimals)} ${item.unit}" else "— ${item.unit}", 13, color = C.Text2)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun tiltColor(angle: Float?, accent: Color): Color = if (angle != null && abs(angle) >= 25f) C.Amber else accent

/** The main dial: thin 270° arc with ticks, the reading in large light type, min/max under the arc. */
@Composable
internal fun HeroGauge(def: GaugeDef, value: Double?, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(Shape10).background(C.SurfaceCard).clickable(onClick = onClick).padding(28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(def.label, 24, FontWeight.Medium, C.Text2, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Tune, "Change gauge", tint = C.Text2, modifier = Modifier.size(28.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val track = C.Raised
            val tick = C.Text2
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 16f
                val d = size.minDimension - stroke * 4
                val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2 + d * 0.05f)
                val arcSize = Size(d, d)
                drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                if (value != null) {
                    val f = ((value - def.min) / (def.max - def.min)).coerceIn(0.0, 1.0).toFloat()
                    if (def.centred) drawArc(color, 270f, (f - 0.5f) * 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    else drawArc(color, 135f, 270f * f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                // Ticks every 10% outside the arc.
                val c = Offset(topLeft.x + d / 2, topLeft.y + d / 2)
                val r1 = d / 2 + stroke * 1.2f
                for (k in 0..10) {
                    val a = Math.toRadians(135.0 + 27.0 * k)
                    val r2 = r1 + if (k % 5 == 0) 18f else 9f
                    drawLine(tick, Offset(c.x + (r1 * kotlin.math.cos(a)).toFloat(), c.y + (r1 * kotlin.math.sin(a)).toFloat()),
                        Offset(c.x + (r2 * kotlin.math.cos(a)).toFloat(), c.y + (r2 * kotlin.math.sin(a)).toFloat()), 3f)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(value.fmt(def.decimals), 120, FontWeight.Light)
                Txt(def.unit, 24, color = C.Text2)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp)) {
            Txt(def.min.fmt(0), 18, color = C.Text2, modifier = Modifier.weight(1f))
            Txt(def.max.fmt(0), 18, color = C.Text2)
        }
    }
}

/** A compact gauge: label and unit on the left, big number on the right, thin level bar underneath. */
@Composable
internal fun BarGaugeRow(def: GaugeDef, value: Double?, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(Shape10).background(C.SurfaceCard).clickable(onClick = onClick).padding(horizontal = 28.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Txt(def.label, 20, FontWeight.Medium, C.Text2)
                Txt(def.unit, 16, color = C.Text2)
            }
            Txt(value.fmt(def.decimals), 52, FontWeight.Light)
        }
        val f = value?.let { ((it - def.min) / (def.max - def.min)).coerceIn(0.0, 1.0).toFloat() }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(Radius4).background(C.Raised)) {
            if (f != null) {
                if (def.centred) {
                    val lo = minOf(f, 0.5f)
                    val w = kotlin.math.abs(f - 0.5f)
                    Row(Modifier.fillMaxSize()) {
                        if (lo > 0f) Box(Modifier.weight(lo).fillMaxHeight())
                        if (w > 0f) Box(Modifier.weight(w).fillMaxHeight().background(color))
                        Box(Modifier.weight((1f - lo - w).coerceAtLeast(0.001f)).fillMaxHeight())
                    }
                } else {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(color))
                }
            }
        }
    }
}