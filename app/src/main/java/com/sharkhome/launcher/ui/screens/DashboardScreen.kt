package com.sharkhome.launcher.ui.screens

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt
import com.sharkhome.launcher.Screen
import com.sharkhome.launcher.NavLauncher
import com.sharkhome.launcher.container
import com.sharkhome.launcher.media.AaNavInfo
import com.sharkhome.launcher.media.NowPlaying
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent
import com.sharkhome.launcher.ui.components.Car
import com.sharkhome.launcher.ui.components.CarImage
import com.sharkhome.launcher.ui.components.CarView
import com.sharkhome.launcher.ui.components.ChevronButton
import com.sharkhome.launcher.ui.components.DButton
import com.sharkhome.launcher.ui.components.DCard
import com.sharkhome.launcher.ui.components.DsIcon
import com.sharkhome.launcher.ui.components.Eyebrow
import com.sharkhome.launcher.ui.components.FanBar
import com.sharkhome.launcher.ui.components.Hairline
import com.sharkhome.launcher.ui.components.Ic
import com.sharkhome.launcher.ui.components.LabelValue
import com.sharkhome.launcher.ui.components.LineSegmented
import com.sharkhome.launcher.ui.components.MediaAccessPrompt
import com.sharkhome.launcher.ui.components.PillSwitch
import com.sharkhome.launcher.ui.components.Radius4
import com.sharkhome.launcher.ui.components.SmallButton
import com.sharkhome.launcher.ui.components.T
import com.sharkhome.launcher.ui.components.ThinBar
import com.sharkhome.launcher.ui.components.TileSpec
import com.sharkhome.launcher.ui.components.VHairline
import com.sharkhome.launcher.ui.components.VSlider
import com.sharkhome.launcher.ui.components.fmt
import com.sharkhome.launcher.ui.components.prefsTick
import com.sharkhome.launcher.ui.components.rememberCar
import com.sharkhome.launcher.ui.components.seatSides
import kotlinx.coroutines.delay

/**
 * Home, laid out like the "Home, parked" and "Home, driving" artboards: vehicle + map on the left,
 * climate + favourite apps in the middle, media on the right. The vehicle card switches to the
 * driving view (speed, power bar, brake lights) when the car is in D or R, or moving.
 */
@Composable
fun DashboardScreen(onNavigate: (Screen) -> Unit) {
    val car = rememberCar()
    val c = LocalContext.current.container
    val motion by c.motion.motion.collectAsState()
    prefsTick()
    val speed = if (c.prefs.useGpsSpeed) {
        motion.speedKmh?.toDouble() ?: car.n("vehicle.speedKmh")
    } else {
        car.n("vehicle.speedKmh")?.takeIf { it > 0.0 } ?: motion.speedKmh?.toDouble() ?: car.n("vehicle.speedKmh")
    }
    val gear = car.label("vehicle.gear")
    val driving = gear == "D" || gear == "R" || (speed ?: 0.0) > 3.0

    // The home screen is a set of widgets the driver can add, remove, move and resize (HomeWidgets.kt).
    HomeLayout(car, speed, driving, onNavigate)
}

@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(5_000)
        }
    }
    return now
}

// ---------------------------------------------------------------- vehicle (parked)

private fun Car.combinedRange(): Double? = totalKm()

private fun Car.parkedLine(): String {
    val g = label("vehicle.gear")
    val state = when (g) { null, "P" -> "Parked"; "N" -> "Neutral"; else -> "Gear $g" }
    return when (label("body.locked")) { "locked" -> "$state · Locked"; "unlocked" -> "$state · Unlocked"; else -> state }
}

private val DOORS = listOf(
    "door.driver" to "Driver door", "door.passenger" to "Passenger door", "door.rearLeft" to "Rear left door",
    "door.rearRight" to "Rear right door", "door.tailgate" to "Tailgate", "door.bonnet" to "Bonnet",
)

private fun Car.openDoors(): List<String> = DOORS.filter { label(it.first) == "open" }.map { it.second }

/** Short drive-energy mode for the badge: EV / HEV, or the raw code until it's known. */
private fun Car.modeBadge(): String? = when (val l = label("vehicle.powerMode")) {
    // An unknown code ("Mode 3") isn't useful to the driver and isn't in the design: show nothing until it's known.
    null -> null
    "EV priority", "EV" -> "EV"
    "Hybrid", "HEV" -> "HEV"
    else -> l
}

private fun minutesText(min: Int): String = when {
    min >= 60 -> "${min / 60} h ${min % 60} m"
    else -> "$min min"
}

@Composable
internal fun VehicleCard(car: Car, modifier: Modifier = Modifier.fillMaxWidth(), hidden: Set<String> = emptySet(), onOpen: () -> Unit) {
    val chargeLeft by LocalContext.current.container.vehicle.chargeMinutesLeft.collectAsState()
    DCard(modifier, onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            T("Shark 6", 24, FontWeight.SemiBold, spacing = 0.01f)
            car.modeBadge()?.let { mode ->
                Box(Modifier.clip(Radius4).background(C.SurfaceRaised).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    T(mode, 14, FontWeight.Medium)
                }
            }
            Box(Modifier.weight(1f))
            T(car.parkedLine(), 16, color = C.InkMuted)
        }
        val open = car.openDoors()
        if (open.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(C.Alert))
                T(open.joinToString(", ") + " open", 16, FontWeight.Medium, C.Alert, maxLines = 2)
            }
        }
        // Big numbers first, then the detail rows (owner wanted the detail kept, 8 Nov). The picture takes what's left.
        if ("image" !in hidden) CarImage(Modifier.fillMaxWidth().weight(1f)) else Box(Modifier.weight(0.01f))
        if ("energy" !in hidden) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            EnergyColumn("Battery", car.n("vehicle.batteryPct"), modifier = Modifier.weight(1f))
            EnergyColumn("Fuel", car.n("vehicle.fuelPct"), modifier = Modifier.weight(1f))
        }
        if ("range" !in hidden) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            T("Real range", 18, color = C.InkMuted, modifier = Modifier.weight(1f).padding(bottom = 10.dp))
            T(car.combinedRange().fmt(0), 48, FontWeight.Light, lineHeight = 54)
            T(" km", 20, color = C.InkMuted, modifier = Modifier.padding(bottom = 8.dp))
        }
        if ("power" !in hidden) PowerBar(car)
        if ("odometer" !in hidden) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("Odometer", 18, color = C.InkMuted, modifier = Modifier.weight(1f))
            T(car.n("vehicle.odometerKm")?.let { "%,d km".format(it.toLong()) } ?: "—", 20, FontWeight.Medium)
        }
        if ("averages" !in hidden) AverageRow(car)
        val rawChargeKw = car.n("vehicle.chargingPower")
        val dischargeKw = car.n("vehicle.dischargePower")
        val gunPlugged = (car.n("vehicle.chargingGun") ?: 0.0) > 0.0
        // BYD's body.getChargingPower can report a bogus sentinel value (e.g. 359.4) while unplugged;
        // only trust charging power when the gun is connected and the reading is plausible (< 200 kW).
        val chargeKw = rawChargeKw?.takeIf { gunPlugged && it in 0.05..200.0 }
        val isDischarging = gunPlugged && chargeKw == null && dischargeKw != null && dischargeKw in 5.0..20.0
        val isCharging = gunPlugged && !isDischarging

        if ("charging" in hidden) {
            // switched off in Options
        } else if (isCharging || isDischarging) {
            Row(
                Modifier.fillMaxWidth().clip(Radius4).background(C.P.btnLightest).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DsIcon(Ic.BatteryCharging, if (isCharging) C.Ok else LocalAccent.current, 24.dp)
                Column(Modifier.weight(1f)) {
                    T(when { isCharging && chargeKw != null -> "Charging"; isCharging -> "Plugged in · not charging"; else -> "V2L discharge active" }, 16, FontWeight.SemiBold)
                    val detail = when {
                        isCharging && chargeLeft != null -> "Full in ${minutesText(chargeLeft!!)}" +
                            (if (chargeKw != null) " · ${chargeKw.fmt(1, " kW")}" else "")
                        isCharging && chargeKw != null -> "${chargeKw.fmt(1, " kW")} · Cable connected"
                        isCharging -> if ((car.n("vehicle.batteryPct") ?: 0.0) >= 99.0) "Battery full" else "Cable connected · waiting"
                        else -> "${dischargeKw!!.fmt(2, " kW")} output"
                    }
                    if (detail.isNotBlank()) T(detail, 14, color = C.InkMuted)
                }
                if (isCharging && chargeKw != null) {
                    T("+${chargeKw.fmt(1, " kW")}", 20, FontWeight.SemiBold, color = C.Ok)
                } else if (isDischarging) {
                    T("-${dischargeKw.fmt(2, " kW")}", 20, FontWeight.SemiBold, color = LocalAccent.current)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabelValue("Charging port", if (gunPlugged) "Connected" else "Unplugged", Modifier.weight(1f), valueSize = 20, labelSize = 16)
                LabelValue("V2L", "Standby", Modifier.weight(1f), valueSize = 20, labelSize = 16)
            }
        }
        if ("tyres" !in hidden) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Front left" to "tyre.fl", "Front right" to "tyre.fr", "Rear left" to "tyre.rl", "Rear right" to "tyre.rr").forEach { (l, k) ->
                LabelValue(l, car.n(k).fmt(0, " psi"), Modifier.weight(1f), valueSize = 20, labelSize = 16)
            }
        }
    }
}

