package com.sharkhome.launcher.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharkhome.launcher.Screen
import com.sharkhome.launcher.container
import com.sharkhome.launcher.settings.ClimatePreset
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent
import com.sharkhome.launcher.ui.components.BtnStyle
import com.sharkhome.launcher.ui.components.Car
import com.sharkhome.launcher.ui.components.ChevronButton
import com.sharkhome.launcher.ui.components.DButton
import com.sharkhome.launcher.ui.components.DCard
import com.sharkhome.launcher.ui.components.FanBar
import com.sharkhome.launcher.ui.components.Ic
import com.sharkhome.launcher.ui.components.LineSegmented
import com.sharkhome.launcher.ui.components.Radius4
import com.sharkhome.launcher.ui.components.T
import com.sharkhome.launcher.ui.components.TileRow
import com.sharkhome.launcher.ui.components.TileSpec
import com.sharkhome.launcher.ui.components.fmt
import com.sharkhome.launcher.ui.components.prefsTick
import com.sharkhome.launcher.ui.components.rememberCar
import com.sharkhome.launcher.ui.components.seatSides

private sealed interface PresetEditTarget {
    data class New(val initial: ClimatePreset) : PresetEditTarget
    data class Existing(val index: Int, val preset: ClimatePreset) : PresetEditTarget
}

/**
 * Full climate page in the home screen's card style: one card per seat on the outside (big temperature, seat heat and
 * vent), the shared controls in the middle (power, fan, A/C and demist, airflow), presets along the bottom. The
 * controls are the same pieces the home Climate widget uses, so both look and behave alike.
 */
@Composable
fun ClimateScreen(onNavigate: (Screen) -> Unit) {
    val car = rememberCar()
    val ctx = LocalContext.current
    val c = ctx.container
    val last by c.vehicle.lastWrite.collectAsState()
    val (left, right) = seatSides()
    var editTarget by remember { mutableStateOf<PresetEditTarget?>(null) }
    prefsTick()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Title bar
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    T("Climate", 32, FontWeight.Medium, lineHeight = 38)
                    T(
                        last?.message ?: listOf(climateTemps(car), "Every change is checked against what the car reports").filter { it.isNotBlank() }.joinToString(" · "),
                        15, color = if (last?.confirmed == true) C.Ok else C.InkMuted,
                    )
                }
                DButton("Open BYD Climate", { c.apps.launchPackage("com.byd.hvac", "BYD climate") }, height = 52.dp)
                DButton("Done", { onNavigate(Screen.DASHBOARD) }, style = BtnStyle.Dark, minWidth = 120.dp, height = 52.dp)
            }

            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SeatCard(car, left, Modifier.weight(0.27f).fillMaxHeight())
                CentreCard(car, Modifier.weight(0.46f).fillMaxHeight())
                SeatCard(car, right, Modifier.weight(0.27f).fillMaxHeight())
            }

            PresetCard(
                car = car,
                onAdd = { editTarget = PresetEditTarget.New(car.capture(ClimatePreset.nextName(c.prefs.presets))) },
                onEdit = { index, preset -> editTarget = PresetEditTarget.Existing(index, preset) },
            )
        }

        when (val target = editTarget) {
            is PresetEditTarget.New -> ClimatePresetModal(
                car = car,
                initial = target.initial,
                isEdit = false,
                onSave = { preset ->
                    c.prefs.customPresets = c.prefs.customPresets + preset
                    editTarget = null
                    Toast.makeText(ctx, "Saved \"${preset.name}\"", Toast.LENGTH_SHORT).show()
                },
                onClose = { editTarget = null },
            )
            is PresetEditTarget.Existing -> ClimatePresetModal(
                car = car,
                initial = target.preset,
                isEdit = true,
                onSave = { updated ->
                    val list = c.prefs.customPresets.toMutableList()
                    if (target.index in list.indices) list[target.index] = updated
                    c.prefs.customPresets = list
                    editTarget = null
                    Toast.makeText(ctx, "Updated \"${updated.name}\"", Toast.LENGTH_SHORT).show()
                },
                onDelete = {
                    val list = c.prefs.customPresets.toMutableList()
                    val removed = if (target.index in list.indices) list.removeAt(target.index) else null
                    c.prefs.customPresets = list
                    editTarget = null
                    if (removed != null) Toast.makeText(ctx, "Deleted \"${removed.name}\"", Toast.LENGTH_SHORT).show()
                },
                onClose = { editTarget = null },
            )
            null -> Unit
        }
    }
}

