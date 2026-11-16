package com.sharkhome.launcher.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DownhillSkiing
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sharkhome.launcher.car.VehicleRepository
import com.sharkhome.launcher.container
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent
import com.sharkhome.launcher.ui.components.BtnStyle
import com.sharkhome.launcher.ui.components.Car
import com.sharkhome.launcher.ui.components.CarImage
import com.sharkhome.launcher.ui.components.CarView
import com.sharkhome.launcher.ui.components.DButton
import com.sharkhome.launcher.ui.components.DCard
import com.sharkhome.launcher.ui.components.Hairline
import com.sharkhome.launcher.ui.components.LabelValue
import com.sharkhome.launcher.ui.components.LineSegmented
import com.sharkhome.launcher.ui.components.Radius4
import com.sharkhome.launcher.ui.components.SmallButton
import com.sharkhome.launcher.ui.components.T
import com.sharkhome.launcher.ui.components.ThinBar
import com.sharkhome.launcher.ui.components.ToggleTile
import com.sharkhome.launcher.ui.components.fmt
import com.sharkhome.launcher.ui.components.prefsTick
import com.sharkhome.launcher.ui.components.rememberCar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Drive page: Trail mode (a 4WD cockpit: attitude, trail mode, traction, tyres, position), Towing, and Gauges.
 * Controls only send what has been confirmed on the car; everything else is read-only or says why it is disabled.
 */
@Composable
fun OffRoadScreen(initialTab: Int = 0) {
    var modeTab by rememberSaveable(initialTab) { mutableStateOf(initialTab.coerceIn(0, 2)) } // 0 = Terrain, 1 = Towing, 2 = Gauges
    val car = com.sharkhome.launcher.ui.components.rememberCar()
    prefsTick()

    Column(Modifier.fillMaxSize().padding(end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val title = when (modeTab) {
                0 -> "Drive"
                1 -> "Towing"
                else -> "Gauges"
            }
            T(title, 32, FontWeight.Medium, lineHeight = 38, modifier = Modifier.weight(1f))
            // Read-only chassis status in the header (these are the car's own systems; SharkOS-CE never changes them).
            StatusBadge("Stability", if (car.label("chassis.stability") == "off") "OFF" else "ON", ok = car.label("chassis.stability") != "off", bad = car.label("chassis.stability") == "off")
            StatusBadge("Auto Hold", (car.label("chassis.autoHold") ?: "off").uppercase(), ok = car.on("chassis.autoHold"))
            StatusBadge("Trailer", if (car.on("trailer.mode")) "ON" else "OFF", ok = car.on("trailer.mode"))
            LineSegmented(
                options = listOf("Trail mode", "Towing", "Gauges"),
                selected = modeTab,
                onSelect = { modeTab = it },
                modifier = Modifier.width(520.dp),
                height = 52.dp,
            )
        }

        when (modeTab) {
            0 -> TrailBody(car)
            1 -> TowBody(car)
            else -> Box(Modifier.weight(1f).fillMaxWidth()) { GaugesScreen() }
        }
    }
}

@Composable
private fun StatusBadge(label: String, state: String, ok: Boolean, bad: Boolean = false) {
    Row(
        Modifier.clip(Radius4).background(C.SurfaceCard).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (bad) C.Alert else if (ok) C.Ok else C.InkMuted))
        T("$label · $state", 15, FontWeight.Medium, color = if (bad) C.Alert else if (ok) C.Ink else C.InkSecondary)
    }
}

/** What the car did with the last change: confirmed, or sent but not taken (so the driver is never left guessing). */
@Composable
private fun WriteStatus(modifier: Modifier = Modifier) {
    val last by LocalContext.current.container.vehicle.lastWrite.collectAsState()
    // Always reserve the two lines, so the layout doesn't jump when the first message appears.
    Box(modifier.fillMaxWidth().height(44.dp)) { WriteStatusText(last) }
}

@Composable
private fun WriteStatusText(w: com.sharkhome.launcher.car.WriteResult?) {
    if (w == null) return
    val name = when (w.key) {
        "drive.mode" -> "Drive mode"
        "drive.regenLevel" -> "Regen braking"
        "chassis.brakeFeel" -> "Brake feel"
        "chassis.steerFeel" -> "Steering feel"
        "trailer.mode" -> "Trailer mode"
        "trailer.size" -> "Trailer size"
        "trailer.lightCheck" -> "Light check"
        "drive.roadMode" -> "Trail mode"
        "drive.hillDescent" -> "Hill descent"
        "chassis.stability" -> "Stability control (ESC)"
        else -> w.key.substringAfter('.')
    }
    val text = when {
        w.confirmed -> "$name: confirmed by the car."
        w.sent -> "$name: sent, but the car did not change it. Some settings only change with the car switched on."
        else -> "$name: ${w.message}"
    }
    T(text, 15, color = if (w.confirmed) C.Ok else C.InkSecondary, maxLines = 2)
}

