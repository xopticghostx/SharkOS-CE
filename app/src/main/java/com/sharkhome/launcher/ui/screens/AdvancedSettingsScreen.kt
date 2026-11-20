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
        }

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
            "SharkOS-CE never changes a setting by itself: only what you tick Remember on is restored at start-up.",
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

    // Android-level controls (not car signals): Bluetooth is read and switched through the adapter, screen brightness
    // through the head unit's own adb (Settings.System.screen_brightness, 0-255).
    val btAdapter = remember { runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter() }.getOrNull() }
    var btOn by remember { mutableStateOf(runCatching { btAdapter?.isEnabled == true }.getOrDefault(false)) }
    var brightness by remember { mutableStateOf(readScreenBrightness(ctx)) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            btOn = runCatching { btAdapter?.isEnabled == true }.getOrDefault(btOn)
            brightness = readScreenBrightness(ctx)
            kotlinx.coroutines.delay(1500)
        }
    }
    fun setBrightness(v: Int) {
        val n = v.coerceIn(10, 255)
        brightness = n
        scope.launch { runCatching { c.adb.shell("settings put system screen_brightness $n") } }
    }

    val rowOne = tiles.map { t -> ControlTile(t.label, t.icon, t.isOn(car)) { t.toggle(car) } }
    val rowTwo = MORE_TILES.filter { car.available(it.key) }.map { t -> ControlTile(t.label, t.icon, t.isOn(car)) { t.toggle(car) } } +
        listOfNotNull(
            btAdapter?.let {
                ControlTile("Bluetooth", com.sharkhome.launcher.ui.components.Ic.Bluetooth, btOn) {
                    btOn = !btOn
                    runCatching { if (btOn) it.enable() else it.disable() }
                }
            },
        )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
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

        // Rows of big one-tap tiles (only features this car actually reports).
        if (rowOne.isEmpty()) com.sharkhome.launcher.ui.components.T("Waiting for the car service…", 18, color = C.InkMuted)
        else ControlTileRow(rowOne, 8, 132.dp, accent)
        if (rowTwo.isNotEmpty()) ControlTileRow(rowTwo, 8, 108.dp, accent)

        // Driving feel and Battery hold are compact so the Windows card (and more controls later) fit underneath.
        Row(Modifier.fillMaxWidth().height(390.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Driving feel
            com.sharkhome.launcher.ui.components.DCard(Modifier.weight(1f).fillMaxHeight(), padding = 18.dp, spacing = 8.dp) {
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
            com.sharkhome.launcher.ui.components.DCard(Modifier.weight(1f).fillMaxHeight(), padding = 18.dp, spacing = 8.dp) {
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
                    }, size = 56.dp, iconSize = 24.dp)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        com.sharkhome.launcher.ui.components.T(target?.let { "${it.toInt()} %" } ?: "—", 40, FontWeight.Light, lineHeight = 46)
                    }
                    com.sharkhome.launcher.ui.components.ChevronButton(com.sharkhome.launcher.ui.components.Ic.Plus, "Raise target", {
                        target?.let { car.set("energy.socTarget", (it + 5).coerceIn(25.0, 70.0)) }
                    }, size = 56.dp, iconSize = 24.dp)
                }
                com.sharkhome.launcher.ui.components.ThinBar(target?.let { (it / 100).toFloat() }, color = accent, height = 6.dp)
                com.sharkhome.launcher.ui.components.T("Last 50 km: where the energy went", 14, color = C.InkSecondary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    com.sharkhome.launcher.ui.components.LabelValue("Driving", car.n("energy.drivePct50km").fmt(0, " %"), Modifier.weight(1f), valueSize = 20, labelSize = 13)
                    com.sharkhome.launcher.ui.components.LabelValue("A/C", car.n("energy.acPct50km").fmt(0, " %"), Modifier.weight(1f), valueSize = 20, labelSize = 13)
                    com.sharkhome.launcher.ui.components.LabelValue("Other", car.n("energy.auxPct50km").fmt(0, " %"), Modifier.weight(1f), valueSize = 20, labelSize = 13)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    com.sharkhome.launcher.ui.components.LabelValue("Lifetime EV", car.n("vehicle.evMileage").fmt(0, " km"), Modifier.weight(1f), valueSize = 20, labelSize = 13)
                    com.sharkhome.launcher.ui.components.LabelValue("Lifetime HEV", car.n("vehicle.hevMileage").fmt(0, " km"), Modifier.weight(1f), valueSize = 20, labelSize = 13)
                    com.sharkhome.launcher.ui.components.LabelValue("Power source", car.label("vehicle.powerMode") ?: "—", Modifier.weight(1f), valueSize = 20, labelSize = 13)
                }
                com.sharkhome.launcher.ui.components.Hairline()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    com.sharkhome.launcher.ui.components.LabelValue("HV battery", car.n("vehicle.batteryPct").fmt(0, " %"), Modifier.weight(1f), valueSize = 20, labelSize = 13)
                    com.sharkhome.launcher.ui.components.LabelValue("12V battery", car.n("battery.voltage").fmt(1, " V"), Modifier.weight(1f), valueSize = 20, labelSize = 13)
                    com.sharkhome.launcher.ui.components.LabelValue("Battery health", car.n("vehicle.batteryHealth").fmt(0, " %"), Modifier.weight(1f), valueSize = 20, labelSize = 13)
                }
            }
        }

        Row(Modifier.fillMaxWidth().height(206.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Windows: open / close with the same codes BYD's voice assistant sends (BYDAutoBodyworkDevice.setWindowControlState).
            // The Shark 6 has no adjustable rear windows, so only the two front ones are shown.
            com.sharkhome.launcher.ui.components.DCard(Modifier.weight(2f).fillMaxHeight(), padding = 18.dp, spacing = 10.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.sharkhome.launcher.ui.components.T("Windows", 22, FontWeight.SemiBold)
                    com.sharkhome.launcher.ui.components.T("They move slowly. Tap the other button to reverse.", 14, color = C.InkMuted)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WINDOWS.forEach { w ->
                        val pct = car.n(w.readKey)
                        Column(
                            Modifier.weight(1f).clip(com.sharkhome.launcher.ui.components.Radius4)
                                .border(1.dp, C.Line, com.sharkhome.launcher.ui.components.Radius4).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                com.sharkhome.launcher.ui.components.T(w.label, 18, FontWeight.Medium, modifier = Modifier.weight(1f))
                                com.sharkhome.launcher.ui.components.T(pct?.let { "${it.toInt().coerceIn(0, 100)} % open" } ?: "—", 14, color = C.InkMuted)
                            }
                            com.sharkhome.launcher.ui.components.ThinBar(pct?.let { (it / 100).toFloat() }, color = accent, height = 6.dp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                com.sharkhome.launcher.ui.components.DButton("Open", { car.setLabel(w.ctlKey, "open") }, Modifier.weight(1f), height = 48.dp, textSize = 16)
                                com.sharkhome.launcher.ui.components.DButton("Close", { car.setLabel(w.ctlKey, "closed") }, Modifier.weight(1f), height = 48.dp, textSize = 16)
                            }
                        }
                    }
                }
            }
            // Screen brightness of the centre display (Android's screen_brightness setting, written through the unit's own adb).
            com.sharkhome.launcher.ui.components.DCard(Modifier.weight(1f).fillMaxHeight(), padding = 18.dp, spacing = 10.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.sharkhome.launcher.ui.components.DsIcon(com.sharkhome.launcher.ui.components.Ic.Brightness, accent, 28.dp)
                    com.sharkhome.launcher.ui.components.T("Screen brightness", 22, FontWeight.SemiBold)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    com.sharkhome.launcher.ui.components.ChevronButton(com.sharkhome.launcher.ui.components.Ic.Minus, "Dimmer", { setBrightness(brightness - 15) }, size = 56.dp, iconSize = 24.dp)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        com.sharkhome.launcher.ui.components.T("${(brightness * 100 / 255)} %", 40, FontWeight.Light, lineHeight = 46)
                    }
                    com.sharkhome.launcher.ui.components.ChevronButton(com.sharkhome.launcher.ui.components.Ic.Plus, "Brighter", { setBrightness(brightness + 15) }, size = 56.dp, iconSize = 24.dp)
                }
                com.sharkhome.launcher.ui.components.ThinBar(brightness / 255f, color = accent, height = 6.dp)
            }
        }
    }
}