@Composable
private fun EnergyColumn(label: String, pct: Double?, sub: String? = null, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T(label, 18, color = C.InkMuted)
        T(pct.fmt(0, "%"), 56, FontWeight.Light, lineHeight = 62)
        ThinBar(pct?.let { (it / 100).toFloat() })
        if (!sub.isNullOrBlank()) T(sub, 18, color = C.InkSecondary)
    }
}

@Composable
private fun AverageRow(car: Car) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        LabelValue("Avg fuel", car.n("vehicle.fuelAvg").fmt(1, " L/100 km"), Modifier.weight(1f), valueSize = 20, labelSize = 16)
        LabelValue("Avg electricity", car.n("vehicle.consumption").fmt(1, " kWh/100 km"), Modifier.weight(1f), valueSize = 20, labelSize = 16)
    }
}

// ---------------------------------------------------------------- vehicle (driving)

private const val KW_MIN = -60.0
private const val KW_MAX = 180.0

@Composable
internal fun DrivingCard(car: Car, speed: Double?, modifier: Modifier = Modifier.fillMaxWidth()) {
    val kw = car.n("vehicle.powerKw")
    DCard(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                T(speed.fmt(0), 150, FontWeight.Light, spacing = -0.02f, lineHeight = 150)
                T("km/h", 24, FontWeight.Medium, C.InkMuted, modifier = Modifier.padding(bottom = 22.dp))
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                T("Real range", 14, color = C.InkMuted)
                T(car.combinedRange().fmt(0, " km"), 24, FontWeight.SemiBold)
            }
        }
        PowerBar(car)
        CarImage(Modifier.fillMaxWidth().height(180.dp), CarView.REAR)
        Hairline()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LabelValue("Drive mode", car.readable("drive.roadMode"), Modifier.weight(1f))
            LabelValue("Power", car.readable("vehicle.powerMode"), Modifier.weight(1f))
            LabelValue("Gear", car.label("vehicle.gear") ?: "—", Modifier.weight(1f))
            LabelValue("Hill Descent", if (car.available("drive.hillDescent")) (if (car.on("drive.hillDescent")) "On" else "Off") else "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BarStat("Battery", car.n("vehicle.batteryPct"), Modifier.weight(1f))
            BarStat("Fuel", car.n("vehicle.fuelPct"), Modifier.weight(1f))
        }
        AverageRow(car)
    }
}

/**
 * Power bar, made to be seen: full width, 40 px tall, springs to the reading. Drive power fills right of the zero
 * mark in the accent colour with a glow; regen fills left in green. The kW reading is big. When the car reports no
 * power value, the bar follows the accelerator pedal instead (labelled "Pedal").
 */