// ------------------------------------------------------------------------------------------------- Terrain

// ------------------------------------------------------------------------------------------------- Trail mode (4WD cockpit)

// Guideline colours only (not the car's limits): amber at "take care", red at "this is steep".
private const val ROLL_CAUTION = 25f
private const val ROLL_LIMIT = 30f
private const val PITCH_CAUTION = 30f
private const val PITCH_LIMIT = 40f

private fun tiltColor(angle: Float?, caution: Float, limit: Float): Color = when {
    angle == null -> C.Ink
    abs(angle) >= limit -> C.Alert
    abs(angle) >= caution -> C.Warn
    else -> C.Ink
}

/**
 * Everything a 4WD driver glances at: live strip on top, then attitude (pitch, roll, peaks), trail mode and the systems
 * you switch on the trail, and a right-hand column with traction (front/rear torque), tyres and position.
 */
@Composable
private fun TrailBody(car: Car) {
    val c = LocalContext.current.container
    val motion by c.motion.motion.collectAsState()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LiveStrip(car, motion.speedKmh)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AttitudeCard(Modifier.weight(0.36f).fillMaxHeight())
            Column(Modifier.weight(0.28f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ModeCard(car)
                SystemsCard(car)
                PositionCard(Modifier.weight(1f))
            }
            Column(Modifier.weight(0.36f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TractionCard(car, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AttitudeCard(modifier: Modifier) {
    val ctx = LocalContext.current
    val c = ctx.container
    val motion by c.motion.motion.collectAsState()
    var peakNose by remember { mutableStateOf(0f) }
    var peakTail by remember { mutableStateOf(0f) }
    var peakLeft by remember { mutableStateOf(0f) }
    var peakRight by remember { mutableStateOf(0f) }
    val pitch = motion.pitch
    val roll = motion.roll
    androidx.compose.runtime.LaunchedEffect(pitch, roll) {
        if (pitch != null) { peakNose = maxOf(peakNose, pitch); peakTail = minOf(peakTail, pitch) }
        if (roll != null) { peakRight = maxOf(peakRight, roll); peakLeft = minOf(peakLeft, roll) }
    }
    DCard(modifier.fillMaxWidth(), padding = 20.dp, spacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            T("Attitude", 22, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (motion.tiltDeg != null) T("Tilt ${motion.tiltDeg.fmt(0, "°")}", 18, FontWeight.Medium, color = tiltColor(motion.tiltDeg, ROLL_CAUTION, ROLL_LIMIT).takeIf { it != C.Ink } ?: C.InkSecondary)
            if (motion.tiltLearned) SmallButton("Set level here", {
                c.motion.calibrate()
                Toast.makeText(ctx, "Current position saved as level", Toast.LENGTH_SHORT).show()
            }, height = 48.dp)
        }
        if (!motion.hasTiltSensor) {
            T("This head unit has no tilt sensor.", 16, color = C.InkMuted)
        } else if (!motion.tiltLearned) {
            // The sensor lies flat inside the head unit, so which way is "forward" can't be assumed. One gentle drive teaches it.
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                T(motion.tiltDeg.fmt(0, "°"), 96, FontWeight.Light, lineHeight = 100)
                T("Total tilt from level", 18, color = C.InkMuted)
                Box(Modifier.height(16.dp))
                T("Pitch and roll appear after one gentle drive: pull away in a straight line a few times and SharkOS-CE learns which way the sensor faces.", 16, color = C.InkSecondary, maxLines = 4)
            }
        } else {
            val steep = (roll != null && abs(roll) >= ROLL_CAUTION) || (pitch != null && abs(pitch) >= PITCH_CAUTION)
            if (steep) {
                val severe = (roll != null && abs(roll) >= ROLL_LIMIT) || (pitch != null && abs(pitch) >= PITCH_LIMIT)
                Box(
                    Modifier.fillMaxWidth().clip(Radius4).background((if (severe) C.Alert else C.Warn).copy(alpha = 0.18f)).padding(12.dp),
                ) {
                    T(
                        if (roll != null && abs(roll) >= ROLL_CAUTION) "Side slope ${abs(roll).fmt(0, "°")}: keep to the high side" else "Steep ${if ((pitch ?: 0f) > 0f) "climb" else "descent"} ${abs(pitch ?: 0f).fmt(0, "°")}",
                        17, FontWeight.Medium, if (severe) C.Alert else C.Warn,
                    )
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TiltDial("Pitch", pitch, Modifier.weight(1f).fillMaxHeight(), PITCH_CAUTION, PITCH_LIMIT) { m -> CarImage(m, CarView.SIDE) }
                TiltDial("Roll", roll, Modifier.weight(1f).fillMaxHeight(), ROLL_CAUTION, ROLL_LIMIT) { m -> CarImage(m, CarView.REAR) }
            }
            Hairline()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabelValue("Nose up", peakNose.fmt(0, "°"), Modifier.weight(1f), valueSize = 20, labelSize = 13)
                LabelValue("Nose down", abs(peakTail).fmt(0, "°"), Modifier.weight(1f), valueSize = 20, labelSize = 13)
                LabelValue("Left side down", abs(peakLeft).fmt(0, "°"), Modifier.weight(1f), valueSize = 20, labelSize = 13)
                LabelValue("Right side down", peakRight.fmt(0, "°"), Modifier.weight(1f), valueSize = 20, labelSize = 13)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val grade = pitch?.let { kotlin.math.tan(Math.toRadians(it.toDouble())) * 100.0 }
                LabelValue("Gradient", grade?.let { (if (it >= 0) "↑ " else "↓ ") + "%.0f %%".format(abs(it)) } ?: "--", Modifier.weight(1f), valueSize = 22, labelSize = 13)
                SmallButton("Reset peaks", { peakNose = 0f; peakTail = 0f; peakLeft = 0f; peakRight = 0f }, height = 48.dp)
            }
        }
    }
}

/** Just which modes you're in: trail mode and drive mode, side by side. (Regen is under Controls > Quick controls.) */
@Composable
private fun ModeCard(car: Car) {
    val raw = car.n(VehicleRepository.ROAD_MODE)?.toInt()
    val trail = car.label(VehicleRepository.ROAD_MODE) ?: raw?.let { "Code $it" } ?: "--"
    val drive = car.label("drive.mode") ?: "--"
    DCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 0.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                T("Trail mode", 14, color = C.InkMuted)
                T(trail, 32, FontWeight.Medium, lineHeight = 38)
            }
            Box(Modifier.width(1.dp).height(52.dp).background(C.Line).align(Alignment.CenterVertically))
            Column(Modifier.weight(1f)) {
                T("Drive mode", 14, color = C.InkMuted)
                T(drive, 32, FontWeight.Medium, lineHeight = 38)
            }
        }
    }
}

/** The switches you reach for on the trail. ESC off and hill descent are the only ones this page sends; Wading and Sand are BYD's. */
@Composable
private fun SystemsCard(car: Car) {
    val c = LocalContext.current.container
    prefsTick()
    DCard(Modifier.fillMaxWidth(), padding = 20.dp, spacing = 12.dp) {
        T("Trail systems", 22, FontWeight.SemiBold)
        Row(Modifier.height(92.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleTile("Hill descent ${if (car.on("drive.hillDescent")) "ON" else "OFF"}", Icons.Rounded.DownhillSkiing, car.on("drive.hillDescent"), Modifier.weight(1f).fillMaxHeight()) {
                car.toggle("drive.hillDescent")
            }
            EscTile(car, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.height(92.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val hold = car.on("chassis.autoHold")
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(Radius4).background(C.P.btnLightest).border(1.dp, C.Line, Radius4).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                T("Auto hold", 20, FontWeight.Medium)
                T(if (hold) "On" else "Off", 15, color = if (hold) C.Ok else C.InkMuted)
            }
            // BYD turns Wading (and Sand) on only after its own risk confirmation, which also changes safety systems, so
            // SharkOS-CE never switches them itself: this opens BYD's settings, where that confirmation happens.
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(Radius4).background(C.P.btnLightest).border(1.dp, C.Line, Radius4)
                    .clickable { c.apps.launchPackage("com.byd.carsettings", "BYD settings") }.padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                T("Wading", 20, FontWeight.Medium)
                T("Open BYD settings", 14, color = C.InkMuted)
            }
        }
        WriteStatus()
    }
}

/**
 * Where the power is going, drawn over the top-view photo of the car (front to the left). The petrol engine charges the battery
 * and also drives the front wheels; the battery feeds the front and rear motors. Moving dashes follow the power;
 * blue = driving, green = regenerating, grey = idle. The engine's torque isn't reported, so its links are steady while it runs.
 * Tyre pressures are in a row at the bottom.
 */
@Composable
private fun TractionCard(car: Car, modifier: Modifier) {
    val front = car.n("motor.frontTorque")
    val rear = car.n("motor.rearTorque")
    val f = (front ?: 0.0).coerceAtLeast(0.0)
    val r = (rear ?: 0.0).coerceAtLeast(0.0)
    DCard(modifier.fillMaxWidth(), padding = 20.dp, spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Traction & tyres", 22, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (f + r > 5.0) T("Front ${"%.0f".format(f / (f + r) * 100)} · Rear ${"%.0f".format(r / (f + r) * 100)} %", 16, color = C.InkSecondary)
        }
        PowerFlow(car, Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LabelValue("Engine", car.n("engine.rpm").fmt(0, " rpm"), Modifier.weight(1f), valueSize = 17, labelSize = 13)
            LabelValue("Front motor", car.n("motor.frontRpm").fmt(0, " rpm") + (car.n("motor.frontTemp")?.takeIf { it > -30.0 }?.let { " · %.0f°".format(it) } ?: ""), Modifier.weight(1.3f), valueSize = 17, labelSize = 13)
            LabelValue("Rear motor", car.n("motor.rearRpm").fmt(0, " rpm") + (car.n("motor.rearTemp")?.takeIf { it > -30.0 }?.let { " · %.0f°".format(it) } ?: ""), Modifier.weight(1.3f), valueSize = 17, labelSize = 13)
        }
        Hairline()
        TyreRow(car)
    }
}

/** The four tyre pressures and temperatures. Amber when under 20 psi or well away from the others. */
@Composable
private fun TyreRow(car: Car) {
    val tyres = listOf(
        Triple("Front left", "tyre.fl", "tyre.tempFl"), Triple("Front right", "tyre.fr", "tyre.tempFr"),
        Triple("Rear left", "tyre.rl", "tyre.tempRl"), Triple("Rear right", "tyre.rr", "tyre.tempRr"),
    )
    val avg = tyres.mapNotNull { car.n(it.second) }.takeIf { it.isNotEmpty() }?.average()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((label, pKey, tKey) in tyres) {
            val psi = car.n(pKey)
            val off = psi != null && avg != null && (psi < 20.0 || abs(psi - avg) > 6.0)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                T(label, 14, color = C.InkMuted, maxLines = 1)
                T(psi.fmt(0, " psi"), 24, FontWeight.Medium, if (off) C.Warn else C.Ink, maxLines = 1)
                T(car.n(tKey).fmt(0, "°C"), 13, color = C.InkMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PowerFlow(car: Car, modifier: Modifier) {
    val accent = LocalAccent.current
    val front = car.n("motor.frontTorque")
    val rear = car.n("motor.rearTorque")
    val engineOn = (car.n("engine.rpm") ?: 0.0) > 300.0
    val scale = maxOf(100.0, abs(front ?: 0.0), abs(rear ?: 0.0))
    fun level(t: Double?): Float = if (t == null || abs(t) < 5.0) 0f else (abs(t) / scale).toFloat().coerceIn(0.2f, 1f)
    val fLevel = level(front)
    val rLevel = level(rear)
    val fRegen = (front ?: 0.0) < -5.0
    val rRegen = (rear ?: 0.0) < -5.0
    val phase by androidx.compose.animation.core.rememberInfiniteTransition(label = "flow").animateFloat(
        initialValue = 0f, targetValue = 40f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.LinearEasing)),
        label = "dash",
    )
    val idle = C.InkMuted.copy(alpha = 0.55f)
    val regenCol = C.Ok
    // Coordinates are in the top-view photo's own pixels (1308 x 539): front of the car on the left.
    val iw = 1308f
    val ih = 539f
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Box(Modifier.fillMaxWidth().aspectRatio(iw / ih)) {
            CarImage(Modifier.fillMaxSize(), CarView.TOP)
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val k = size.width / iw
                fun o(x: Float, y: Float) = Offset(x * k, y * k)
                fun tone(level: Float, regen: Boolean) = if (level <= 0f) idle else if (regen) regenCol else accent
                // A moving dashed path through [pts]; reversed when [reverse] (regen / charging back). Glow underneath so it reads on the paint.
                fun path(pts: List<Offset>, level: Float, reverse: Boolean, regen: Boolean, thin: Boolean = false) {
                    val active = level > 0f
                    val col = tone(level, regen)
                    val w = if (active) (if (thin) 5f else 7f) + 9f * level * k * 1.6f else 4f
                    val ps = if (reverse) pts.reversed() else pts
                    val pa = androidx.compose.ui.graphics.Path().apply {
                        moveTo(ps[0].x, ps[0].y)
                        for (i in 1 until ps.size) lineTo(ps[i].x, ps[i].y)
                    }
                    drawPath(pa, Color.Black.copy(alpha = 0.45f), style = androidx.compose.ui.graphics.drawscope.Stroke(w + 6f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    drawPath(pa, col, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        pathEffect = if (active) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(18f, 12f), -phase) else null))
                }
                val fx = 262f; val rx = 1030f; val yTop = 38f; val yBot = 502f; val cy = 270f
                val engine = o(110f, cy); val fMotor = o(fx, cy); val battery = o(640f, cy); val rMotor = o(rx, cy)
                val genLane = 345f   // engine -> battery (generator) runs along here
                val fLane = 195f     // battery -> front motor
                val engLevel = if (engineOn) 0.7f else 0f
                // engine charges the battery
                path(listOf(o(110f, cy), o(110f, genLane), o(600f, genLane), o(600f, 318f)), engLevel, false, false)
                // engine also supplies some power straight to the front wheels (drive link into the front motor/axle)
                path(listOf(o(110f, cy), o(fx, cy)), if (engineOn && fLevel > 0f && !fRegen) minOf(0.5f, fLevel) else 0f, false, false, thin = true)
                // battery <-> front motor, battery <-> rear motor (reversed when regenerating into the battery)
                path(listOf(o(640f, 222f), o(640f, fLane), o(fx, fLane), o(fx, 235f)), fLevel, fRegen, fRegen)
                path(listOf(o(700f, cy), o(rx, cy)), rLevel, rRegen, rRegen)
                // motors to the wheels each axle drives
                path(listOf(o(fx, cy), o(fx, yTop)), fLevel, fRegen, fRegen, thin = true)
                path(listOf(o(fx, cy), o(fx, yBot)), fLevel, fRegen, fRegen, thin = true)
                path(listOf(o(rx, cy), o(rx, yTop)), rLevel, rRegen, rRegen, thin = true)
                path(listOf(o(rx, cy), o(rx, yBot)), rLevel, rRegen, rRegen, thin = true)
                // wheels
                for ((x, lv, rg) in listOf(Triple(fx, fLevel, fRegen), Triple(rx, rLevel, rRegen))) for (y in listOf(yTop, yBot)) {
                    val c = o(x, y)
                    drawCircle(Color.Black.copy(alpha = 0.5f), 17f * k * 1.5f, c)
                    drawCircle(tone(lv, rg), 12f * k * 1.5f, c)
                }
                // components
                fun node(c: Offset, w: Float, h: Float, on: Boolean, level: Float, regen: Boolean) {
                    val col = if (on) (if (level > 0f) tone(level, regen) else accent) else idle
                    val tl = Offset(c.x - w * k / 2, c.y - h * k / 2)
                    val sz = androidx.compose.ui.geometry.Size(w * k, h * k)
                    drawRoundRect(Color.Black.copy(alpha = 0.55f), tl, sz, androidx.compose.ui.geometry.CornerRadius(14f))
                    drawRoundRect(col, tl, sz, androidx.compose.ui.geometry.CornerRadius(14f), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
                }
                node(engine, 110f, 130f, engineOn, 0f, false)
                node(fMotor, 70f, 90f, fLevel > 0f, fLevel, fRegen)
                node(battery, 230f, 110f, true, 0f, false)
                node(rMotor, 70f, 90f, rLevel > 0f, rLevel, rRegen)
            }
        }
        // names and values under the picture, aligned to the parts above
        BoxWithConstraintsLabels(car, front, rear, engineOn, fLevel, rLevel)
    }
}

@Composable
private fun BoxWithConstraintsLabels(car: Car, front: Double?, rear: Double?, engineOn: Boolean, fLevel: Float, rLevel: Float) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(46.dp)) {
        val w = maxWidth
        @Composable fun label(frac: Float, title: String, value: String, on: Boolean) {
            Column(Modifier.offset(x = w * frac - 46.dp).width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                T(title, 14, color = C.InkMuted, maxLines = 1)
                T(value, 14, FontWeight.Medium, if (on) C.Ink else C.InkMuted, maxLines = 1)
            }
        }
        label(0.085f, "Engine", if (engineOn) "Running" else "Off", engineOn)
        label(0.215f, "Front motor", torqueText(front), fLevel > 0f)
        label(0.49f, "Battery", car.n("vehicle.batteryPct").fmt(0, " %"), true)
        label(0.79f, "Rear motor", torqueText(rear), rLevel > 0f)
    }
}