private fun readScreenBrightness(ctx: android.content.Context): Int =
    runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, "screen_brightness") }.getOrDefault(128)

/** One tile on the Quick controls screen (a car signal or an Android setting). */
private data class ControlTile(val label: String, val icon: com.sharkhome.launcher.ui.components.Ic, val on: Boolean, val onClick: () -> Unit)

/** A row of equal-width tiles; empty slots keep the width of the last tiles when a row isn't full. */
@Composable
private fun ControlTileRow(tiles: List<ControlTile>, columns: Int, height: androidx.compose.ui.unit.Dp, accent: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.forEach { t ->
            val fg = if (t.on) androidx.compose.ui.graphics.Color.White else C.Ink
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(com.sharkhome.launcher.ui.components.Radius4)
                    .background(if (t.on) accent else C.SurfaceCard)
                    .border(1.dp, if (t.on) accent else C.Line, com.sharkhome.launcher.ui.components.Radius4)
                    .clickable { t.onClick() }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    com.sharkhome.launcher.ui.components.DsIcon(t.icon, if (t.on) fg else C.InkSecondary, 34.dp)
                    Box(Modifier.weight(1f))
                    com.sharkhome.launcher.ui.components.T(if (t.on) "ON" else "OFF", 13, FontWeight.SemiBold, if (t.on) fg else C.InkMuted)
                }
                com.sharkhome.launcher.ui.components.T(t.label, 19, FontWeight.SemiBold, color = fg, maxLines = 1)
            }
        }
        repeat((columns - tiles.size).coerceAtLeast(0)) { Box(Modifier.weight(1f)) }
    }
}