@Composable
internal fun PowerBar(car: Car, modifier: Modifier = Modifier) {
    val kw = car.n("vehicle.powerKw")
    val pedal = car.n("pedal.accelerator")
    val usePedal = kw == null && pedal != null
    // Plugged in and taking charge: the car reports the charger's power as negative drive power (read -6 kW at a
    // 5.9 kW charge, 13 Nov). That is charging, not regen, so say so and show the charger's own figure.
    val gunPlugged = (car.n("vehicle.chargingGun") ?: 0.0) > 0.0
    val chargeKw = car.n("vehicle.chargingPower")?.takeIf {
        gunPlugged && it in 0.05..200.0 && (car.n("vehicle.speedKmh") ?: 0.0) < 1.0 && (kw ?: 0.0) <= 0.5
    }
    val charging = chargeKw != null
    // Fraction of the bar: -1 (full regen) .. 0 (zero mark) .. 1 (full drive power).
    val target = when {
        kw != null -> if (kw < 0) (kw / -KW_MIN).toFloat().coerceIn(-1f, 0f) else (kw / KW_MAX).toFloat().coerceIn(0f, 1f)
        pedal != null -> (pedal / 100.0).toFloat().coerceIn(0f, 1f)
        else -> 0f
    }
    val f by androidx.compose.animation.core.animateFloatAsState(
        target,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
        label = "power",
    )
    val accent = LocalAccent.current
    val drive = accent
    val regen = C.Ok
    val zeroAt = (-KW_MIN / (KW_MAX - KW_MIN)).toFloat() // 0.25: a quarter of the bar is regen
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            T(if (charging) "Charging" else if (usePedal) "Pedal" else if (f < -0.01f) "Regen" else "Power", 18, color = if (charging || f < -0.01f) regen else C.InkMuted,
                modifier = Modifier.weight(1f).padding(bottom = 10.dp))
            T(
                if (charging) "+%.1f".format(chargeKw) else if (usePedal) pedal.fmt(0) else kw?.let { "%.0f".format(it) } ?: "--",
                64, FontWeight.Light, if (charging || f < -0.01f) regen else C.Ink, lineHeight = 70,
            )
            T(if (usePedal) " %" else " kW", 20, color = C.InkMuted, modifier = Modifier.padding(bottom = 10.dp))
        }
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val w = size.width
            val h = size.height
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
            val z = if (usePedal) 0f else w * zeroAt
            drawRoundRect(C.Raised, cornerRadius = r)
            if (f > 0.001f) {
                val end = z + (w - z) * f
                // Glow: a soft, wider halo behind the fill that grows with power.
                drawRoundRect(drive.copy(alpha = 0.18f + 0.25f * f), topLeft = androidx.compose.ui.geometry.Offset(z, -6f),
                    size = androidx.compose.ui.geometry.Size(end - z, h + 12f), cornerRadius = r)
                drawRoundRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(drive.copy(alpha = 0.75f), drive, Color.White.copy(alpha = 0.9f)), startX = z, endX = end),
                    topLeft = androidx.compose.ui.geometry.Offset(z, 0f), size = androidx.compose.ui.geometry.Size(end - z, h), cornerRadius = r,
                )
            } else if (f < -0.001f) {
                val start = z + z * f
                drawRoundRect(regen.copy(alpha = 0.25f), topLeft = androidx.compose.ui.geometry.Offset(start, -6f),
                    size = androidx.compose.ui.geometry.Size(z - start, h + 12f), cornerRadius = r)
                drawRoundRect(regen, topLeft = androidx.compose.ui.geometry.Offset(start, 0f), size = androidx.compose.ui.geometry.Size(z - start, h), cornerRadius = r)
            }
            if (!usePedal) drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(z - 2f, -4f), size = androidx.compose.ui.geometry.Size(4f, h + 8f))
        }
        if (!usePedal) Row(Modifier.fillMaxWidth()) {
            T(if (charging) "Charge" else "Regen", 15, color = C.InkMuted, modifier = Modifier.weight(zeroAt))
            T("0", 15, color = C.InkMuted, modifier = Modifier.weight(1f - zeroAt))
            T("${KW_MAX.toInt()} kW", 15, color = C.InkMuted)
        }
    }
}
@Composable
private fun BarStat(label: String, pct: Double?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            T(label, 16, color = C.InkMuted, modifier = Modifier.weight(1f))
            T(pct.fmt(0, "%"), 16, FontWeight.Medium)
        }
        ThinBar(pct?.let { (it / 100).toFloat() })
    }
}

// ---------------------------------------------------------------- map

@Composable
internal fun MapCard(car: Car, speed: Double?, modifier: Modifier, hidden: Set<String> = emptySet()) {
    val ctx = LocalContext.current
    val c = ctx.container
    prefsTick()
    val aa by c.media.androidAutoConnected.collectAsState()
    val aaNavApp by c.media.aaNavAppActive.collectAsState()
    val aaNavStatus by c.media.aaNavStatus.collectAsState()
    val aaNavInfo by c.media.aaNavInfo.collectAsState()
    val aaServiceReady by c.media.aaServiceReady.collectAsState()
    val aaProjectionError by c.media.aaProjectionError.collectAsState()
    val aaVideoFocus by c.media.aaVideoFocus.collectAsState()
    val editing by HomeEdit.editing.collectAsState()
    val overlayOpen by HomeEdit.overlayOpen.collectAsState()
    val nav = c.nav

    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var lastResumedAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isResumed = true
                    lastResumedAt = android.os.SystemClock.elapsedRealtime()
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    isResumed = false
                    c.media.detachProjectionSurface()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            c.media.detachProjectionSurface()
        }
    }

    val aaNavActive = aa && (aaNavApp || aaNavStatus == 1 || aaNavStatus == 3 || aaNavInfo != null)
    val mapSource = c.prefs.mapSource
    val aaProjectionMode = c.prefs.aaProjectionMode
    val aaMapWanted = when (mapSource) {
        "osm" -> false
        "androidauto" -> aa
        else -> aaNavActive
    }
    val useCluster = aaProjectionMode == "cluster"
    val wantVideoSurface = isResumed &&
        aaMapWanted &&
        aaProjectionMode != "tbt" &&
        aaServiceReady &&
        aaProjectionError == null &&
        !editing &&
        !overlayOpen

    LaunchedEffect(wantVideoSurface) {
        if (!wantVideoSurface) {
            c.media.detachProjectionSurface()
        }
    }

    fun open(split: Boolean, bydType: Int = NavLauncher.BYD_MAP) {
        val preferAa = aa && mapSource != "osm"
        if (preferAa) {
            // Release our widget Surface synchronously so full-screen AapActivity can reclaim the video stream.
            c.media.detachProjectionSurface()
        }
        // BYD replaced Android's split screen with its own (TsSplitPolicy); apps can't start it and Navigation is
        // non-resizable, so "split" opens the map full screen and points at the dock's split button, which then
        // offers SharkOS-CE for the other half (checked on the car 2026-11-07).
        val ok = if (preferAa) nav.openAndroidAuto() else nav.openBydNav(bydType)
        if (!ok) {
            Toast.makeText(ctx, if (preferAa) "Couldn't open Android Auto" else "Couldn't open BYD Navigation", Toast.LENGTH_SHORT).show()
            nav.openNavApp()
        } else if (split) {
            Toast.makeText(ctx, "Tap the split-screen button in the dock (two rectangles), then pick SharkOS-CE", Toast.LENGTH_LONG).show()
        }
    }

    val showSpeedo = "speedo" !in hidden && wantVideoSurface && (aaNavActive || useCluster)
    val tsrLimit = car.n("vehicle.speedLimitKmh")?.roundToInt()?.takeIf { it in 10..200 }

    DCard(modifier, padding = 0.dp, spacing = 0.dp) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(C.P.mapGround)) {
            if (wantVideoSurface) {
                AaVideoSurface(
                    modifier = Modifier.fillMaxSize(),
                    useCluster = useCluster,
                    isResumed = isResumed,
                    lastResumedAt = lastResumedAt,
                    videoFocus = aaVideoFocus,
                    onAttach = { surface, w, h, cluster, force ->
                        c.media.attachProjectionSurface(surface, w, h, useCluster = cluster, requestNaviUi = false, force = force)
                    },
                    onDetach = { surface -> c.media.detachProjectionSurface(surface) },
                    onTouch = { ev, w, h -> c.media.sendProjectionTouch(ev, w, h) },
                    onTapCluster = { open(split = false) },
                )
            } else {
                com.sharkhome.launcher.ui.components.VectorMap(Modifier.fillMaxSize(), zoom = 14.0)
                Box(Modifier.fillMaxSize().clickable { open(split = false) })
                if (aa && (aaMapWanted || aaNavInfo != null)) {
                    AaTurnByTurnOverlay(
                        info = aaNavInfo,
                        status = aaNavStatus,
                        fallbackReason = when {
                            aaProjectionMode == "tbt" -> null
                            aaProjectionError != null -> "AA video unavailable ($aaProjectionError)"
                            !aaServiceReady -> "AA video service connecting…"
                            else -> null
                        },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = if (showSpeedo) 98.dp else 12.dp, bottom = 12.dp, end = 12.dp),
                    )
                }
            }

            if (showSpeedo) {
                WazeSpeedo(
                    speed = speed,
                    speedLimit = tsrLimit,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp),
                )
            }

            Row(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (aa) {
                    Row(
                        Modifier.clip(Radius4).background(C.SurfacePanel.copy(alpha = 0.88f))
                            .clickable {
                                c.media.clearProjectionError()
                                c.prefs.mapSource = when {
                                    aaMapWanted -> "osm"
                                    mapSource == "osm" -> "auto"
                                    else -> "androidauto"
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (aaMapWanted) C.Ok else C.InkMuted))
                        T(
                            when {
                                !aaMapWanted -> "Local Map"
                                wantVideoSurface && useCluster -> "AA Map"
                                wantVideoSurface -> "AA Live"
                                else -> "AA TBT"
                            },
                            13,
                            FontWeight.Medium,
                        )
                    }
                }
                Row(
                    Modifier.clip(Radius4).background(C.SurfacePanel.copy(alpha = 0.88f))
                        .clickable { open(split = false) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!aa) Box(Modifier.size(8.dp).clip(CircleShape).background(LocalAccent.current))
                    T(if (aa && mapSource != "osm") "Full" else "BYD Navigation", 14, FontWeight.Medium)
                }
                Row(
                    Modifier.clip(Radius4).background(C.SurfacePanel.copy(alpha = 0.88f))
                        .clickable { open(split = true) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DsIcon(Ic.SplitScreen, C.Ink, 14.dp)
                    T("Split", 14, FontWeight.Medium)
                }
            }
        }
        if ("search" !in hidden) Hairline()
        if ("search" !in hidden) {
            val navBarText = when {
                aaMapWanted && aaNavInfo != null -> {
                    val main = listOfNotNull(
                        aaNavInfo?.nextRoad?.takeIf { it.isNotEmpty() },
                        aaNavInfo?.distanceText?.takeIf { it.isNotEmpty() },
                        aaNavInfo?.etaText?.takeIf { it.isNotEmpty() },
                    ).joinToString(" · ")
                    main.ifEmpty { aaNavInfo?.destination?.ifEmpty { "Android Auto navigation" } ?: "Android Auto navigation" }
                }
                aa && mapSource != "osm" -> "Open Android Auto"
                else -> "Navigate"
            }
            Row(
                Modifier.fillMaxWidth().height(56.dp).clickable { open(split = false) }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DsIcon(Ic.Search, C.InkMuted, 24.dp)
                T(navBarText, 18, FontWeight.Medium, if (aaMapWanted && aaNavInfo != null) C.Ink else C.InkMuted, spacing = -0.01f)
            }
        }
        if ("places" !in hidden) Hairline()
        if ("places" !in hidden) Row(Modifier.fillMaxWidth().height(64.dp)) {
            val sub = if (aa && mapSource != "osm") "via Android Auto" else "Saved in BYD Navigation"
            PlaceCell(Ic.Home, "Home", sub, Modifier.weight(1f)) { open(split = false, bydType = NavLauncher.BYD_HOME) }
            VHairline()
            PlaceCell(Ic.Work, "Work", sub, Modifier.weight(1f)) { open(split = false, bydType = NavLauncher.BYD_WORK) }
        }
    }
}