private fun torqueText(t: Double?): String = when {
    t == null -> "--"
    t < -5.0 -> "Regen %.0f N·m".format(-t)
    t < 5.0 -> "Idle"
    else -> "%.0f N·m".format(t)
}

/** Where you are: compass, heading, altitude, position and a topo map. */
@Composable
private fun PositionCard(modifier: Modifier) {
    val c = LocalContext.current.container
    val motion by c.motion.motion.collectAsState()
    val heading = motion.heading
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DCard(Modifier.fillMaxWidth().height(150.dp), padding = 14.dp, spacing = 0.dp) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Compass(heading, Modifier.size(110.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (heading != null) T(cardinal(heading) + " " + heading.fmt(0, "°"), 24, FontWeight.Medium)
                    else T("Heading shows once you're moving", 14, color = C.InkMuted, maxLines = 2)
                    T("Altitude ${motion.altitudeM.fmt(0, " m")}", 18, FontWeight.Medium)
                    val lat = motion.lat; val lon = motion.lon
                    if (lat != null && lon != null) T("%.4f, %.4f".format(lat, lon), 14, color = C.InkMuted, maxLines = 1)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(Radius4).border(1.dp, C.Line, Radius4)) {
            com.sharkhome.launcher.ui.components.OsmMap(Modifier.fillMaxSize(), zoom = 13.0, style = com.sharkhome.launcher.ui.components.MapStyle.TOPO)
        }
    }
}

