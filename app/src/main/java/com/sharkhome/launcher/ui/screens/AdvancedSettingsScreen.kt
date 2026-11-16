package com.sharkhome.launcher.ui.screens

import com.sharkhome.launcher.ui.components.fmt
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sharkhome.launcher.Screen
import com.sharkhome.launcher.car.StartupMemory
import com.sharkhome.launcher.container
import com.sharkhome.launcher.system.UsbDebugging
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent
import com.sharkhome.launcher.ui.components.BtnStyle
import com.sharkhome.launcher.ui.components.Panel
import com.sharkhome.launcher.ui.components.Shape10
import com.sharkhome.launcher.ui.components.SmallButton
import com.sharkhome.launcher.ui.components.Txt
import com.sharkhome.launcher.ui.components.prefsTick
import kotlinx.coroutines.launch

/** Advanced Settings: remember BYD convenience settings and put them back after every start. */
@Composable
fun AdvancedSettingsScreen(go: (Screen) -> Unit, part: Int = 0) {
    val ctx = LocalContext.current
    val c = ctx.container
    val mem = c.startupMemory
    prefsTick()
    // Read the ticked list here, in the screen's own scope: the cards below are separately remembered, and reading the
    // settings file inside them meant a tap was saved but the box never redrew (owner: "tick boxes don't stay", 8 Nov).
    val memory = c.prefs.advancedMemory
    val readings by c.vehicle.readings.collectAsState()
    val results by mem.results.collectAsState()
    val running by mem.running.collectAsState()
    val lastRun by mem.lastRun.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Txt(if (part == 0) "Startup controls" else "Startup memory", 32, FontWeight.Medium)
        if (part == 0) Txt("Tick Remember on the settings BYD resets, and they are put back after every start.", 16, color = C.Text2)

        val cards = StartupMemory.ITEMS.filter { it.key !in StartupMemory.TRAFFIC_SIGN_KEYS }.map { item ->
            @Composable { m: Modifier ->
                val r = readings[item.key]
                val current = c.vehicle.labelFor(item.key, r?.raw)
                val status = when {
                    r?.raw == null -> "Not found on this car yet"
                    current != null -> current.replaceFirstChar { it.uppercase() }
                    else -> "Unknown value ${r?.raw}"
                }
                SettingCard(
                    title = item.title,
                    status = status,
                    statusMuted = r?.raw == null,
                    note = listOfNotNull(results[item.key]?.message, item.note).joinToString(" · ").ifEmpty { null },
                    remembered = item.key in memory,
                    onRemember = { mem.setRemember(item.key, it) },
                    buttons = item.options.map { opt ->
                        Triple(opt.replaceFirstChar { it.uppercase() }, current == opt) {
                            scope.launch {
                                val res = mem.set(item.key, opt)
                                Toast.makeText(ctx, res.message, Toast.LENGTH_SHORT).show()
                            }
                            Unit
                        }
                    },
                    modifier = m,
                )
            }
        } + listOf(
            @Composable { m: Modifier ->
                SettingCard(
                    title = "Sound EQ Memory",
                    status = if (c.prefs.eqSnapshot != null) "Saved" else "Nothing saved yet",
                    statusMuted = c.prefs.eqSnapshot == null,
                    note = results[StartupMemory.SOUND_EQ]?.message,
                    remembered = StartupMemory.SOUND_EQ in memory,
                    onRemember = { mem.setRemember(StartupMemory.SOUND_EQ, it) },
                    buttons = listOf(
                        Triple("Save current", false) {
                            val ok = c.audio.saveSnapshot()
                            Toast.makeText(ctx, if (ok) "Sound settings saved" else "The equaliser isn't available on this head unit", Toast.LENGTH_SHORT).show()
                        },
                        Triple("Restore now", false) {
                            val ok = c.audio.restoreSnapshot()
                            Toast.makeText(ctx, if (ok) "Sound settings restored" else "Nothing to restore", Toast.LENGTH_SHORT).show()
                        },
                    ),
                    modifier = m,
                )
            },
        )

        val signTile: @Composable (Modifier) -> Unit = { tileMod ->
            TrafficSignPanel(
                items = StartupMemory.ITEMS.filter { it.key in StartupMemory.TRAFFIC_SIGN_KEYS }.sortedBy { StartupMemory.TRAFFIC_SIGN_KEYS.indexOf(it.key) },
                currentOf = { k -> c.vehicle.labelFor(k, readings[k]?.raw) },
                statusOf = { k -> val r = readings[k]; val cur = c.vehicle.labelFor(k, r?.raw); when { r?.raw == null -> "Not found on this car yet"; cur != null -> cur.replaceFirstChar { it.uppercase() }; else -> "Value ${r.raw}" } },
                onSet = { k, o ->
                    scope.launch {
                        val res = mem.set(k, o)
                        if (k != StartupMemory.TRAFFIC_SIGN_KEYS.first()) mem.rememberAs(k, o)
                        Toast.makeText(ctx, res.message, Toast.LENGTH_SHORT).show()
                    }
                },
                onInit = { mem.ensureTrafficSignMemory() },
                masterRemembered = StartupMemory.TRAFFIC_SIGN_KEYS.first() in memory,
                onMasterRemember = { mem.setRemember(StartupMemory.TRAFFIC_SIGN_KEYS.first(), it) },
                modifier = tileMod,
            )
        }

        if (part == 0) (listOf(signTile) + cards).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }

        if (part == 1) Panel(Modifier.fillMaxWidth(), spacing = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("Startup memory", 22, FontWeight.Medium)
                    Txt(lastRun ?: "Ticked settings are restored each time the car starts.", 15, color = C.Text2)
                }
                SmallButton(if (running) "Restoring…" else "Restore now", { if (!running) mem.restoreAll("manual") }, primary = true)
            }
            CheckRow("Start SharkOS-CE when the car starts", c.prefs.autoStart) {
                c.prefs.autoStart = it
                if (it) com.sharkhome.launcher.StartupService.start(ctx)
            }
            CheckRow("Bring SharkOS-CE to the front after start-up", c.prefs.openOnStart) { c.prefs.openOnStart = it }
        }

        if (part == 1) Panel(Modifier.fillMaxWidth(), spacing = 10.dp) {
            Txt("A setting says \"Not found\"?", 18, FontWeight.Medium)
            Txt(
                "Each car firmware names these differently. Open the Car explorer, press Record, change the setting in BYD's own menu, " +
                    "and the value that moves is the one to use. Add its get/set names to shark6_codes.json under the matching advanced key.",
                15, color = C.Text2, maxLines = 4,
            )
            SmallButton("Open Car explorer", { go(Screen.EXPLORER) })
        }

        Txt(
            "Safety protection: ABS, ESC, AEB and emergency steering intervention are never changed.",
            16, FontWeight.Medium, C.Red, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/**
 * Dedicated Quick Settings screen: BYD OEM / Tesla minimalistic Control Center.
 * Top: 1-tap OEM icon toggles (remembered by the car) + Driving/Chassis Dynamics & HEV SOC Hold.
 * Bottom: Advanced Settings (Startup Memory) for convenience settings restored on every vehicle start.
 */
@Composable
fun QuickSettingsScreen(go: (Screen) -> Unit = {}) {
    val ctx = LocalContext.current
    val c = ctx.container
    val mem = c.startupMemory
    val car = com.sharkhome.launcher.ui.components.rememberCar()
    val accent = LocalAccent.current
    prefsTick()

    val memory = c.prefs.advancedMemory
    val readings by c.vehicle.readings.collectAsState()
    val running by mem.running.collectAsState()
    val lastRun by mem.lastRun.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var adbOn by remember { mutableStateOf(UsbDebugging.isEnabled(ctx)) }
    var adbBusy by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                adbOn = UsbDebugging.isEnabled(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Regen has its own row in the driving card, so it isn't repeated as a tile here.
    val tiles = QUICK_TILES.filter { it.id != "regen" && car.available(it.key) }
    val missing = QUICK_TILES.filter { !car.available(it.key) }.map { it.label } +
        listOfNotNull("SOC Hold switch".takeIf { !car.available("energy.socSave") }, "Screen rotation".takeIf { !car.available("display.screenRotation") })

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                com.sharkhome.launcher.ui.components.T("Quick controls", 32, FontWeight.Medium)
                if (missing.isNotEmpty()) {
                    com.sharkhome.launcher.ui.components.T(
                        "Not reported by this car: ${missing.joinToString(", ")}",
                        13,
                        color = C.InkMuted,
                        maxLines = 1,
                    )
                }
            }
            com.sharkhome.launcher.ui.components.DButton(
                text = when {
                    adbBusy -> "Enabling USB Debugging…"
                    adbOn -> "USB Debugging · ON"
                    else -> "Turn on USB Debugging"
                },
                onClick = {
                    if (adbBusy) return@DButton
                    scope.launch {
                        adbBusy = true
                        val res = UsbDebugging.enable(ctx, c.adb)
                        adbOn = res.enabled
                        Toast.makeText(ctx, res.message, Toast.LENGTH_SHORT).show()
                        adbBusy = false
                    }
                },
                style = if (adbOn) BtnStyle.Lightest else BtnStyle.Primary,
                selected = adbOn,
                height = 48.dp,
                textSize = 15,
            )
            com.sharkhome.launcher.ui.components.DButton(
                text = "BYD ADB Screen",
                onClick = {
                    if (!UsbDebugging.openBydAdbScreen(ctx)) {
                        Toast.makeText(ctx, "BYD ADB screen unavailable", Toast.LENGTH_SHORT).show()
                    }
                },
                style = BtnStyle.Lightest,
                height = 48.dp,
                textSize = 15,
            )
        }

        // One row of big one-tap tiles (only features this car actually reports).
        Row(Modifier.fillMaxWidth().height(132.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.forEach { t ->
                val on = t.isOn(car)
                val fg = if (on) androidx.compose.ui.graphics.Color.White else C.Ink
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(com.sharkhome.launcher.ui.components.Radius4)
                        .background(if (on) accent else C.SurfaceCard)
                        .border(1.dp, if (on) accent else C.Line, com.sharkhome.launcher.ui.components.Radius4)
                        .clickable { t.toggle(car) }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        com.sharkhome.launcher.ui.components.DsIcon(t.icon, if (on) fg else C.InkSecondary, 34.dp)
                        Box(Modifier.weight(1f))
                        com.sharkhome.launcher.ui.components.T(if (on) "ON" else "OFF", 13, FontWeight.SemiBold, if (on) fg else C.InkMuted)
                    }
                    com.sharkhome.launcher.ui.components.T(t.label, 20, FontWeight.SemiBold, color = fg)
                }
            }
            if (tiles.isEmpty()) com.sharkhome.launcher.ui.components.T("Waiting for the car service…", 18, color = C.InkMuted)
        }

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Driving feel
            com.sharkhome.launcher.ui.components.DCard(Modifier.weight(1f).fillMaxHeight(), spacing = 10.dp) {
                com.sharkhome.launcher.ui.components.T("Driving feel", 22, FontWeight.SemiBold)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
                    QuickModeRow(com.sharkhome.launcher.ui.components.Ic.Regen, "Regenerative braking", "Energy recovery when you lift off",
                        listOf("Standard", "High"), car.label("drive.regenLevel")) { car.setLabel("drive.regenLevel", it) }
                    QuickModeRow(com.sharkhome.launcher.ui.components.Ic.BrakeFeel, "Brake pedal feel", "Booster response",
                        listOf("Comfort", "Sport"), car.label("chassis.brakeFeel")) { car.setLabel("chassis.brakeFeel", it) }
                    QuickModeRow(com.sharkhome.launcher.ui.components.Ic.SteeringWheel, "Steering assist", "Steering weight",
                        listOf("Comfort", "Sport"), car.label("chassis.steerFeel")) { car.setLabel("chassis.steerFeel", it) }
                }
            }
            // Energy
            com.sharkhome.launcher.ui.components.DCard(Modifier.weight(1f).fillMaxHeight(), spacing = 12.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.sharkhome.launcher.ui.components.DsIcon(com.sharkhome.launcher.ui.components.Ic.SocHold, accent, 28.dp)
                    Column(Modifier.weight(1f)) {
                        com.sharkhome.launcher.ui.components.T("Battery hold target", 22, FontWeight.SemiBold)
                        com.sharkhome.launcher.ui.components.T("The charge the car keeps in HEV mode (25–70 %)", 14, color = C.InkMuted)
                    }
                    if (car.available("energy.socSave")) {
                        val on = car.on("energy.socSave")
                        com.sharkhome.launcher.ui.components.DButton(if (on) "Hold · ON" else "Hold · OFF", { car.toggle("energy.socSave") }, selected = on, height = 48.dp)
                    }
                }
                val target = car.n("energy.socTarget")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    com.sharkhome.launcher.ui.components.ChevronButton(com.sharkhome.launcher.ui.components.Ic.Minus, "Lower target", {
                        target?.let { car.set("energy.socTarget", (it - 5).coerceIn(25.0, 70.0)) }
                    }, size = 72.dp, iconSize = 28.dp)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        com.sharkhome.launcher.ui.components.T(target?.let { "${it.toInt()} %" } ?: "—", 56, FontWeight.Light, lineHeight = 62)
                    }
                    com.sharkhome.launcher.ui.components.ChevronButton(com.sharkhome.launcher.ui.components.Ic.Plus, "Raise target", {
                        target?.let { car.set("energy.socTarget", (it + 5).coerceIn(25.0, 70.0)) }
                    }, size = 72.dp, iconSize = 28.dp)
                }
                com.sharkhome.launcher.ui.components.ThinBar(target?.let { (it / 100).toFloat() }, color = accent, height = 6.dp)
                Box(Modifier.weight(1f))
                com.sharkhome.launcher.ui.components.T("Last 50 km: where the energy went", 15, color = C.InkSecondary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    com.sharkhome.launcher.ui.components.LabelValue("Driving", car.n("energy.drivePct50km").fmt(0, " %"), Modifier.weight(1f), valueSize = 22, labelSize = 14)
                    com.sharkhome.launcher.ui.components.LabelValue("A/C", car.n("energy.acPct50km").fmt(0, " %"), Modifier.weight(1f), valueSize = 22, labelSize = 14)
                    com.sharkhome.launcher.ui.components.LabelValue("Other", car.n("energy.auxPct50km").fmt(0, " %"), Modifier.weight(1f), valueSize = 22, labelSize = 14)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    com.sharkhome.launcher.ui.components.LabelValue("Lifetime EV", car.n("vehicle.evMileage").fmt(0, " km"), Modifier.weight(1f), valueSize = 22, labelSize = 14)
                    com.sharkhome.launcher.ui.components.LabelValue("Lifetime HEV", car.n("vehicle.hevMileage").fmt(0, " km"), Modifier.weight(1f), valueSize = 22, labelSize = 14)
                    com.sharkhome.launcher.ui.components.LabelValue("Power source", car.label("vehicle.powerMode") ?: "—", Modifier.weight(1f), valueSize = 22, labelSize = 14)
                }
                com.sharkhome.launcher.ui.components.Hairline()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    com.sharkhome.launcher.ui.components.LabelValue("HV battery", car.n("vehicle.batteryPct").fmt(0, " %"), Modifier.weight(1f), valueSize = 22, labelSize = 14)
                    com.sharkhome.launcher.ui.components.LabelValue("12V battery", car.n("battery.voltage").fmt(1, " V"), Modifier.weight(1f), valueSize = 22, labelSize = 14)
                    com.sharkhome.launcher.ui.components.LabelValue("Battery health", car.n("vehicle.batteryHealth").fmt(0, " %"), Modifier.weight(1f), valueSize = 22, labelSize = 14)
                }
            }
        }
    }
}