@Composable
internal fun WazeSpeedo(
    speed: Double?,
    speedLimit: Int? = null,
    modifier: Modifier = Modifier,
) {
    val kmh = speed?.roundToInt()?.coerceAtLeast(0)
    val overLimit = kmh != null && speedLimit != null && kmh > speedLimit
    val nearLimit = kmh != null && speedLimit != null && !overLimit && kmh >= speedLimit - 3
    val ringColor = when {
        overLimit -> Color(0xFFFF3B30)
        nearLimit -> Color(0xFFFFB300)
        else -> Color(0xFF33CCFF)
    }
    val sweepFraction = ((kmh ?: 0).toFloat() / (speedLimit?.coerceAtLeast(60) ?: 130).toFloat()).coerceIn(0f, 1f)

    Box(modifier) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color(0xFF171A21).copy(alpha = 0.92f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                val stroke = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                drawArc(
                    color = Color.White.copy(alpha = 0.14f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = stroke,
                )
                if (sweepFraction > 0f) {
                    drawArc(
                        color = ringColor,
                        startAngle = 135f,
                        sweepAngle = 270f * sweepFraction,
                        useCenter = false,
                        style = stroke,
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((-3).dp),
            ) {
                T(
                    kmh?.toString() ?: "0",
                    24,
                    FontWeight.Bold,
                    color = if (overLimit) Color(0xFFFF5252) else Color.White,
                    lineHeight = 26,
                )
                T("km/h", 14, FontWeight.Medium, color = Color.White.copy(alpha = 0.68f))
            }
        }
        if (speedLimit != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 12.dp, y = (-4).dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(3.dp, Color(0xFFE53935), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                T(speedLimit.toString(), 14, FontWeight.ExtraBold, color = Color(0xFF111111))
            }
        }
    }
}

private fun applyAaVideoTransform(view: TextureView, width: Int, height: Int, useCluster: Boolean) {
    if (width <= 0 || height <= 0) return
    val vw = width.toFloat()
    val vh = height.toFloat()
    val videoW = 1920f
    val videoH = 1080f
    val matrix = Matrix()
    if (useCluster) {
        // AA's cluster stream embeds 180px black margins above and below the 1920x720 active map band.
        // Scale vertically by 1080/720 (1.5x) to eliminate top/bottom black bars, and crop the outer horizontal
        // padding while preserving both the vehicle cursor and Google Maps/Waze turn card.
        val sy = videoH / 720f
        val uniformSx = (videoW / 720f) * (vh / vw)
        val sx = uniformSx.coerceIn(1.0f, 1.32f)
        matrix.setScale(sx, sy, vw * 0.62f, vh * 0.5f)
    } else {
        // Full AA stream (1920x1080): center-crop to match MediaCodec SCALE_TO_FIT_WITH_CROPPING.
        val viewAspect = vw / vh
        val videoAspect = videoW / videoH
        val (sx, sy) = if (viewAspect < videoAspect) {
            (videoAspect / viewAspect) to 1f
        } else {
            1f to (viewAspect / videoAspect)
        }
        matrix.setScale(sx, sy, vw * 0.5f, vh * 0.5f)
    }
    view.setTransform(matrix)
}

@Composable
private fun AaVideoSurface(
    modifier: Modifier,
    useCluster: Boolean,
    isResumed: Boolean,
    lastResumedAt: Long,
    videoFocus: Int,
    onAttach: (Surface, Int, Int, Boolean, Boolean) -> Boolean,
    onDetach: (Surface?) -> Unit,
    onTouch: (MotionEvent, Int, Int) -> Unit,
    onTapCluster: () -> Unit,
) {
    val currentUseCluster by rememberUpdatedState(useCluster)
    val currentIsResumed by rememberUpdatedState(isResumed)
    val currentOnAttach by rememberUpdatedState(onAttach)
    val currentOnDetach by rememberUpdatedState(onDetach)
    val currentOnTouch by rememberUpdatedState(onTouch)
    val currentOnTapCluster by rememberUpdatedState(onTapCluster)

    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var activeSurface by remember { mutableStateOf<Surface?>(null) }
    var surfaceSize by remember { mutableStateOf(0 to 0) }
    var focusRecoveryUsed by remember(lastResumedAt) { mutableStateOf(false) }

    LaunchedEffect(isResumed, useCluster, activeSurface, surfaceSize) {
        val tv = textureViewRef
        val s = activeSurface
        val (w, h) = surfaceSize
        if (tv != null && w > 0 && h > 0) {
            applyAaVideoTransform(tv, w, h, useCluster)
        }
        if (isResumed && s != null && s.isValid && w > 0 && h > 0) {
            currentOnAttach(s, w, h, useCluster, false)
        }
    }

    // Returning from AapActivity: AapActivity.onStop() -> hideSurface() can call setSurface(null, 0, 0)
    // ~200-400ms after MainActivity.onResume() attached our widget Surface. Recover once if focus drops right after resume.
    LaunchedEffect(isResumed, lastResumedAt, videoFocus, useCluster, activeSurface, surfaceSize) {
        val s = activeSurface
        val (w, h) = surfaceSize
        if (isResumed && !useCluster && !focusRecoveryUsed && videoFocus == 0 && s != null && s.isValid && w > 0 && h > 0) {
            val sinceResume = android.os.SystemClock.elapsedRealtime() - lastResumedAt
            if (lastResumedAt > 0L && sinceResume < 3000L) {
                delay(150L)
                if (currentIsResumed && s.isValid) {
                    focusRecoveryUsed = true
                    currentOnAttach(s, w, h, false, true)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val s = activeSurface
            currentOnDetach(s)
            activeSurface = null
            s?.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            object : TextureView(context) {
                override fun performClick(): Boolean = super.performClick()
            }.apply {
                textureViewRef = this
                isOpaque = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        val s = Surface(st)
                        activeSurface?.release()
                        activeSurface = s
                        surfaceSize = width to height
                        applyAaVideoTransform(this@apply, width, height, currentUseCluster)
                        if (currentIsResumed && width > 0 && height > 0) {
                            currentOnAttach(s, width, height, currentUseCluster, false)
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        surfaceSize = width to height
                        applyAaVideoTransform(this@apply, width, height, currentUseCluster)
                        val s = activeSurface
                        if (currentIsResumed && s != null && s.isValid && width > 0 && height > 0) {
                            currentOnAttach(s, width, height, currentUseCluster, false)
                        }
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        val s = activeSurface
                        currentOnDetach(s)
                        if (activeSurface === s) {
                            activeSurface = null
                        }
                        s?.release()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
                setOnTouchListener { v, event ->
                    if (currentUseCluster) {
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            v.performClick()
                            currentOnTapCluster()
                        }
                        true
                    } else {
                        currentOnTouch(event, v.width, v.height)
                        true
                    }
                }
            }
        },
    )
}

@Composable
private fun AaTurnByTurnOverlay(
    info: AaNavInfo?,
    status: Int,
    fallbackReason: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(Radius4)
            .background(C.SurfacePanel.copy(alpha = 0.90f))
            .border(1.dp, C.Line, Radius4)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (info != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                info.maneuverIcon?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val primary = listOfNotNull(
                        info.distanceText.takeIf { it.isNotEmpty() },
                        info.nextRoad.takeIf { it.isNotEmpty() },
                    ).joinToString(" · ").ifEmpty {
                        if (status == 3) "Rerouting…" else "Android Auto Navigation"
                    }
                    T(primary, 15, FontWeight.SemiBold, C.Ink)
                    val secondary = listOfNotNull(
                        info.currentRoad.takeIf { it.isNotEmpty() },
                        info.etaText.takeIf { it.isNotEmpty() },
                        info.destination.takeIf { it.isNotEmpty() },
                    ).joinToString(" · ")
                    if (secondary.isNotEmpty()) {
                        T(secondary, 14, color = C.InkSecondary)
                    }
                }
            }
        } else {
            T(if (status == 3) "Android Auto · Rerouting" else "Android Auto Map Active", 14, FontWeight.SemiBold, C.Ink)
        }
        if (!fallbackReason.isNullOrBlank()) {
            T(fallbackReason, 14, color = C.InkMuted, maxLines = 2)
        }
    }
}