/** One seat: its temperature (big), then its heat and vent buttons filling the rest of the card. */
@Composable
private fun SeatCard(car: Car, who: String, modifier: Modifier) {
    val c = LocalContext.current.container
    DCard(modifier, spacing = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            T(who, 24, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (who == "Passenger" && c.prefs.climateSync) {
                Box(Modifier.clip(Radius4).background(LocalAccent.current.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    T("SYNCED TO DRIVER", 14, FontWeight.Bold, color = LocalAccent.current)
                }
            }
        }
        TempStepper(car, who, Modifier.fillMaxWidth(), big = true)
        T("Seat", 16, color = C.InkSecondary)
        SeatControl(car, who, Modifier.weight(1f, fill = false).heightIn(min = 120.dp, max = 240.dp).fillMaxWidth())
        T("Tap to step off → low → high. Heat and vent can't run together.", 14, color = C.InkMuted, maxLines = 2)
    }
}

/** Shared controls: power + Wheel/Sync/Auto, fan, the A/C row and airflow. */
@Composable
private fun CentreCard(car: Car, modifier: Modifier) {
    val on = car.label("climate.power") != "off"
    val accent = LocalAccent.current
    DCard(modifier, spacing = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(if (on) C.Ok else C.ControlOff))
            T(if (on) "Climate on" else "Climate off", 24, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            ClimateHeaderButtons(car, height = 52.dp, textSize = 17)
            DButton(if (on) "Turn off" else "Turn on", { car.setLabel("climate.power", if (on) "off" else "on") },
                style = if (on) BtnStyle.Lightest else BtnStyle.Primary, minWidth = 130.dp, height = 52.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                T("Fan", 16, color = C.InkSecondary, modifier = Modifier.weight(1f))
                T("${car.n("climate.fan").fmt(0)} of 7", 16, FontWeight.Medium)
            }
            FanBar(car.n("climate.fan")?.toInt() ?: 0, 7, { car.set("climate.fan", it.toDouble()) }, height = 64.dp)
        }
        T("Air", 16, color = C.InkSecondary)
        TileRow(climateTiles(car), Modifier.weight(1f, fill = false).heightIn(min = 80.dp, max = 130.dp).fillMaxWidth(), height = Dp.Unspecified)
        T("Airflow", 16, color = C.InkSecondary)
        TileRow(
            listOf(
                TileSpec("Face", Ic.Face, car.label("climate.airflow") == "face") { car.setLabel("climate.airflow", "face") },
                TileSpec("Face + Feet", Ic.FaceFeet, car.label("climate.airflow") == "faceFeet") { car.setLabel("climate.airflow", "faceFeet") },
                TileSpec("Feet", Ic.Feet, car.label("climate.airflow") == "feet") { car.setLabel("climate.airflow", "feet") },
                TileSpec("Max Cool", Ic.MaxCool, car.climateOn("climate.maxCool")) { car.toggle("climate.maxCool") },
            ),
            Modifier.weight(1f, fill = false).heightIn(min = 80.dp, max = 130.dp).fillMaxWidth(),
            height = Dp.Unspecified,
        )
        if (!on) T("Most controls start the climate when tapped, as in BYD's own app.", 14, color = accent.copy(alpha = 0.9f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetCard(car: Car, onAdd: () -> Unit, onEdit: (Int, ClimatePreset) -> Unit) {
    val c = LocalContext.current.container
    val builtIns = ClimatePreset.BUILT_IN.filter { !it.name.contains("Pet", ignoreCase = true) }
    val customs = c.prefs.customPresets.mapIndexedNotNull { idx, p ->
        if (p.name.contains("Pet", ignoreCase = true)) null else idx to p
    }
    val totalCount = builtIns.size + customs.size
    DCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(172.dp)) {
                T("Presets ($totalCount)", 20, FontWeight.SemiBold)
                T("Hold custom to edit/delete", 14, color = C.InkMuted)
            }
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(builtIns, key = { "builtin_${it.name}" }) { p ->
                    PresetItemTile(p, onClick = { car.apply(p) }, onLongClick = null)
                }
                items(customs, key = { (idx, p) -> "custom_${idx}_${p.name}" }) { (idx, p) ->
                    PresetItemTile(p, onClick = { car.apply(p) }, onLongClick = { onEdit(idx, p) })
                }
            }
            DButton("+ Add preset", onAdd, style = BtnStyle.Primary, height = 74.dp, minWidth = 176.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetItemTile(preset: ClimatePreset, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    Column(
        Modifier.width(244.dp).height(78.dp).clip(Radius4).background(C.P.btnLightest)
            .let { if (C.P.dark) it else it.border(1.dp, C.Line, Radius4) }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        T(preset.name, 17, FontWeight.Medium, color = C.P.btnInk)
        T(preset.summary, 14, color = C.InkMuted, maxLines = 2, lineHeight = 15)
    }
}

/**
 * Add / Edit dialog for a [ClimatePreset], covering all controllable climate and seat functions:
 * power, dual-zone sync, independent driver/passenger temps, fan, auto, airflow, A/C, max cool,
 * recirculate, front/rear demist, steering wheel heat, and independent driver/passenger seat heat & vent.
 */
@Composable
internal fun ClimatePresetModal(
    car: Car,
    initial: ClimatePreset,
    isEdit: Boolean,
    onSave: (ClimatePreset) -> Unit,
    onDelete: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val c = LocalContext.current.container
    val accent = LocalAccent.current
    val (left, right) = seatSides()
    var name by remember(initial) { mutableStateOf(initial.name) }
    var power by remember(initial) { mutableStateOf(initial.power) }
    var sync by remember(initial) { mutableStateOf(initial.sync) }
    var tempDriver by remember(initial) { mutableDoubleStateOf(initial.tempDriver.coerceIn(17.0, 32.0)) }
    var tempPassenger by remember(initial) { mutableDoubleStateOf(initial.tempPassenger.coerceIn(17.0, 32.0)) }
    var fan by remember(initial) { mutableIntStateOf(initial.fan.coerceIn(1, 7)) }
    var auto by remember(initial) { mutableStateOf(initial.auto) }
    var ac by remember(initial) { mutableStateOf(initial.ac) }
    var maxCool by remember(initial) { mutableStateOf(initial.maxCool) }
    var recirc by remember(initial) { mutableStateOf(initial.recirc) }
    var defrostFront by remember(initial) { mutableStateOf(initial.defrostFront) }
    var defrostRear by remember(initial) { mutableStateOf(initial.defrostRear) }
    var airflow by remember(initial) { mutableStateOf(initial.airflow) }
    var steeringWheelHeat by remember(initial) { mutableStateOf(initial.steeringWheelHeat) }
    var seatHeatDriver by remember(initial) { mutableStateOf(initial.seatHeatDriver) }
    var seatVentDriver by remember(initial) { mutableStateOf(initial.seatVentDriver) }
    var seatHeatPassenger by remember(initial) { mutableStateOf(initial.seatHeatPassenger) }
    var seatVentPassenger by remember(initial) { mutableStateOf(initial.seatVentPassenger) }

    val draft = ClimatePreset(
        name = name.trim().ifBlank { ClimatePreset.nextName(c.prefs.presets) },
        power = power,
        sync = sync,
        tempDriver = tempDriver,
        tempPassenger = if (sync) tempDriver else tempPassenger,
        fan = fan,
        auto = auto,
        ac = ac,
        maxCool = maxCool,
        recirc = recirc,
        defrostFront = defrostFront,
        defrostRear = defrostRear,
        airflow = if (auto) null else airflow,
        steeringWheelHeat = steeringWheelHeat,
        seatHeatDriver = seatHeatDriver,
        seatVentDriver = seatVentDriver,
        seatHeatPassenger = seatHeatPassenger,
        seatVentPassenger = seatVentPassenger,
    )

    val seatLevels = listOf("off" to "Off", "low" to "Low", "high" to "High")
    val airflowOptions = listOf<Pair<String?, String>>(
        null to "Unchanged",
        "face" to "Face",
        "faceFeet" to "Face + Feet",
        "feet" to "Feet",
    )

    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClose,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // 1040 dp, or 94% of a narrower window (split screen)
            Modifier.width(minOf(1040.dp, maxWidth * 0.94f)).heightIn(max = 960.dp).clip(Radius4).background(C.SurfaceCard)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                T(if (isEdit) "Edit climate preset" else "New climate preset", 26, FontWeight.Medium, modifier = Modifier.weight(1f))
                DButton("Use current car", {
                    val live = car.capture(name)
                    power = live.power
                    sync = live.sync
                    tempDriver = live.tempDriver.coerceIn(17.0, 32.0)
                    tempPassenger = live.tempPassenger.coerceIn(17.0, 32.0)
                    fan = live.fan.coerceIn(1, 7)
                    auto = live.auto
                    ac = live.ac
                    maxCool = live.maxCool
                    recirc = live.recirc
                    defrostFront = live.defrostFront
                    defrostRear = live.defrostRear
                    airflow = live.airflow
                    steeringWheelHeat = live.steeringWheelHeat
                    seatHeatDriver = live.seatHeatDriver
                    seatVentDriver = live.seatVentDriver
                    seatHeatPassenger = live.seatHeatPassenger
                    seatVentPassenger = live.seatVentPassenger
                }, height = 46.dp, textSize = 15)
                DButton("Close", onClose, height = 46.dp, textSize = 15)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T("Preset name", 15, color = C.InkSecondary)
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(Radius4).background(C.Field)
                        .border(1.dp, C.Line, Radius4).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        if (name.isEmpty()) T("Enter preset name…", 18, color = C.InkMuted)
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle = TextStyle(color = C.Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                            cursorBrush = SolidColor(accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (name.isNotEmpty()) {
                        T("Clear", 15, FontWeight.Medium, C.InkMuted, modifier = Modifier.clickable { name = "" }.defaultMinSize(minWidth = 56.dp, minHeight = 44.dp).padding(8.dp))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("System & wheel", 15, color = C.InkSecondary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DButton(
                        if (power) "Climate: On" else "Climate: Off",
                        { power = !power },
                        Modifier.weight(1f),
                        height = 50.dp,
                        textSize = 16,
                        selected = power,
                    )
                    DButton(
                        "Dual Sync",
                        {
                            sync = !sync
                            if (sync) tempPassenger = tempDriver
                        },
                        Modifier.weight(1f),
                        height = 50.dp,
                        textSize = 16,
                        selected = sync && power,
                    )
                    DButton(
                        "Auto",
                        {
                            if (!power) power = true
                            auto = !auto
                            if (auto) airflow = null
                        },
                        Modifier.weight(1f),
                        height = 50.dp,
                        textSize = 16,
                        selected = auto && power,
                    )
                    DButton(
                        "Wheel Heat",
                        { steeringWheelHeat = !steeringWheelHeat },
                        Modifier.weight(1f),
                        height = 50.dp,
                        textSize = 16,
                        selected = steeringWheelHeat,
                    )
                }
            }

            if (power) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(0.54f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        listOf(left, right).forEach { who ->
                            val isDriver = who == "Driver"
                            val shownTemp = if (isDriver || sync) tempDriver else tempPassenger
                            val adjust: (Double) -> Unit = { delta ->
                                val next = (shownTemp + delta).coerceIn(17.0, 32.0)
                                if (sync) {
                                    tempDriver = next
                                    tempPassenger = next
                                } else if (isDriver) {
                                    tempDriver = next
                                } else {
                                    tempPassenger = next
                                }
                            }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    T(who, 15, color = C.InkSecondary)
                                    if (!isDriver && sync) {
                                        Box(Modifier.clip(Radius4).background(accent.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                            T("SYNC", 14, FontWeight.Bold, color = accent)
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ChevronButton(Ic.ChevronLeft, "$who cooler", { adjust(-1.0) }, size = 50.dp, iconSize = 24.dp)
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        T("${shownTemp.toInt()}°C", 34, FontWeight.Light)
                                    }
                                    ChevronButton(Ic.ChevronRight, "$who warmer", { adjust(1.0) }, size = 50.dp, iconSize = 24.dp)
                                }
                            }
                        }
                    }

                    Column(Modifier.weight(0.46f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            T("Fan speed", 15, color = C.InkSecondary, modifier = Modifier.weight(1f))
                            T(if (auto) "Auto (manual $fan)" else "$fan of 7", 15, FontWeight.Medium)
                        }
                        FanBar(fan, 7, {
                            fan = it.coerceIn(1, 7)
                            auto = false
                        }, height = 52.dp)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        T("Airflow", 15, color = C.InkSecondary, modifier = Modifier.weight(1f))
                        if (auto) T("Auto mode controls airflow (tapping a direction switches Auto off)", 14, color = C.InkMuted)
                    }
                    LineSegmented(
                        options = airflowOptions.map { it.second },
                        selected = airflowOptions.indexOfFirst { it.first == (if (auto) null else airflow) }.takeIf { it >= 0 },
                        onSelect = { idx ->
                            val picked = airflowOptions[idx].first
                            airflow = picked
                            if (picked != null) auto = false
                        },
                        height = 46.dp,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    T("Air & demist", 15, color = C.InkSecondary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DButton("A/C", {
                            ac = !ac
                            if (!ac) maxCool = false
                        }, Modifier.weight(1f), height = 50.dp, textSize = 15, selected = ac)
                        DButton("Max Cool", {
                            maxCool = !maxCool
                            if (maxCool) ac = true
                        }, Modifier.weight(1f), height = 50.dp, textSize = 15, selected = maxCool)
                        DButton("Recirculate", { recirc = !recirc }, Modifier.weight(1f), height = 50.dp, textSize = 15, selected = recirc)
                        DButton("Front Demist", { defrostFront = !defrostFront }, Modifier.weight(1f), height = 50.dp, textSize = 15, selected = defrostFront)
                        DButton("Rear Demist", { defrostRear = !defrostRear }, Modifier.weight(1f), height = 50.dp, textSize = 15, selected = defrostRear)
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().clip(Radius4).background(C.Field).border(1.dp, C.Line, Radius4).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    T(
                        "HVAC blower, temperature, A/C and airflow stay off. Rear Demist, Wheel Heat and Seats still run.",
                        14,
                        color = C.InkMuted,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    DButton("Rear Demist", { defrostRear = !defrostRear }, height = 46.dp, minWidth = 156.dp, textSize = 15, selected = defrostRear)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(left, right).forEach { who ->
                    val isDriver = who == "Driver"
                    val heat = if (isDriver) seatHeatDriver else seatHeatPassenger
                    val vent = if (isDriver) seatVentDriver else seatVentPassenger
                    val setHeat: (String) -> Unit = { v ->
                        if (isDriver) {
                            seatHeatDriver = v
                            if (v != "off") seatVentDriver = "off"
                        } else {
                            seatHeatPassenger = v
                            if (v != "off") seatVentPassenger = "off"
                        }
                    }
                    val setVent: (String) -> Unit = { v ->
                        if (isDriver) {
                            seatVentDriver = v
                            if (v != "off") seatHeatDriver = "off"
                        } else {
                            seatVentPassenger = v
                            if (v != "off") seatHeatPassenger = "off"
                        }
                    }
                    Column(
                        Modifier.weight(1f).clip(Radius4).background(C.Field).border(1.dp, C.Line, Radius4).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        T("$who seat", 16, FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            T("Heat", 14, color = C.InkSecondary, modifier = Modifier.width(44.dp))
                            LineSegmented(
                                options = seatLevels.map { it.second },
                                selected = seatLevels.indexOfFirst { it.first == heat }.takeIf { it >= 0 },
                                onSelect = { idx -> setHeat(seatLevels[idx].first) },
                                modifier = Modifier.weight(1f),
                                height = 44.dp,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            T("Vent", 14, color = C.InkSecondary, modifier = Modifier.width(44.dp))
                            LineSegmented(
                                options = seatLevels.map { it.second },
                                selected = seatLevels.indexOfFirst { it.first == vent }.takeIf { it >= 0 },
                                onSelect = { idx -> setVent(seatLevels[idx].first) },
                                modifier = Modifier.weight(1f),
                                height = 44.dp,
                            )
                        }
                    }
                }
            }

            T("Summary: ${draft.summary}", 14, color = C.InkMuted, maxLines = 2)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isEdit && onDelete != null) {
                    Box(
                        Modifier.height(54.dp).clip(Radius4).background(C.Alert.copy(alpha = 0.14f))
                            .border(1.dp, C.Alert, Radius4).clickable(onClick = onDelete)
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        T("Delete preset", 16, FontWeight.Medium, C.Alert)
                    }
                }
                Box(Modifier.weight(1f))
                DButton("Cancel", onClose, height = 54.dp)
                DButton(
                    text = if (isEdit) "Save changes" else "Save preset",
                    onClick = { onSave(draft) },
                    style = BtnStyle.Primary,
                    height = 54.dp,
                    minWidth = 170.dp,
                )
            }
        }
    }
}