/** Speed, gear, power, pedals and steering in one band: the things you glance at while driving. */
@Composable
private fun LiveStrip(car: Car, gpsKmh: Float?) {
    val speed = car.n("vehicle.speedKmh")?.takeIf { it > 0.0 } ?: gpsKmh?.toDouble() ?: car.n("vehicle.speedKmh")
    val accel = car.n("pedal.accelerator") ?: 0.0
    val brake = car.n("pedal.brake") ?: 0.0
    val accent = LocalAccent.current
    DCard(Modifier.fillMaxWidth(), padding = 20.dp, spacing = 0.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T("Speed", 15, color = C.InkMuted)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(speed?.let { "%.0f".format(it.coerceAtLeast(0.0)) } ?: "--", 56, FontWeight.Light, lineHeight = 60)
                    T("km/h", 18, color = C.InkMuted, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T("Gear", 15, color = C.InkMuted)
                T(car.label("vehicle.gear") ?: "--", 40, FontWeight.Medium, lineHeight = 46)
                T(car.label("vehicle.powerMode") ?: "", 15, color = C.InkMuted)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T("Power", 15, color = C.InkMuted)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    T(car.n("vehicle.powerKw").fmt(0), 40, FontWeight.Light, lineHeight = 46)
                    T("kW", 18, color = C.InkMuted, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    T("Throttle", 15, color = C.InkMuted, modifier = Modifier.weight(1f))
                    T("${accel.toInt()}%", 15, FontWeight.Medium)
                }
                ThinBar((accel / 100.0).toFloat().coerceIn(0f, 1f), color = accent, height = 10.dp)
                Row(Modifier.fillMaxWidth()) {
                    T("Brake", 15, color = C.InkMuted, modifier = Modifier.weight(1f))
                    T("${brake.toInt()}%", 15, FontWeight.Medium, color = if (brake > 1.0) C.Alert else C.Ink)
                }
                ThinBar((brake / 100.0).toFloat().coerceIn(0f, 1f), color = C.Alert, height = 10.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T("Steering wheel", 15, color = C.InkMuted)
                T(car.n("chassis.steeringAngle").fmt(0, "°"), 40, FontWeight.Light, lineHeight = 46)
            }
        }
    }
}