@Composable
private fun PlaceCell(icon: Ic, label: String, address: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DsIcon(icon, C.Ink, 24.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            T(label, 18, FontWeight.Medium)
            T(address.ifBlank { "Set in Display & themes" }, 14, color = C.InkMuted)
        }
    }
}

// ---------------------------------------------------------------- climate card

/**
 * Home climate card. It fills whatever size the widget is given: the A/C row and the seat buttons share the spare
 * height equally (so a seat button is always the same size as the A/C tile above it), capped so they don't balloon.
 * Sections can be switched off in the widget's ⚙ Options.
 */
@Composable
internal fun ClimateCard(
    car: Car,
    modifier: Modifier,
    hidden: Set<String> = emptySet(),
    onOpen: () -> Unit,
    onAddPreset: (() -> Unit)? = null,
) {
    val c = LocalContext.current.container
    val (left, right) = seatSides()
    prefsTick()
    DCard(modifier, padding = 20.dp, spacing = 14.dp) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f).clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T("Climate", 22, FontWeight.SemiBold, spacing = 0.01f, maxLines = 1)
                val temps = climateTemps(car)
                if (temps.isNotBlank()) T(temps, 14, color = C.InkMuted, maxLines = 1)
            }
            if ("buttons" !in hidden) ClimateHeaderButtons(car, height = 44.dp, textSize = 15)
        }
        if ("temps" !in hidden) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TempStepper(car, left, Modifier.weight(1f))
            TempStepper(car, right, Modifier.weight(1f))
        }
        if ("fan" !in hidden) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                T("Fan", 15, color = C.InkSecondary, modifier = Modifier.weight(1f))
                T(if (car.label("climate.power") == "off") "Off" else car.n("climate.fan").fmt(0), 15, FontWeight.Medium)
            }
            FanBar(car.n("climate.fan")?.toInt() ?: 0, 7, { car.set("climate.fan", it.toDouble()) }, height = 46.dp)
        }
        val tiles = "tiles" !in hidden
        val seats = "seats" !in hidden
        if (tiles || seats) {
            // One even 4 x 2 grid: the climate tiles and the seat buttons share the same columns, gaps and button
            // style. fill = false: each row may be shorter than its share (capped at 112); spare room goes around them.
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                if (tiles) Row(Modifier.weight(1f, fill = false).heightIn(min = 64.dp, max = 112.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    climateTiles(car).forEach { ClimateTile(it, Modifier.weight(1f)) }
                }
                if (seats) Row(Modifier.weight(1f, fill = false).heightIn(min = 64.dp, max = 112.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeatControl(car, left, Modifier.weight(1f).fillMaxHeight())
                    SeatControl(car, right, Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            Box(Modifier.weight(1f))
        }
        if ("presets" !in hidden) {
            val presets = c.prefs.presets
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (presets.size <= 3) {
                    presets.forEach { p ->
                        DButton(p.name, { car.apply(p) }, Modifier.weight(1f), height = 52.dp, textSize = 15)
                    }
                } else {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        presets.forEach { p ->
                            DButton(p.name, { car.apply(p) }, minWidth = 132.dp, height = 52.dp, textSize = 15)
                        }
                    }
                }
                if (onAddPreset != null) {
                    Box(
                        Modifier.size(52.dp).clip(Radius4).background(C.P.btnLightest)
                            .let { if (C.P.dark) it else it.border(1.dp, C.Line, Radius4) }
                            .clickable(onClickLabel = "Add climate preset", onClick = onAddPreset),
                        contentAlignment = Alignment.Center,
                    ) {
                        DsIcon(Ic.Plus, C.P.btnInk, 22.dp)
                    }
                }
            }
        }
    }
}