@Composable
private fun QuickModeRow(
    icon: com.sharkhome.launcher.ui.components.Ic,
    label: String,
    subtitle: String,
    options: List<String>,
    current: String?,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        com.sharkhome.launcher.ui.components.DsIcon(icon, C.InkSecondary, 28.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            com.sharkhome.launcher.ui.components.T(label, 18, FontWeight.Medium)
            com.sharkhome.launcher.ui.components.T(subtitle, 14, color = C.InkMuted)
        }
        com.sharkhome.launcher.ui.components.LineSegmented(
            options = options,
            selected = options.indexOf(current).takeIf { it >= 0 },
            onSelect = { onSelect(options[it]) },
            modifier = Modifier.width(300.dp),
            height = 52.dp,
        )
    }
}

@Composable
private fun SettingCard(
    title: String,
    status: String,
    statusMuted: Boolean,
    note: String?,
    remembered: Boolean,
    onRemember: (Boolean) -> Unit,
    buttons: List<Triple<String, Boolean, () -> Unit>>,
    modifier: Modifier,
) {
    Panel(modifier, spacing = 12.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Txt(title, 20, FontWeight.Medium)
                Txt(status, 16, color = C.Text2)
            }
            Checkbox("Remember at startup", remembered, onRemember)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            buttons.forEach { (label, active, onClick) ->
                // Blue only means "on": a selected "Off" is a lighter grey, so the driver can tell at a glance.
                val isOff = label.equals("Off", ignoreCase = true)
                val bg = when {
                    active && !isOff -> LocalAccent.current
                    active -> C.SurfaceRaised
                    else -> C.P.btnLightest
                }
                Box(
                    Modifier.weight(1f).height(60.dp).clip(Shape10)
                        .background(bg)
                        .clickable { onClick() },
                    contentAlignment = Alignment.Center,
                ) { Txt(label, 18, if (active) FontWeight.Medium else FontWeight.Normal, if (active && !isOff) androidx.compose.ui.graphics.Color.White else if (active) C.Ink else C.Text3) }
            }
        }
        if (note != null) Txt(note, 14, color = C.Text2)
    }
}