private fun carToggle(id: String, label: String, icon: com.sharkhome.launcher.ui.components.Ic, key: String) =
    QuickTile(id, label, icon, key, { it.on(key) }, { it.toggle(key) })

/** Second row of Quick controls: toggles whose read and write were both confirmed on the car (see CHANGELOG v0.6.774). */
private val MORE_TILES = listOf(
    carToggle("closelock", "Close on Lock", com.sharkhome.launcher.ui.components.Ic.Window, "locks.lockCloseWindows"),
    carToggle("closerain", "Close in Rain", com.sharkhome.launcher.ui.components.Ic.Rain, "locks.rainCloseWindows"),
    carToggle("wiper", "Auto Wiper", com.sharkhome.launcher.ui.components.Ic.Wiper, "vehicle.autoWiper"),
    carToggle("courtesy", "Courtesy Light", com.sharkhome.launcher.ui.components.Ic.Headlight, "light.courtesyLight"),
    carToggle("autolock", "Auto Lock", com.sharkhome.launcher.ui.components.Ic.Lock, "locks.autoLock"),
    carToggle("reardemist", "Rear Demist", com.sharkhome.launcher.ui.components.Ic.RearDemist, "climate.defrostRear"),
)

/** A window: where it is read (open %), and the write-only signal that moves it (see shark6_codes.json window.ctl.*). */
private data class WindowSpec(val label: String, val readKey: String, val ctlKey: String)

private val WINDOWS = listOf(
    WindowSpec("Front left", "window.frontLeft", "window.ctl.frontLeft"),
    WindowSpec("Front right", "window.frontRight", "window.ctl.frontRight"),
)

private fun launchBydPackage(ctx: android.content.Context, label: String, pkg: String) {
    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
    if (intent == null) {
        Toast.makeText(ctx, "$label isn't available on this unit", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, "Couldn't open $label", Toast.LENGTH_SHORT).show() }
}

/**
 * BYD settings tab (Controls › BYD settings): verified read/write BYD vehicle settings and HUD tuning.
 * Every control shown here passed live read-back confirmation (confirmed=true) on the Shark 6.
 */