/** One climate switch on the home card, drawn like the seat buttons beside it. */
@Composable
private fun ClimateTile(t: TileSpec, modifier: Modifier) {
    val fg = if (t.on) Color.White else C.InkSecondary
    Column(
        modifier
            .fillMaxHeight()
            .clip(Radius4)
            .background(if (t.on) LocalAccent.current else C.P.btnLightest)
            .border(1.dp, if (t.on) LocalAccent.current else C.Line, Radius4)
            .clickable(onClickLabel = t.label, onClick = t.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        DsIcon(t.icon, fg, 28.dp)
        T(t.label, 14, if (t.on) FontWeight.Medium else FontWeight.Normal, color = fg)
    }
}

/** Wheel heat, dual-zone Sync and Auto: the same three buttons on the home card and the Climate page. */
@Composable
internal fun ClimateHeaderButtons(car: Car, height: Dp, textSize: Int) {
    val c = LocalContext.current.container
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DButton("Wheel", { car.toggle("seat.steeringWheelHeat") }, minWidth = 70.dp, height = height, textSize = textSize, selected = car.on("seat.steeringWheelHeat"))
        DButton("Sync", {
            val next = !c.prefs.climateSync
            c.prefs.climateSync = next
            car.setLabel("climate.dualSync", if (next) "on" else "off")
            if (next) car.n("climate.tempDriver")?.let { car.set("climate.tempPassenger", it) }
        }, minWidth = 68.dp, height = height, textSize = textSize, selected = c.prefs.climateSync)
        DButton("Auto", { car.toggle("climate.auto") }, minWidth = 68.dp, height = height, textSize = textSize, selected = car.climateOn("climate.auto"))
    }
}

fun climateTemps(car: Car): String = listOfNotNull(
    car.n("climate.insideTemp")?.let { "Inside ${it.toInt()}°C" },
    car.n("climate.outsideTemp")?.let { "Outside ${it.toInt()}°C" },
).joinToString(" · ")

/**
 * True when [key] reads "on" AND the climate itself is running. Several BYD climate states keep their
 * last value while the climate is off (Auto and front demist read 1), so they'd otherwise look on.
 */
fun Car.climateOn(key: String): Boolean = on(key) && (label("climate.power") != "off")

/** A label, or "Mode n" when the car reports a code the signal map has no name for yet. */
fun Car.readable(key: String): String = label(key) ?: n(key)?.let { "Mode ${it.toInt()}" } ?: "—"

fun climateTiles(car: Car) = listOf(
    TileSpec("A/C", Ic.AC, car.climateOn("climate.ac")) { car.toggle("climate.ac") },
    TileSpec("Recirculate", Ic.Recirc, car.climateOn("climate.recirc")) { car.toggle("climate.recirc") },
    TileSpec("Front Demist", Ic.FrontDemist, car.climateOn("climate.defrostFront")) { car.toggle("climate.defrostFront") },
    TileSpec("Rear Demist", Ic.RearDemist, car.on("climate.defrostRear")) { car.toggle("climate.defrostRear") },
)

@Composable
internal fun TempStepper(car: Car, who: String, modifier: Modifier, big: Boolean = false) {
    val key = "climate.temp$who"
    val c = LocalContext.current.container
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The big version sits in a card already titled with the seat, so it skips its own label.
        if (!big) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T(who, 15, color = C.InkSecondary)
            if (c.prefs.climateSync && who == "Passenger") {
                Box(Modifier.clip(Radius4).background(LocalAccent.current.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    T("SYNC", 14, FontWeight.Bold, color = LocalAccent.current)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val btn = if (big) 80.dp else 56.dp
            ChevronButton(Ic.ChevronLeft, "$who cooler", { car.nudge(key, -1.0) }, size = btn, iconSize = if (big) 32.dp else 24.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                T(car.n(key).fmt(0, "°"), if (big) 88 else 52, FontWeight.Light, lineHeight = if (big) 96 else 58)
            }
            ChevronButton(Ic.ChevronRight, "$who warmer", { car.nudge(key, 1.0) }, size = btn, iconSize = if (big) 32.dp else 24.dp)
        }
    }
}

/**
 * One seat: a heat button and a vent button that fill the height given to [modifier] (the side label sits in the corner).
 * Each tap steps off -> low -> high -> off; the dots show the level and the button fills with the accent while on.
 */
@Composable
internal fun SeatControl(car: Car, who: String, modifier: Modifier) {
    val heat = car.label("seat.heat$who") ?: "off"
    val vent = car.label("seat.vent$who") ?: "off"
    fun next(l: String) = when (l) { "off" -> "low"; "low" -> "high"; else -> "off" }
    Column(modifier) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeatButton(Ic.SeatHeat, "$who seat heating", heat, Modifier.weight(1f), who) {
                val n = next(heat)
                if (n != "off" && vent != "off") car.setLabel("seat.vent$who", "off")
                car.setLabel("seat.heat$who", n)
            }
            SeatButton(Ic.SeatVent, "$who seat ventilation", vent, Modifier.weight(1f), who) {
                val n = next(vent)
                if (n != "off" && heat != "off") car.setLabel("seat.heat$who", "off")
                car.setLabel("seat.vent$who", n)
            }
        }
    }
}