@Composable
private fun Checkbox(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.height(52.dp).clip(Shape10).clickable { onChange(!on) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(24.dp).clip(Shape10).background(if (on) LocalAccent.current else C.Seg).border(1.dp, C.Text2, Shape10),
            contentAlignment = Alignment.Center,
        ) { if (on) Icon(Icons.Rounded.Check, null, tint = C.OnAccent, modifier = Modifier.size(18.dp)) }
        Txt(label, 15)
    }
}

@Composable
private fun CheckRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 17, modifier = Modifier.weight(1f))
        Box(Modifier.width(52.dp).height(28.dp).clip(Shape10).background(if (on) LocalAccent.current else C.Raised), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.padding(3.dp).size(22.dp).clip(Shape10).background(androidx.compose.ui.graphics.Color.White))
        }
    }
}

/**
 * Traffic Sign Recognition as one tile of normal card height: master Off / On + Remember on the left, the three speed-limit
 * options as small toggles (always remembered at start-up) on the right.
 */
@Composable
private fun TrafficSignPanel(
    items: List<StartupMemory.Item>,
    currentOf: (String) -> String?,
    statusOf: (String) -> String,
    onSet: (String, String) -> Unit,
    onInit: () -> Unit,
    masterRemembered: Boolean,
    onMasterRemember: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    androidx.compose.runtime.LaunchedEffect(Unit) { onInit() }
    val master = items.first()
    val masterOff = currentOf(master.key) == "off"
    com.sharkhome.launcher.ui.components.DCard(modifier, padding = 12.dp, spacing = 8.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        com.sharkhome.launcher.ui.components.T("Traffic Sign Recognition", 15, FontWeight.SemiBold, maxLines = 1)
                        com.sharkhome.launcher.ui.components.T(statusOf(master.key), 11, color = C.InkMuted, maxLines = 1)
                    }
                    Row(
                        Modifier.height(28.dp)
                            .clip(com.sharkhome.launcher.ui.components.Radius4)
                            .background(if (masterRemembered) accent.copy(alpha = 0.16f) else C.SurfacePanel)
                            .border(1.dp, if (masterRemembered) accent else C.Line, com.sharkhome.launcher.ui.components.Radius4)
                            .clickable { onMasterRemember(!masterRemembered) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            Modifier.size(14.dp).clip(com.sharkhome.launcher.ui.components.Radius4)
                                .background(if (masterRemembered) accent else androidx.compose.ui.graphics.Color.Transparent)
                                .border(1.dp, if (masterRemembered) accent else C.InkMuted, com.sharkhome.launcher.ui.components.Radius4),
                            contentAlignment = Alignment.Center,
                        ) { if (masterRemembered) Icon(Icons.Rounded.Check, null, tint = C.OnAccent, modifier = Modifier.size(11.dp)) }
                        com.sharkhome.launcher.ui.components.T("Remember", 11, FontWeight.Medium, if (masterRemembered) C.Ink else C.InkSecondary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    master.options.forEach { opt ->
                        val active = currentOf(master.key) == opt
                        val isOff = opt.equals("off", ignoreCase = true)
                        Box(
                            Modifier.weight(1f).height(44.dp)
                                .clip(com.sharkhome.launcher.ui.components.Radius4)
                                .background(when { active && !isOff -> accent; active -> C.SurfaceRaised; else -> C.P.btnLightest })
                                .border(1.dp, if (active) accent else C.Line, com.sharkhome.launcher.ui.components.Radius4)
                                .clickable { onSet(master.key, opt) },
                            contentAlignment = Alignment.Center,
                        ) {
                            com.sharkhome.launcher.ui.components.T(
                                opt.replaceFirstChar { it.uppercase() }, 13,
                                if (active) FontWeight.SemiBold else FontWeight.Medium,
                                if (active && !isOff) androidx.compose.ui.graphics.Color.White else if (active) C.Ink else C.InkSecondary,
                            )
                        }
                    }
                }
            }
            Column(
                Modifier.width(290.dp).height(80.dp).let { if (masterOff) it.alpha(0.5f) else it },
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                items.drop(1).forEach { item ->
                    val on = currentOf(item.key) == "on"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.sharkhome.launcher.ui.components.T(item.title.removePrefix("Intelligent "), 12, FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                        com.sharkhome.launcher.ui.components.PillSwitch(on, { onSet(item.key, if (it) "on" else "off") })
                    }
                }
            }
        }
    }
}