@Composable
fun BydSettingsScreen() {
    val ctx = LocalContext.current
    val c = ctx.container
    val car = com.sharkhome.launcher.ui.components.rememberCar()
    val lastWrite by c.vehicle.lastWrite.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                com.sharkhome.launcher.ui.components.T("BYD settings", 32, FontWeight.Medium)
                com.sharkhome.launcher.ui.components.T("Direct controls verified against the Shark 6 ECU. Changes are read back from the car.", 15, color = C.InkMuted)
            }
            lastWrite?.let { w ->
                com.sharkhome.launcher.ui.components.T(
                    if (w.confirmed) "Confirmed by car" else w.message,
                    15,
                    FontWeight.Medium,
                    if (w.confirmed) C.Ok else C.InkSecondary,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Left column: Drive/Energy Memory + Locks, Windows, Wipers & Lights
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                com.sharkhome.launcher.ui.components.DCard(Modifier.fillMaxWidth(), spacing = 12.dp) {
                    com.sharkhome.launcher.ui.components.Eyebrow("Drive & energy memory")
                    BydSwitchRow(
                        title = "SAVE mode memory",
                        subtitle = "Keep SOC hold state across restarts",
                        car = car,
                        key = "energy.saveMemory",
                    )
                    com.sharkhome.launcher.ui.components.Hairline()
                    BydSwitchRow(
                        title = "SPORT mode memory",
                        subtitle = "Keep Sport drive mode across restarts",
                        car = car,
                        key = "energy.sportMemory",
                    )
                    com.sharkhome.launcher.ui.components.Hairline()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            com.sharkhome.launcher.ui.components.T("Smart / scheduled charging", 18, FontWeight.Medium)
                            com.sharkhome.launcher.ui.components.T("Set charge windows and target SOC in BYD Smart Charging", 14, color = C.InkMuted)
                        }
                        com.sharkhome.launcher.ui.components.DButton(
                            "Open Smart Charging",
                            { launchBydPackage(ctx, "Smart Charging", "com.byd.smarttravel") },
                            height = 48.dp,
                            textSize = 15,
                        )
                    }
                }

                com.sharkhome.launcher.ui.components.DCard(Modifier.fillMaxWidth(), spacing = 12.dp) {
                    com.sharkhome.launcher.ui.components.Eyebrow("Locks, windows & wipers")
                    BydSwitchRow(
                        title = "Auto lock while driving",
                        subtitle = "Lock doors automatically at speed",
                        car = car,
                        key = "locks.autoLock",
                    )
                    com.sharkhome.launcher.ui.components.Hairline()
                    BydSwitchRow(
                        title = "Auto close windows in rain",
                        subtitle = "Raise windows automatically when rain is detected",
                        car = car,
                        key = "locks.rainCloseWindows",
                    )
                    com.sharkhome.launcher.ui.components.Hairline()
                    BydSwitchRow(
                        title = "Auto close windows on lock",
                        subtitle = "Close open windows automatically after locking the car",
                        car = car,
                        key = "locks.lockCloseWindows",
                    )
                    com.sharkhome.launcher.ui.components.Hairline()
                    BydSwitchRow(
                        title = "Auto rain wiper",
                        subtitle = "Automatic front wiper rain sensing",
                        car = car,
                        key = "vehicle.autoWiper",
                    )
                    com.sharkhome.launcher.ui.components.Hairline()
                    BydSwitchRow(
                        title = "Intelligent courtesy light",
                        subtitle = "Welcome lighting when approaching or unlocking",
                        car = car,
                        key = "light.courtesyLight",
                    )
                }
            }

            // Right column: Head-Up Display (HUD)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                com.sharkhome.launcher.ui.components.DCard(Modifier.fillMaxWidth(), spacing = 12.dp) {
                    com.sharkhome.launcher.ui.components.Eyebrow("Head-up display (HUD)")
                    BydSwitchRow(
                        title = "HUD master",
                        subtitle = "Windshield head-up projection",
                        car = car,
                        key = "advanced.hud",
                    )
                    com.sharkhome.launcher.ui.components.Hairline()
                    BydSwitchRow(
                        title = "Snow mode",
                        subtitle = "High-contrast blue/orange HUD palette",
                        car = car,
                        key = "advanced.hudSnow",
                    )
                    com.sharkhome.launcher.ui.components.Hairline()
                    BydStepperRow(
                        title = "Height",
                        subtitle = "Vertical projection position (−10 to +10)",
                        value = car.n("hud.height"),
                        min = -10.0,
                        max = 10.0,
                        format = { v -> val i = v.toInt(); if (i > 0) "+$i" else "$i" },
                        onStep = { car.set("hud.height", it) },
                    )
                    com.sharkhome.launcher.ui.components.Hairline()
                    BydStepperRow(
                        title = "Brightness",
                        subtitle = "Display luminance level (1 to 11)",
                        value = car.n("hud.brightness"),
                        min = 1.0,
                        max = 11.0,
                        format = { v -> "${v.toInt()}" },
                        onStep = { car.set("hud.brightness", it) },
                    )
                    com.sharkhome.launcher.ui.components.Hairline()
                    BydStepperRow(
                        title = "Angle",
                        subtitle = "Horizon tilt adjustment (−5° to +5°)",
                        value = car.n("hud.angle"),
                        min = -5.0,
                        max = 5.0,
                        format = { v -> val i = v.toInt(); if (i > 0) "+${i}°" else "${i}°" },
                        onStep = { car.set("hud.angle", it) },
                    )
                    com.sharkhome.launcher.ui.components.Hairline()
                    BydSwitchRow(
                        title = "Show ADAS info on HUD",
                        subtitle = "Lane keeping, cruise & driver-assist graphics",
                        car = car,
                        key = "hud.adas",
                    )
                    com.sharkhome.launcher.ui.components.Hairline()
                    BydSwitchRow(
                        title = "Show navigation on HUD",
                        subtitle = "Turn-by-turn navigation prompts",
                        car = car,
                        key = "hud.navi",
                    )
                }
            }
        }
    }
}