@Composable
private fun SeatButton(icon: Ic, desc: String, level: String, modifier: Modifier, who: String, onClick: () -> Unit) {
    val on = level != "off"
    val lit = when (level) { "low" -> 1; "high" -> 2; else -> 0 }
    val fg = if (on) C.OnAccent else C.InkSecondary
    Column(
        modifier
            .fillMaxHeight()
            .clip(Radius4)
            .background(if (on) LocalAccent.current else C.P.btnLightest)
            .border(1.dp, if (on) LocalAccent.current else C.Line, Radius4)
            .clickable(onClickLabel = desc, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        DsIcon(icon, fg, 28.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(2) { i -> Box(Modifier.size(width = 14.dp, height = 3.dp).clip(Radius4).background(if (i < lit) fg else fg.copy(alpha = 0.25f))) }
        }
        T(if (icon == Ic.SeatHeat) "Heat" else "Vent", 14, if (on) FontWeight.Medium else FontWeight.Normal, color = fg)
        T(who, 14, color = fg.copy(alpha = 0.7f))
    }
}


// ---------------------------------------------------------------- shortcuts under the climate card

/**
 * Apps & shortcuts widget: when 1 row tall, shows a slim bar of favourite apps + page shortcuts (expanding app
 * capacity when widened). When resized taller (2+ rows, e.g. the 4 × 3 default under Climate), fills the card with a
 * multi-row grid of all pinned apps plus an "+ Add" tile, with the page shortcuts anchored along the bottom.
 * "Apps" opens SharkOS-CE's own app grid: BYD's app list closes itself after 8 s.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShortcutRow(
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
    hidden: Set<String> = emptySet(),
    spec: WidgetSpec? = null,
) {
    val ctx = LocalContext.current
    val c = ctx.container
    val all by c.apps.apps.collectAsState()
    prefsTick()
    val showApps = "apps" !in hidden
    val showPages = "pages" !in hidden
    val allFavs = if (showApps) c.apps.dock(all, c.prefs.dock) else emptyList()
    val items = if (!showPages) emptyList() else listOf(
        Triple("Controls", Ic.Sliders) { onNavigate(Screen.QUICK_SETTINGS) },
        Triple("Drive", Ic.OffRoad) { onNavigate(Screen.OFFROAD) },
        Triple("Gauges", Ic.Gauges) { onNavigate(Screen.GAUGES) },
        Triple("Car", Ic.Vehicle) { onNavigate(Screen.VEHICLE) },
        Triple("Apps", Ic.Apps) { onNavigate(Screen.APPS) },
    )

    if ((spec?.h ?: 1) <= 1) {
        Box(modifier, contentAlignment = Alignment.Center) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(104.dp).clip(Radius4).background(C.SurfaceCard).padding(horizontal = 12.dp),
            ) {
                val pagesWidth = if (showPages) 307.dp else 0.dp
                val maxAppSlots = ((maxWidth - pagesWidth) / 62.dp).toInt().coerceAtLeast(if (showApps) 1 else 0)
                val favs = allFavs.take(maxAppSlots)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    favs.forEach { app ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(Radius4).combinedClickable(
                                onClick = { c.apps.launch(app) },
                                onLongClick = {
                                    c.apps.togglePin(app.pkg)
                                    Toast.makeText(ctx, "Removed ${app.label} from favourites", Toast.LENGTH_SHORT).show()
                                },
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val icon = app.icon
                            if (icon != null) Image(icon, app.label, Modifier.size(60.dp).clip(Radius4))
                            else Box(Modifier.size(60.dp).clip(Radius4).background(C.P.btnLight))
                        }
                    }
                    if (showApps && favs.size < maxAppSlots) {
                        Box(Modifier.weight(1f).fillMaxHeight().clip(Radius4).clickable { onNavigate(Screen.APPS) }, contentAlignment = Alignment.Center) {
                            DsIcon(Ic.Plus, C.InkMuted, 32.dp)
                        }
                        repeat((maxAppSlots - favs.size - 1).coerceAtLeast(0)) { Box(Modifier.weight(1f)) }
                    }
                    if (showApps && showPages) Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(56.dp).background(C.Line))
                    items.forEach { (label, icon, go) ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(Radius4).clickable(onClick = go),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                        ) {
                            DsIcon(icon, C.InkSecondary, 30.dp)
                            T(label, 15, color = C.InkSecondary)
                        }
                    }
                }
            }
        }
        return
    }

    DCard(modifier, padding = 16.dp, spacing = 10.dp) {
        if (showApps) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val cols = (maxWidth / 100.dp).toInt().coerceAtLeast(3)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(allFavs, key = { it.pkg }) { app ->
                        Column(
                            Modifier.fillMaxWidth().clip(Radius4).combinedClickable(
                                onClick = { c.apps.launch(app) },
                                onLongClick = {
                                    c.apps.togglePin(app.pkg)
                                    Toast.makeText(ctx, "Removed ${app.label} from favourites", Toast.LENGTH_SHORT).show()
                                },
                            ).padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val icon = app.icon
                            if (icon != null) Image(icon, app.label, Modifier.size(58.dp).clip(Radius4))
                            else Box(Modifier.size(58.dp).clip(Radius4).background(C.P.btnLight))
                            T(app.label, 14, color = C.InkSecondary, maxLines = 1, align = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    item(key = "__add_app__") {
                        Column(
                            Modifier.fillMaxWidth().clip(Radius4).clickable { onNavigate(Screen.APPS) }.padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier.size(58.dp).clip(Radius4).border(1.dp, C.Line, Radius4),
                                contentAlignment = Alignment.Center,
                            ) {
                                DsIcon(Ic.Plus, C.InkMuted, 26.dp)
                            }
                            T("Add", 14, color = C.InkMuted, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (showApps && showPages) Hairline()
        if (showPages) {
            Row(
                if (showApps) Modifier.fillMaxWidth().height(72.dp) else Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { (label, icon, go) ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(Radius4).clickable(onClick = go),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    ) {
                        DsIcon(icon, C.InkSecondary, 28.dp)
                        T(label, 14, color = C.InkSecondary)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- media

/** Source buttons open BYD's media app (Bluetooth, radio and USB all live in "Media Center"), CarPlay, or Android Auto. */
private val SOURCES = listOf(
    "Bluetooth" to listOf("Media Center", "Bluetooth Music", "Music"),
    "Radio" to listOf("Media Center", "Radio"),
    "USB" to listOf("Media Center", "USB Music", "Music"),
    "CarPlay" to listOf("Apple CarPlay", "CarPlay"),
    "Android Auto" to listOf("Android Auto"),
)

@Composable
internal fun MediaCard(modifier: Modifier, hidden: Set<String> = emptySet()) {
    val ctx = LocalContext.current
    val c = ctx.container
    val np by c.media.nowPlaying.collectAsState()
    val access by c.media.hasAccess.collectAsState()
    val eq by c.audio.eq.collectAsState()
    val vol by c.audio.volume.collectAsState()

    // The card can be short (4×3). Source and Equaliser only fit in a tall card; composing them in a short one just
    // gets them clipped (and pushed the Volume bar mostly out of view), so they wait until there is room.
    BoxWithConstraints(modifier) {
    val roomForSource = maxHeight >= 500.dp
    val roomForEq = maxHeight >= 640.dp
    DCard(Modifier.fillMaxSize(), spacing = 16.dp) {
        if ("controls" in hidden) {
            // track & controls switched off in Options
        } else if (!access && np == null) {
            MediaAccessPrompt()
        } else {
            TrackRow(np)
            Progress(np)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconTap(Ic.Sliders, "Equaliser", C.InkMuted, 30.dp) { c.audio.setEnabled(!eq.enabled) }
                IconTap(Ic.Prev, "Previous track", C.Ink, 40.dp) { c.media.previous() }
                IconTap(if (np?.playing == true) Ic.Pause else Ic.Play, if (np?.playing == true) "Pause" else "Play", C.Ink, 52.dp) { c.media.playPause() }
                IconTap(Ic.Next, "Next track", C.Ink, 40.dp) { c.media.next() }
                IconTap(Ic.Search, "Open the music app", C.InkMuted, 30.dp) { np?.pkg?.let { c.apps.launchPackage(it) } }
            }
        }
        if ("controls" !in hidden) Hairline()

        if ("volume" !in hidden) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                T("Volume", 18, color = C.InkSecondary, modifier = Modifier.weight(1f))
                T("$vol", 18, FontWeight.Medium)
            }
            VolumeBar(vol, c.audio.maxVolume) { c.audio.setVolume(it) }
        }

        if ("source" !in hidden && roomForSource) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val allApps by c.apps.apps.collectAsState()
            fun appFor(i: Int) = SOURCES[i].second.firstNotNullOfOrNull { name -> allApps.firstOrNull { it.label.equals(name, ignoreCase = true) } }
            val current = SOURCES.indices.firstOrNull { i ->
                val pkg = np?.pkg ?: return@firstOrNull false
                when (SOURCES[i].first) {
                    "Android Auto" -> pkg.contains("androidauto", ignoreCase = true)
                    "CarPlay" -> pkg.contains("carplay", ignoreCase = true)
                    else -> pkg.contains(SOURCES[i].first, ignoreCase = true)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                T("Source", 18, color = C.InkSecondary, modifier = Modifier.weight(1f))
                if (current == 3 || current == 4) {
                    val pkg = if (current == 3) "com.ts.carplay.app" else "com.ts.androidauto.app"
                    val label = if (current == 3) "CarPlay" else "Android Auto"
                    SmallButton("Split with Dashboard", { c.apps.launchAdjacent(pkg, label) }, height = 32.dp, primary = true)
                }
            }
            LineSegmented(SOURCES.map { it.first }, current, { i ->
                val app = appFor(i)
                when {
                    app != null -> c.apps.launch(app)
                    SOURCES[i].first == "Android Auto" -> c.apps.launchPackage("com.ts.androidauto.app")
                    SOURCES[i].first == "CarPlay" -> c.apps.launchPackage("com.ts.carplay.app")
                    else -> Toast.makeText(ctx, "${SOURCES[i].first}: no matching app on this head unit", Toast.LENGTH_SHORT).show()
                }
            }, textSize = 16, height = 60.dp)
        }

        if ("eq" !in hidden && roomForEq) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                T("Equaliser", 18, color = C.InkSecondary, modifier = Modifier.weight(1f))
                if (eq.available) {
                    if (eq.enabled && eq.presets.isNotEmpty()) {
                        val name = eq.presets.getOrNull(eq.currentPreset) ?: "Custom"
                        Row(
                            Modifier.height(56.dp).width(184.dp).clip(Radius4).background(C.P.btnLightest)
                                .clickable { c.audio.usePreset((eq.currentPreset + 1).mod(eq.presets.size)) }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            T(name, 16, color = C.P.btnInk, modifier = Modifier.weight(1f))
                            DsIcon(Ic.ChevronDown, C.P.btnInk, 16.dp)
                        }
                    }
                    PillSwitch(eq.enabled, { c.audio.setEnabled(it) })
                    T(if (eq.enabled) "On" else "Off", 16, FontWeight.Medium, modifier = Modifier.width(32.dp))
                }
            }
            when {
                !eq.available -> T("This head unit doesn't allow a system equaliser${eq.error?.let { " ($it)" } ?: ""}.", 14, color = C.InkMuted, maxLines = 3)
                // Turning the equaliser off collapses the bands, as in the design.
                eq.enabled -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    eq.bands.forEach { b ->
                        val span = (eq.maxLevel - eq.minLevel).coerceAtLeast(1)
                        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            T((b.level / 100.0).let { if (it > 0) "+%.0f".format(it) else "%.0f".format(it) }, 14, FontWeight.Medium)
                            VSlider(
                                (b.level - eq.minLevel).toFloat() / span,
                                { f -> c.audio.setBand(b.index, (eq.minLevel + f * span).toInt()) },
                                Modifier.weight(1f),
                                color = C.Ink,
                            )
                            T(if (b.centerHz >= 1000) "${b.centerHz / 1000}k" else "${b.centerHz}", 14, color = C.InkMuted)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun TrackRow(np: NowPlaying?) {
    val ctx = LocalContext.current
    val appName = np?.pkg?.let { pkg ->
        runCatching { ctx.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val art = np?.art
        Box(Modifier.size(88.dp).clip(Radius4).background(C.P.btnLight), contentAlignment = Alignment.Center) {
            if (art != null) Image(art.asImageBitmap(), null, Modifier.size(88.dp), contentScale = ContentScale.Crop)
            else DsIcon(Ic.Music, C.InkMuted, 36.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T(np?.title ?: "Nothing playing", 22, FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (appName != null) DsIcon(Ic.Cast, C.InkMuted, 14.dp)
                T(listOfNotNull(np?.artist?.ifBlank { null }, appName).joinToString(" · "), 14, color = C.InkMuted)
            }
        }
    }
}

@Composable
private fun Progress(np: NowPlaying?) {
    val f = if (np != null && np.durationMs > 0) (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.Line))
        Box(Modifier.fillMaxWidth(f).height(2.dp).background(C.Ink))
        Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.001f)), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(C.Ink))
        }
    }
}