/**
 * ESC on/off, the same control as BYD's own quick-settings tile (owner-authorised 14 Nov 2026). Turning it OFF needs the
 * car at 10 km/h or below and a second tap within 4 s; turning it back ON is one tap, at any speed. It is never
 * remembered or restored at start-up.
 */
@Composable
private fun EscTile(car: Car, modifier: Modifier) {
    val ctx = LocalContext.current
    val off = car.label("chassis.stability") == "off"
    var armed by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(armed) { if (armed) { kotlinx.coroutines.delay(4000); armed = false } }
    Column(
        modifier.clip(Radius4).background(if (off) C.Alert else C.P.btnLightest).border(1.dp, if (off) C.Alert else C.Line, Radius4)
            .clickable {
                if (off) {
                    car.steps { it.writeLabel("chassis.stability", "on") }
                    armed = false
                } else if (!armed) {
                    val kmh = car.n("vehicle.speedKmh")
                    if (kmh == null || kmh > VehicleRepository.ESC_OFF_MAX_KMH) {
                        Toast.makeText(ctx, "ESC can only be switched off below ${VehicleRepository.ESC_OFF_MAX_KMH.toInt()} km/h", Toast.LENGTH_LONG).show()
                    } else armed = true
                } else {
                    armed = false
                    car.steps { it.writeLabel("chassis.stability", "off") }
                }
            }.padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        T(if (off) "ESC OFF" else "ESC ON", 20, FontWeight.SemiBold, if (off) Color.White else C.Ink)
        T(if (off) "Tap to turn ESC back on" else if (armed) "Tap again to turn ESC OFF" else "Tap to turn off", 15, color = if (off) Color.White else C.InkMuted, maxLines = 2)
    }
}