@Composable
private fun BydSwitchRow(
    title: String,
    subtitle: String,
    car: com.sharkhome.launcher.ui.components.Car,
    key: String,
) {
    val available = car.available(key)
    val on = car.on(key)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            com.sharkhome.launcher.ui.components.T(title, 18, FontWeight.Medium)
            com.sharkhome.launcher.ui.components.T(if (available) subtitle else "Not reported by this unit", 14, color = C.InkMuted)
        }
        com.sharkhome.launcher.ui.components.T(
            if (!available) "—" else if (on) "ON" else "OFF",
            14,
            FontWeight.SemiBold,
            if (!available) C.InkSubtle else if (on) LocalAccent.current else C.InkMuted,
        )
        com.sharkhome.launcher.ui.components.PillSwitch(on, {
            if (available) car.setLabel(key, if (it) "on" else "off")
        })
    }
}

@Composable
private fun BydStepperRow(
    title: String,
    subtitle: String,
    value: Double?,
    min: Double,
    max: Double,
    format: (Double) -> String,
    onStep: (Double) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            com.sharkhome.launcher.ui.components.T(title, 18, FontWeight.Medium)
            com.sharkhome.launcher.ui.components.T(subtitle, 14, color = C.InkMuted)
        }
        com.sharkhome.launcher.ui.components.ChevronButton(
            com.sharkhome.launcher.ui.components.Ic.Minus,
            "Decrease $title",
            { value?.let { onStep((it - 1.0).coerceIn(min, max)) } },
            size = 48.dp,
            iconSize = 20.dp,
        )
        Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
            com.sharkhome.launcher.ui.components.T(
                value?.let(format) ?: "—",
                22,
                FontWeight.SemiBold,
                if (value == null) C.InkSubtle else C.Ink,
            )
        }
        com.sharkhome.launcher.ui.components.ChevronButton(
            com.sharkhome.launcher.ui.components.Ic.Plus,
            "Increase $title",
            { value?.let { onStep((it + 1.0).coerceIn(min, max)) } },
            size = 48.dp,
            iconSize = 20.dp,
        )
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
                // The selected option is always in the accent colour, "Off" included: a grey "selected Off" looked
                // the same as an unselected button in the dark theme (owner, 20 Nov 2026).
                val bg = if (active) LocalAccent.current else C.P.btnLightest
                Box(
                    Modifier.weight(1f).height(60.dp).clip(Shape10)
                        .background(bg)
                        .clickable { onClick() },
                    contentAlignment = Alignment.Center,
                ) { Txt(label, 18, if (active) FontWeight.Medium else FontWeight.Normal, if (active) androidx.compose.ui.graphics.Color.White else C.Text3) }
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
                        Box(
                            Modifier.weight(1f).height(44.dp)
                                .clip(com.sharkhome.launcher.ui.components.Radius4)
                                .background(if (active) accent else C.P.btnLightest)
                                .border(1.dp, if (active) accent else C.Line, com.sharkhome.launcher.ui.components.Radius4)
                                .clickable { onSet(master.key, opt) },
                            contentAlignment = Alignment.Center,
                        ) {
                            com.sharkhome.launcher.ui.components.T(
                                opt.replaceFirstChar { it.uppercase() }, 13,
                                if (active) FontWeight.SemiBold else FontWeight.Medium,
                                if (active) androidx.compose.ui.graphics.Color.White else C.InkSecondary,
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