@Composable
private fun IconTap(icon: Ic, desc: String, tint: Color, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(76.dp).clip(Radius4).clickable(onClickLabel = desc, onClick = onClick), contentAlignment = Alignment.Center) {
        DsIcon(icon, tint, size)
    }
}

/** Volume bar with direct click & drag touch detection, plus minus/plus buttons for fine adjustments. */
@Composable
private fun VolumeBar(volume: Int, max: Int, onSet: (Int) -> Unit) {
    val m = max.coerceAtLeast(1)
    var widthPx by remember { mutableFloatStateOf(1f) }
    val vol by rememberUpdatedState(volume)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChevronButton(Ic.Minus, "Volume down", { onSet((volume - 1).coerceAtLeast(0)) }, size = 64.dp, iconSize = 28.dp)
        Box(
            Modifier.weight(1f).height(64.dp).clip(Radius4).background(C.P.btnLightest)
                .onGloballyPositioned { widthPx = it.size.width.toFloat().coerceAtLeast(1f) }
                // A touch never jumps the volume: a tap moves one step towards the finger, a sideways drag moves it
                // by the distance dragged (at most 3 steps per event). One accidental touch can't blast the cabin.
                .pointerInput(m) {
                    detectTapGestures { offset ->
                        val thumbX = widthPx * vol / m
                        onSet((vol + if (offset.x > thumbX) 1 else -1).coerceIn(0, m))
                    }
                }
                .pointerInput(m) {
                    var acc = 0f
                    detectHorizontalDragGestures(onDragStart = { acc = 0f }) { change, dx ->
                        change.consume()
                        acc += dx
                        val stepPx = widthPx / m
                        val steps = (acc / stepPx).toInt().coerceIn(-3, 3)
                        if (steps != 0) {
                            acc -= steps * stepPx
                            onSet((vol + steps).coerceIn(0, m))
                        }
                    }
                },
        ) {
            Box(Modifier.fillMaxWidth((volume.toFloat() / m).coerceIn(0f, 1f)).fillMaxHeight().background(C.SurfaceRaised))
            DsIcon(Ic.Volume, C.Ink, 26.dp, Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
        }
        ChevronButton(Ic.Plus, "Volume up", { onSet((volume + 1).coerceAtMost(m)) }, size = 64.dp, iconSize = 28.dp)
    }
}