// ------------------------------------------------------------------------------------------------- Towing

private val TOW_CHECKS = listOf(
    "Hitch coupled and safety chains secured",
    "Trailer plug connected and lights working",
    "Trailer brakes (if fitted) and tyre pressures checked",
    "Mirrors adjusted for the trailer",
    "Indicators and brake lamps checked",
)

@Composable
private fun TowBody(car: Car) {
    val ctx = LocalContext.current
    val c = ctx.container
    prefsTick()
    val trailerOn = car.on("trailer.mode")
    val trailerSize = car.label("trailer.size")
    val brakeFeel = car.label("chassis.brakeFeel")
    val steerFeel = car.label("chassis.steerFeel")
    val regen = car.label("drive.regenLevel")
    val checks = c.prefs.towChecklist
    fun isChecked(i: Int) = (checks shr i) and 1 == 1

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Trailer mode restricts driver-assistance and safety functions, and BYD makes you confirm that on its own
            // screen (Vehicle > Towing Mode), so SharkOS-CE shows the state and opens that screen instead of switching it.
            DCard(Modifier.fillMaxWidth(), padding = 22.dp, spacing = 16.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        T("Trailer mode", 24, FontWeight.SemiBold)
                        T(if (trailerOn) "On" else "Off", 16, color = if (trailerOn) C.Ok else C.InkMuted)
                    }
                    DButton(
                        text = "Open BYD towing settings",
                        onClick = { c.apps.launchPackage("com.byd.carsettings", "BYD settings") },
                        style = BtnStyle.Primary,
                        minWidth = 320.dp,
                    )
                }
                T("BYD asks you to confirm trailer mode because it limits acceleration and some driver-assistance functions. Switch it on in Vehicle > Towing Mode.", 15, color = C.InkMuted, maxLines = 2)
                Hairline()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        T("Trailer weight class (set in BYD towing settings)", 16, FontWeight.Medium, C.InkSecondary, modifier = Modifier.weight(1f))
                        T(if (trailerOn) trailerSize ?: "--" else "Off", 16, color = C.InkMuted)
                    }
                    val sizes = listOf("Small", "Medium", "Large")
                    val ranges = listOf("0–749 kg", "750–1499 kg", "1500–2500 kg")
                    LineSegmented(
                        options = sizes.mapIndexed { i, s -> "$s · ${ranges[i]}" },
                        selected = sizes.indexOf(trailerSize).takeIf { it >= 0 },
                        onSelect = { Toast.makeText(ctx, "Set the weight class in BYD towing settings", Toast.LENGTH_SHORT).show() },
                        height = 60.dp,
                        textSize = 18,
                    )
                }
            }

            DCard(Modifier.fillMaxWidth().weight(1f), padding = 22.dp, spacing = 14.dp) {
                T("Handling", 22, FontWeight.SemiBold)
                val feel = listOf("Comfort", "Sport")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    T("Brake pedal feel", 18, color = C.InkSecondary, modifier = Modifier.width(210.dp))
                    LineSegmented(feel, feel.indexOf(brakeFeel).takeIf { it >= 0 }, { car.setLabel("chassis.brakeFeel", feel[it]) }, Modifier.weight(1f), height = 56.dp)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    T("Steering assist", 18, color = C.InkSecondary, modifier = Modifier.width(210.dp))
                    LineSegmented(feel, feel.indexOf(steerFeel).takeIf { it >= 0 }, { car.setLabel("chassis.steerFeel", feel[it]) }, Modifier.weight(1f), height = 56.dp)
                }
                val regenOpts = listOf("Standard", "High")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    T("Regen braking", 18, color = C.InkSecondary, modifier = Modifier.width(210.dp))
                    LineSegmented(regenOpts, regenOpts.indexOf(regen).takeIf { it >= 0 }, { car.setLabel("drive.regenLevel", regenOpts[it]) }, Modifier.weight(1f), height = 56.dp)
                }
                Box(Modifier.weight(1f))
                DButton("Set Sport brake + Sport steering + High regen", {
                    car.setLabel("chassis.brakeFeel", "Sport")
                    car.setLabel("chassis.steerFeel", "Sport")
                    car.setLabel("drive.regenLevel", "High")
                }, Modifier.fillMaxWidth(), style = BtnStyle.Primary)
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DCard(Modifier.fillMaxWidth().weight(1f), padding = 22.dp, spacing = 12.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    T("Before you set off", 22, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    val done = TOW_CHECKS.indices.count { isChecked(it) }
                    T("$done / ${TOW_CHECKS.size}", 16, color = if (done == TOW_CHECKS.size) C.Ok else C.InkMuted)
                }
                TOW_CHECKS.forEachIndexed { idx, text ->
                    val checked = isChecked(idx)
                    Row(
                        Modifier.fillMaxWidth().weight(1f).clip(Radius4)
                            .background(if (checked) LocalAccent.current.copy(alpha = 0.14f) else C.P.btnLightest)
                            .clickable { c.prefs.towChecklist = checks xor (1 shl idx) }
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(Radius4)
                                .background(if (checked) LocalAccent.current else C.SurfacePanel)
                                .border(1.dp, if (checked) LocalAccent.current else C.LineStrong, Radius4),
                            contentAlignment = Alignment.Center,
                        ) { if (checked) Icon(Icons.Rounded.Check, null, tint = C.OnAccent, modifier = Modifier.size(22.dp)) }
                        T(text, 18, if (checked) FontWeight.Medium else FontWeight.Normal, color = C.Ink)
                    }
                }
                DButton("Clear ticks", { c.prefs.towChecklist = 0 }, Modifier.fillMaxWidth(), height = 52.dp)
            }
            DCard(Modifier.fillMaxWidth(), padding = 22.dp, spacing = 14.dp) {
                T("While towing", 20, FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    LabelValue("Trailer trip", car.n("trailer.mileage").fmt(0, " km"), Modifier.weight(1f), valueSize = 24, labelSize = 14)
                    LabelValue("12 V battery", car.n("battery.voltage").fmt(1, " V"), Modifier.weight(1f), valueSize = 24, labelSize = 14)
                    LabelValue("Rear left", car.n("tyre.rl").fmt(0, " psi"), Modifier.weight(1f), valueSize = 24, labelSize = 14)
                    LabelValue("Rear right", car.n("tyre.rr").fmt(0, " psi"), Modifier.weight(1f), valueSize = 24, labelSize = 14)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------- Bits

private fun cardinal(deg: Float): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[(((deg % 360) + 360) % 360 / 45f + 0.5f).toInt() % 8]
}

@Composable
private fun TiltDial(label: String, angle: Float?, modifier: Modifier, caution: Float, limit: Float, art: @Composable (Modifier) -> Unit) {
    val tone = tiltColor(angle, caution, limit)
    Column(
        modifier.clip(Radius4).background(C.P.btnLightest).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        T(label, 18, color = C.InkSecondary)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height * 0.72f
                drawLine(C.Raised, Offset(0f, y), Offset(size.width, y), 2f)
            }
            art(Modifier.fillMaxWidth(0.8f).height(140.dp).rotate(-(angle ?: 0f)))
        }
        T(angle.fmt(0, "°"), 48, FontWeight.Light, tone)
    }
}

@Composable
private fun Compass(heading: Float?, modifier: Modifier) {
    val accent = LocalAccent.current
    Canvas(modifier) {
        val r = size.minDimension / 2 - 4f
        val c = center
        drawCircle(C.Raised, r, c, style = Stroke(3f))
        // The dial turns so the car's heading is always at the top.
        rotate(-(heading ?: 0f), c) {
            for (i in 0 until 36) {
                val a = Math.toRadians(i * 10.0 - 90)
                val inner = if (i % 9 == 0) r - 22f else r - 10f
                drawLine(
                    if (i == 0) C.Red else C.Text2,
                    Offset(c.x + (inner * cos(a)).toFloat(), c.y + (inner * sin(a)).toFloat()),
                    Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat()),
                    if (i % 9 == 0) 4f else 2f,
                )
            }
        }
        drawLine(accent, Offset(c.x, c.y + r * 0.45f), Offset(c.x, c.y - r * 0.6f), 6f)
        drawCircle(accent, 8f, c)
    }
}
