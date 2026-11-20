package com.sharkhome.launcher.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharkhome.launcher.Screen
import com.sharkhome.launcher.car.ReportExporter
import com.sharkhome.launcher.container
import com.sharkhome.launcher.system.AppPermissions
import com.sharkhome.launcher.system.AppUpdater
import com.sharkhome.launcher.system.LocalAdb
import kotlinx.coroutines.launch
import com.sharkhome.launcher.ui.AccentChoices
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent
import com.sharkhome.launcher.ui.components.BtnStyle
import com.sharkhome.launcher.ui.components.CarView
import com.sharkhome.launcher.ui.components.DButton
import com.sharkhome.launcher.ui.components.DsIcon
import com.sharkhome.launcher.ui.components.Hairline
import com.sharkhome.launcher.ui.components.Ic
import com.sharkhome.launcher.ui.components.ChevronButton
import com.sharkhome.launcher.ui.components.PillSwitch
import com.sharkhome.launcher.ui.components.Radius4
import com.sharkhome.launcher.ui.components.T
import com.sharkhome.launcher.ui.components.ThinBar
import com.sharkhome.launcher.ui.components.Car
import com.sharkhome.launcher.ui.components.CarImage
import com.sharkhome.launcher.ui.components.Panel
import com.sharkhome.launcher.ui.components.Segmented
import com.sharkhome.launcher.ui.components.Shape10
import com.sharkhome.launcher.ui.components.SmallButton
import com.sharkhome.launcher.ui.components.StatRow
import com.sharkhome.launcher.ui.components.Txt
import com.sharkhome.launcher.ui.components.fmt
import com.sharkhome.launcher.ui.components.prefsTick
import com.sharkhome.launcher.ui.components.rememberCar

private enum class SettingsTab(val label: String, val icon: Ic) {
    DISPLAY("Display & themes", Ic.Display),
    PERMISSIONS("Permissions & ADB", Ic.Shield),
    INSTALL("Install apps", Ic.Download),
    STARTUP("Startup memory", Ic.Sparkle),
    DIAGNOSTICS("Diagnostics", Ic.Diagnostics),
    TEST("Test", Ic.Sliders),
    UPDATES("Updates", Ic.About),
}

/** Settings: the things you set up once. A tab strip across the top, one page below. */
@Composable
fun SettingsScreen(onNavigate: (Screen) -> Unit, initialTab: Int = 0) {
    var tab by remember(initialTab) { mutableStateOf(SettingsTab.entries[initialTab.coerceIn(0, SettingsTab.entries.size - 1)]) }
    val hasUpdate by LocalContext.current.container.updater.hasUpdate.collectAsState()
    Column(Modifier.fillMaxSize().padding(end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        com.sharkhome.launcher.ui.components.NavTabStrip(
            SettingsTab.entries.map { com.sharkhome.launcher.ui.components.StripTab(it.label, it.icon, badge = it == SettingsTab.UPDATES && hasUpdate) },
            tab.ordinal, { tab = SettingsTab.entries[it] },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                SettingsTab.DISPLAY -> Box(Modifier.padding(top = 8.dp)) { DisplayTab() }
                SettingsTab.PERMISSIONS -> UtilitiesScreen()
                SettingsTab.INSTALL -> InstallScreen()
                SettingsTab.STARTUP -> AdvancedSettingsScreen(onNavigate, part = 1)
                SettingsTab.DIAGNOSTICS -> Box(Modifier.padding(top = 8.dp)) { DiagnosticsTab() }
                SettingsTab.TEST -> Box(Modifier.padding(top = 8.dp)) { TestLabScreen() }
                SettingsTab.UPDATES -> Box(Modifier.padding(top = 8.dp)) { AboutTab() }
            }
        }
    }
}

/** Controls: quick toggles, driver-assist settings BYD resets, and verified BYD vehicle settings. */
@Composable
fun ControlsScreen(onNavigate: (Screen) -> Unit, initialTab: Int = 0) {
    var tab by remember(initialTab) { mutableStateOf(initialTab.coerceIn(0, 2)) }
    Column(Modifier.fillMaxSize().padding(end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        com.sharkhome.launcher.ui.components.NavTabStrip(
            listOf(
                com.sharkhome.launcher.ui.components.StripTab("Quick controls", Ic.Sliders),
                com.sharkhome.launcher.ui.components.StripTab("Startup controls", Ic.SteeringWheel),
                com.sharkhome.launcher.ui.components.StripTab("BYD settings", Ic.Gear),
            ),
            tab, { tab = it },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> QuickSettingsScreen(onNavigate)
                1 -> AdvancedSettingsScreen(onNavigate, part = 0)
                else -> BydSettingsScreen()
            }
        }
    }
}

/** Car: status and fuel at a glance. */
@Composable
fun CarScreen() {
    Box(Modifier.fillMaxSize().padding(end = 24.dp, bottom = 24.dp, top = 8.dp)) { VehicleTab() }
}

@Composable
private fun VehicleTab() {
    val car = rememberCar()
    val c = LocalContext.current.container
    val adapterStatus by c.carAdapter.status.collectAsState()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            T("Car", 32, FontWeight.Medium, lineHeight = 38)
            // Top view, front facing left: the car's right side is at the top.
            Box(Modifier.fillMaxWidth().height(348.dp).border(1.dp, C.Line).padding(24.dp)) {
                CarImage(Modifier.fillMaxSize().padding(horizontal = 140.dp, vertical = 8.dp), CarView.TOP)
                TyreReading("Front right", car.n("tyre.fr"), Modifier.align(Alignment.TopStart), Alignment.Start)
                TyreReading("Rear right", car.n("tyre.rr"), Modifier.align(Alignment.TopEnd), Alignment.End)
                TyreReading("Front left", car.n("tyre.fl"), Modifier.align(Alignment.BottomStart), Alignment.Start)
                TyreReading("Rear left", car.n("tyre.rl"), Modifier.align(Alignment.BottomEnd), Alignment.End)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatBox("Battery", car.n("vehicle.batteryPct").fmt(0, "%"), car.n("vehicle.batteryPct"), "HV battery", Modifier.weight(1f))
                StatBox("Fuel", car.n("vehicle.fuelPct").fmt(0, "%"), car.n("vehicle.fuelPct"), "Fuel gauge", Modifier.weight(1f))
                StatBox("Energy use", car.n("vehicle.consumption").fmt(1), null, "kWh/100 km, last 50 km", Modifier.weight(1f))
            }
            // Extended Fuel Tank & Real-Range Estimator
            ExtendedFuelPanel(car)
            Column {
                Hairline(color = C.LineStrong)
                DetailRow("Odometer", car.n("vehicle.odometerKm").fmt(0, " km"))
                DetailRow("Trip A", car.n("trip.aKm").fmt(1, " km"))
                DetailRow("Trip B", car.n("trip.bKm").fmt(1, " km"))
                DetailRow("Lifetime EV / HEV Split", "${car.n("vehicle.evMileage").fmt(0, " km")} EV · ${car.n("vehicle.hevMileage").fmt(0, " km")} HEV")
                DetailRow("12V Auxiliary Battery", car.n("battery.voltage").fmt(1, " V"))
                DetailRow("HV Battery Health", car.n("vehicle.batteryHealth").fmt(0, "%"))
                DetailRow("SOC Hold Target", car.n("energy.socTarget").fmt(0, "%"))
                DetailRow("Power source", car.readable("vehicle.powerMode"))
                DetailRow("Drive mode", car.readable("drive.roadMode"))
                DetailRow("Avg fuel (50 km)", car.n("vehicle.fuelAvg").fmt(1, " L/100 km"))
                DetailRow(
                    "50 km Energy Breakdown",
                    "Drive ${car.n("energy.drivePct50km").fmt(0, "%")} · A/C ${car.n("energy.acPct50km").fmt(0, "%")} · Aux ${car.n("energy.auxPct50km").fmt(0, "%")}",
                )
            }
        }
        Column(
            Modifier.width(520.dp).fillMaxHeight().padding(bottom = 16.dp).clip(Radius4).background(C.SurfacePanel).border(1.dp, C.Line, Radius4).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Car connection", 24, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                T(android.os.Build.DISPLAY.take(24), 14, color = C.InkMuted)
            }
            T(adapterStatus, 14, color = C.InkMuted, maxLines = 2)
            LazyColumn(Modifier.weight(1f)) {
                items(c.carAdapter.adapters.values.toList()) { a ->
                    ProbeRow(a.name, a.descriptor ?: a.error ?: "", if (a.iface != null) Status.OK else Status.OFF)
                }
                items(c.bydAuto.loaded().keys.toList()) { k -> ProbeRow("bydauto $k", "loaded", Status.OK) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DButton("Run probe again", { c.vehicle.reprobe() }, Modifier.weight(1f))
                val ctx = LocalContext.current
                DButton("Export report", {
                    Toast.makeText(ctx, ReportExporter.save(ctx, c.vehicle.report()), Toast.LENGTH_LONG).show()
                }, Modifier.weight(1f), style = BtnStyle.Primary)
            }
        }
    }
}

@Composable
private fun TyreReading(label: String, psi: Double?, modifier: Modifier, align: Alignment.Horizontal) {
    Column(modifier, horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        T(label, 14, color = C.InkMuted)
        T(psi.fmt(0, " psi"), 24, FontWeight.SemiBold)
    }
}

/**
 * Fuel in litres. The car only reports the gauge %, so litres = % × the tank size set here (Shark 6: 60 L).
 * (Replaces a panel that assumed a hidden 6 L reserve below 0%; nothing on the car backs that up.)
 */
@Composable
private fun ExtendedFuelPanel(car: Car) {
    val c = LocalContext.current.container
    prefsTick()
    val tank = c.prefs.fuelTankL
    val fuelPct = car.n("vehicle.fuelPct")
    val litres = fuelPct?.let { it / 100.0 * tank }
    Column(
        Modifier.fillMaxWidth().clip(Radius4).background(C.SurfacePanel).border(1.dp, C.Line, Radius4).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T("Fuel in litres", 20, FontWeight.SemiBold)
                T("Estimated from the fuel gauge and your tank size. The car's own fuel range is the one to trust near empty.", 14, color = C.InkMuted, maxLines = 2)
            }
            T("Tank", 16, color = C.InkSecondary)
            ChevronButton(Ic.Minus, "Smaller tank", { c.prefs.fuelTankL = (tank - 1).coerceAtLeast(20.0) }, size = 48.dp, iconSize = 20.dp)
            T("${tank.fmt(0)} L", 22, FontWeight.Medium)
            ChevronButton(Ic.Plus, "Bigger tank", { c.prefs.fuelTankL = (tank + 1).coerceAtMost(150.0) }, size = 48.dp, iconSize = 20.dp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                T("Fuel left (approx.)", 14, color = C.InkMuted)
                T(litres.fmt(1, " L"), 28, FontWeight.Light, lineHeight = 34)
                T("Gauge ${fuelPct.fmt(0, "%")}", 14, color = C.InkSecondary)
            }
            Column(Modifier.weight(1f)) {
                T("Real range", 14, color = C.InkMuted)
                T(car.totalKm().fmt(0, " km"), 28, FontWeight.Light, lineHeight = 34)
                T("EV + fuel combined", 14, color = C.InkSecondary)
            }
            Column(Modifier.weight(1f)) {
                T("Trip fuel used", 14, color = C.InkMuted)
                T(car.n("trip.fuel").fmt(1, " L"), 28, FontWeight.Light, lineHeight = 34)
                T("Trip ${car.n("trip.distance").fmt(1, " km")}", 13, color = C.InkSecondary)
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, pct: Double?, sub: String, modifier: Modifier) {
    Column(modifier.border(1.dp, C.Line, Radius4).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T(label, 16, color = C.InkMuted)
        T(value, 36, FontWeight.Light, lineHeight = 46)
        if (pct != null) ThinBar((pct / 100).toFloat())
        T(sub, 16, color = C.InkSecondary)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 16, color = C.InkSecondary, modifier = Modifier.weight(1f))
        T(value, 16, FontWeight.Medium)
    }
    Hairline()
}

@Composable
private fun DisplayTab() {
    val ctx = LocalContext.current
    val c = ctx.container
    val p = c.prefs
    prefsTick()
    val all by c.apps.apps.collectAsState()

    fun persist(uri: Uri) = runCatching {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val pickCar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { persist(uri); p.carImageUri = uri.toString() }
    }
    var showNavPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Txt("Display & themes", 32, FontWeight.Medium)
        SettingRow("Driver's seat", "Puts the driver's controls on this side of the screen") {
            Segmented(listOf("Left", "Right"), if (p.driverOnRight) 1 else 0, { p.driverOnRight = it == 1 }, Modifier.width(320.dp))
        }
        SettingRow("Menu position", "Where the navigation bar sits: the side, the top or the bottom of the screen") {
            val pos = listOf("left", "right", "top", "bottom")
            Segmented(listOf("Left", "Right", "Top", "Bottom"), pos.indexOf(p.navPosition).coerceAtLeast(0), { p.navPosition = pos[it] }, Modifier.width(460.dp))
        }
        SettingRow("Speedometer readout source", if (p.useGpsSpeed) "GPS speed (satellite)" else "CAN wheel speed (vehicle cluster)") {
            Segmented(
                listOf("CAN Wheel Speed", "GPS Speed"),
                if (p.useGpsSpeed) 1 else 0,
                { p.useGpsSpeed = (it == 1) },
                Modifier.width(360.dp),
            )
        }
        SettingRow("Full screen mode", "Hide BYD's bottom system bar for an edge-to-edge launcher") {
            Segmented(
                listOf("Hidden (Full screen)", "Standard"),
                if (p.fullScreenMode) 0 else 1,
                { p.fullScreenMode = (it == 0) },
                Modifier.width(360.dp),
            )
        }
        SettingRow("Theme", "Auto follows the car's own light or dark theme") {
            val modes = listOf("auto", "light", "dark", "shark")
            Segmented(listOf("Auto", "Light", "Dark", "Shark blue"), modes.indexOf(p.themeMode).coerceAtLeast(0), { p.themeMode = modes[it] }, Modifier.width(620.dp))
        }
        SettingRow("Accent colour", "Used for anything switched on") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentChoices.forEach { (argb, name) ->
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(Color(argb))
                            .border(3.dp, if (p.accent == argb) C.Text else Color.Transparent, CircleShape)
                            .clickable { p.accent = argb },
                    )
                }
            }
        }
        SettingRow("Car colour", (com.sharkhome.launcher.ui.components.CarPaints.firstOrNull { it.id == p.carColor } ?: com.sharkhome.launcher.ui.components.CarPaints[0]).let { "${it.name} · ${it.market}" } + ". Recolours the pictures of your car; a close preview of BYD's paints.") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                com.sharkhome.launcher.ui.components.CarPaints.forEach { paint ->
                    val sel = (p.carColor ?: "deep_sea_blue") == paint.id
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(paint.swatch)
                            .border(if (sel) 3.dp else 1.dp, if (sel) C.Text else C.Line, CircleShape)
                            .clickable { p.carColor = paint.id },
                    )
                }
            }
        }
        SettingRow("Picture of your ute", "Replaces the built-in side view. A side-on photo with the background removed looks best") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CarImage(Modifier.width(180.dp).height(70.dp))
                SmallButton("Choose photo", { pickCar.launch(arrayOf("image/*")) })
                SmallButton("Use built-in", { p.carImageUri = null })
            }
        }
        SettingRow("Navigation app", all.firstOrNull { it.pkg == p.navPackage }?.label ?: "Any map app") {
            SmallButton(if (showNavPicker) "Close" else "Choose", { showNavPicker = !showNavPicker })
        }
        SettingRow("Map widget source", "Auto projects Android Auto when Google Maps / Waze or routing is active") {
            val sources = listOf("auto", "androidauto", "osm")
            Segmented(
                listOf("Auto (AA maps)", "Always AA", "Local map"),
                sources.indexOf(p.mapSource).coerceAtLeast(0),
                { p.mapSource = sources[it]; c.media.clearProjectionError() },
                Modifier.width(460.dp),
            )
        }
        SettingRow("Android Auto map projection", "Map Only shows Google Maps / Waze without media apps; Full AA mirrors the AA screen") {
            val modes = listOf("cluster", "main", "tbt")
            Segmented(
                listOf("Map Only (AA)", "Full AA Screen", "Turn-by-Turn"),
                modes.indexOf(p.aaProjectionMode).coerceAtLeast(0),
                { p.aaProjectionMode = modes[it]; c.media.clearProjectionError() },
                Modifier.width(460.dp),
            )
        }
        if (showNavPicker) {
            Panel(color = C.Card2, spacing = 4.dp) {
                all.forEach { app ->
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).clip(Shape10).clickable { p.navPackage = app.pkg; showNavPicker = false }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Txt(app.label, 17, if (app.pkg == p.navPackage) FontWeight.Medium else FontWeight.Normal) }
                }
            }
        }
        SettingRow("Home address", "Used by the Home button on the dashboard map") {
            TextBox(p.homeAddress, { p.homeAddress = it }, Modifier.width(560.dp))
        }
        SettingRow("Work address", "Used by the Work button") {
            TextBox(p.workAddress, { p.workAddress = it }, Modifier.width(560.dp))
        }
        SettingRow("Max wading depth (mm)", "Shown on the Off-road page. Check your owner's manual") {
            TextBox(if (p.wadingDepthMm > 0) p.wadingDepthMm.toString() else "", { p.wadingDepthMm = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0 }, Modifier.width(200.dp), numeric = true)
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Shape10).background(C.Card).border(1.dp, C.Line, Shape10).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(title, 18, FontWeight.Medium)
            Txt(subtitle, 14, color = C.Text2, maxLines = 2)
        }
        control()
    }
}

@Composable
private fun TextBox(value: String, onChange: (String) -> Unit, modifier: Modifier, numeric: Boolean = false) {
    var text by remember(value) { mutableStateOf(value) }
    Box(modifier.height(52.dp).clip(Shape10).background(C.Card2).border(1.dp, C.Line, Shape10).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        BasicTextField(
            text,
            { text = it; onChange(it) },
            singleLine = true,
            textStyle = TextStyle(color = C.Text, fontSize = 17.sp),
            cursorBrush = SolidColor(C.Text),
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DiagnosticsTab() {
    var subTab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Txt("Diagnostics", 32, FontWeight.Medium)
            Segmented(listOf("System Diagnostics", "Car Explorer"), subTab, { subTab = it }, Modifier.width(420.dp))
        }
        if (subTab == 1) {
            ExplorerScreen()
        } else {
            DiagnosticsSystemView()
        }
    }
}

@Composable
private fun DiagnosticsSystemView() {
    val ctx = LocalContext.current
    val c = ctx.container
    val car = rememberCar()
    val adapterStatus by c.carAdapter.status.collectAsState()
    val mediaAccess by c.media.hasAccess.collectAsState()
    val motion by c.motion.motion.collectAsState()
    val aaAppReady by c.media.aaAppServiceReady.collectAsState()
    val aaSvcReady by c.media.aaServiceReady.collectAsState()
    val aaConnected by c.media.androidAutoConnected.collectAsState()
    val aaNavApp by c.media.aaNavAppActive.collectAsState()
    val aaProjectionError by c.media.aaProjectionError.collectAsState()

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(560.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Txt("Car connection", 24, FontWeight.Medium)
            Panel(Modifier.weight(1f).fillMaxWidth(), spacing = 0.dp) {
                Txt(adapterStatus, 15, color = C.Text2, maxLines = 2, modifier = Modifier.padding(bottom = 8.dp))
                LazyColumn {
                    val adapters = c.carAdapter.adapters.values.toList()
                    items(adapters) { a ->
                        ProbeRow(a.name, a.descriptor ?: a.error ?: "", if (a.iface != null) Status.OK else Status.OFF)
                    }
                    val loaded = c.bydAuto.loaded()
                    val failed = c.bydAuto.failed()
                    items(loaded.keys.toList()) { k -> ProbeRow("bydauto $k", "loaded", Status.OK) }
                    items(failed.keys.toList()) { k -> ProbeRow("bydauto $k", failed[k] ?: "", Status.OFF) }
                    item { ProbeRow("Media sessions", "notification listener", if (mediaAccess) Status.OK else Status.OFF) }
                    item {
                        val appDetail = when {
                            !aaAppReady -> "IAndroidAutoAppManager unavailable"
                            aaNavApp -> "AA session active · navigation/map active"
                            aaConnected -> "AA session active"
                            else -> "IAndroidAutoAppManager ready · idle"
                        }
                        ProbeRow("Android Auto App", appDetail, if (aaAppReady) Status.OK else Status.OFF)
                    }
                    item {
                        val videoDetail = when {
                            aaProjectionError != null -> "Error: $aaProjectionError"
                            aaSvcReady -> "IAndroidAutoServiceManager connected"
                            else -> "IAndroidAutoServiceManager unavailable"
                        }
                        ProbeRow("Android Auto Video", videoDetail, if (aaSvcReady && aaProjectionError == null) Status.OK else Status.OFF)
                    }
                    item { ProbeRow("GPS", "location", if (motion.hasGps) Status.OK else Status.OFF) }
                    item { ProbeRow("Tilt sensor", "accelerometer", if (motion.hasTiltSensor) Status.OK else Status.OFF) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallButton("Run probe again", { c.vehicle.reprobe() }, Modifier.weight(1f))
                SmallButton("Export report", {
                    Toast.makeText(ctx, ReportExporter.save(ctx, c.vehicle.report()), Toast.LENGTH_LONG).show()
                }, Modifier.weight(1f), primary = true)
                SmallButton("Share", { ReportExporter.share(ctx, c.vehicle.report()) }, Modifier.weight(1f))
            }
            val diagScope = rememberCoroutineScope()
            var mediaGrantBusy by remember { mutableStateOf(false) }
            var allGrantBusy by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!mediaAccess) {
                    SmallButton(
                        if (mediaGrantBusy) "Enabling media…" else "Allow media access (ADB)",
                        {
                            if (mediaGrantBusy || allGrantBusy) return@SmallButton
                            diagScope.launch {
                                mediaGrantBusy = true
                                c.media.enableAccessWithAdb()
                                mediaGrantBusy = false
                            }
                        },
                        Modifier.weight(1f),
                        primary = true,
                    )
                }
                SmallButton(
                    if (allGrantBusy) "Granting permissions…" else "Grant SharkOS-CE permissions (ADB)",
                    {
                        if (mediaGrantBusy || allGrantBusy) return@SmallButton
                        diagScope.launch {
                            allGrantBusy = true
                            val res = AppPermissions(ctx, c.adb).grantSelfAllPermissions()
                            c.media.start()
                            c.motion.start()
                            Toast.makeText(ctx, res.message, Toast.LENGTH_LONG).show()
                            allGrantBusy = false
                        }
                    },
                    Modifier.weight(1f),
                    primary = mediaAccess,
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Txt("Signals", 24, FontWeight.Medium)
            Panel(Modifier.weight(1f).fillMaxWidth(), spacing = 0.dp) {
                LazyColumn {
                    items(car.readings.values.toList()) { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(r.key, 15, FontWeight.Medium, modifier = Modifier.width(220.dp))
                            Txt(
                                if (r.raw != null) "${r.value.fmt(2)}  (raw ${r.raw.fmt(0)}${car.label(r.key)?.let { " · $it" } ?: ""})" else "—",
                                15, color = if (r.raw != null) C.Text else C.Text2, modifier = Modifier.width(260.dp),
                            )
                            Txt(r.source ?: r.error ?: "", 13, color = C.Text2, modifier = Modifier.weight(1f), maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

private enum class Status { OK, OFF }

@Composable
private fun ProbeRow(name: String, detail: String, status: Status) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt(name, 16, FontWeight.Medium)
            Txt(detail, 13, color = C.Text2, maxLines = 2)
        }
        val (bg, fg, text) = when (status) {
            Status.OK -> Triple(C.SurfaceRaised, C.Ink, "Connected")
            Status.OFF -> Triple(Color.Transparent, C.InkMuted, "Unavailable")
        }
        Box(Modifier.clip(Shape10).background(bg).padding(horizontal = 12.dp, vertical = 6.dp)) { Txt(text, 14, FontWeight.Medium, fg) }
    }
}

@Composable
private fun AboutTab() {
    val ctx = LocalContext.current
    val c = ctx.container
    val p = c.prefs
    val updater = c.updater
    prefsTick()
    val state by updater.state.collectAsState()
    val adbState by c.adb.state.collectAsState()
    val adbConnected = adbState == LocalAdb.State.CONNECTED
    val accent = LocalAccent.current
    val version = updater.currentVersionName()
    val versionCode = updater.currentVersionCode()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        com.sharkhome.launcher.ui.components.SupportCard()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt("Updates", 32, FontWeight.Medium)
                T("Installed v$version (build $versionCode) · BYD DiLink (Shark 6)", 15, color = C.InkMuted)
            }
            DButton(
                text = if (state is AppUpdater.State.Checking) "Checking…" else "Check for updates",
                onClick = { updater.checkForUpdates() },
                style = BtnStyle.Primary,
                height = 50.dp,
                textSize = 16,
            )
            DButton(
                text = "Releases ↗",
                onClick = { updater.openReleasesPage() },
                style = BtnStyle.Lightest,
                height = 50.dp,
                textSize = 16,
            )
        }

        // GitHub Release Updater card
        Panel(Modifier.fillMaxWidth(), spacing = 14.dp) {
            val normalizedRepo = AppUpdater.normalizeRepo(p.githubRepo)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DsIcon(Ic.Download, accent, 24.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    T("GitHub Release Updater", 20, FontWeight.SemiBold)
                    T(
                        normalizedRepo?.let { "github.com/$it/releases" } ?: "Invalid repository — enter owner/repo below",
                        14,
                        color = if (normalizedRepo != null) C.InkMuted else C.Alert,
                    )
                }
                val (badgeBg, badgeFg, badgeText) = when (val s = state) {
                    AppUpdater.State.Idle -> Triple(C.SurfaceRaised, C.InkSecondary, "Ready")
                    AppUpdater.State.Checking -> Triple(C.SurfaceRaised, accent, "Checking")
                    is AppUpdater.State.UpToDate -> Triple(C.Ok.copy(alpha = 0.16f), C.Ok, "Up to date")
                    is AppUpdater.State.UpdateAvailable -> Triple(
                        if (s.release.prerelease) C.Warn.copy(alpha = 0.18f) else accent.copy(alpha = 0.18f),
                        if (s.release.prerelease) C.Warn else accent,
                        if (s.release.prerelease) "Pre-release ${s.release.tagName}" else "Update ${s.release.tagName}",
                    )
                    is AppUpdater.State.Downloading -> Triple(accent.copy(alpha = 0.18f), accent, "Downloading")
                    is AppUpdater.State.ReadyToInstall -> Triple(C.Ok.copy(alpha = 0.16f), C.Ok, "Ready to install")
                    is AppUpdater.State.Installing -> Triple(accent.copy(alpha = 0.18f), accent, "Installing")
                    is AppUpdater.State.SavedToDownloads -> Triple(C.Ok.copy(alpha = 0.16f), C.Ok, "Saved")
                    is AppUpdater.State.Error -> Triple(C.Alert.copy(alpha = 0.16f), C.Alert, "Error")
                }
                Box(Modifier.clip(Shape10).background(badgeBg).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    T(badgeText, 14, FontWeight.SemiBold, badgeFg)
                }
            }

            Hairline()

            when (val s = state) {
                AppUpdater.State.Idle -> {
                    T(
                        "Checks GitHub Releases for newer SharkOS-CE APKs. When Local ADB is active, updates install in-place; otherwise the Android installer or Downloads folder is used.",
                        15,
                        color = C.InkSecondary,
                        maxLines = 3,
                    )
                }

                AppUpdater.State.Checking -> {
                    ThinBar(null, color = accent, height = 6.dp)
                    T("Contacting GitHub Releases API…", 15, color = C.InkSecondary)
                }

                is AppUpdater.State.UpToDate -> {
                    T(
                        "You are running v${s.currentVersion}, which matches the latest release (${s.release.tagName}${s.release.publishedAt.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}).",
                        15,
                        color = C.InkSecondary,
                        maxLines = 3,
                    )
                    if (s.release.notes.isNotBlank()) {
                        ReleaseNotesBox(s.release.notes)
                    }
                }

                is AppUpdater.State.UpdateAvailable -> {
                    val asset = s.release.apkAsset
                    T(
                        "${s.release.title}  ·  v${s.currentVersion} → ${s.release.tagName}${s.release.publishedAt.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}",
                        17,
                        FontWeight.Medium,
                    )
                    if (s.release.notes.isNotBlank()) {
                        ReleaseNotesBox(s.release.notes)
                    }
                    if (asset != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DButton(
                                text = "Download update (${formatBytes(asset.sizeBytes)})",
                                onClick = { updater.downloadUpdate(s.release) },
                                style = BtnStyle.Primary,
                                height = 52.dp,
                                textSize = 16,
                            )
                            T(asset.name, 14, color = C.InkMuted, modifier = Modifier.weight(1f))
                        }
                    } else {
                        T(
                            "Release ${s.release.tagName} does not have an .apk asset attached yet.",
                            15,
                            color = C.Warn,
                            maxLines = 2,
                        )
                    }
                }

                is AppUpdater.State.Downloading -> {
                    val frac = if (s.totalBytes > 0L) (s.bytesRead.toFloat() / s.totalBytes.toFloat()).coerceIn(0f, 1f) else null
                    val pctText = frac?.let { "${(it * 100).toInt()}%" } ?: "…"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        T("Downloading ${s.release.apkAsset?.name ?: s.release.tagName}", 16, FontWeight.Medium, modifier = Modifier.weight(1f))
                        T(
                            "$pctText  ·  ${formatBytes(s.bytesRead)}${if (s.totalBytes > 0L) " / ${formatBytes(s.totalBytes)}" else ""}",
                            15,
                            color = C.InkSecondary,
                        )
                    }
                    ThinBar(frac, color = accent, height = 8.dp)
                }

                is AppUpdater.State.ReadyToInstall -> {
                    T(
                        "Downloaded ${s.apkFile.name} (${formatBytes(s.apkFile.length())}) for ${s.release.tagName}.",
                        16,
                        FontWeight.Medium,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DButton(
                            text = if (adbConnected) "Install silently via ADB" else "Install update",
                            onClick = { updater.installApk(s.release, s.apkFile) },
                            style = BtnStyle.Primary,
                            height = 52.dp,
                            textSize = 16,
                        )
                        DButton(
                            text = "Save to Downloads",
                            onClick = { updater.exportToDownloads(s.release, s.apkFile) },
                            style = BtnStyle.Lightest,
                            height = 52.dp,
                            textSize = 16,
                        )
                    }
                    T(
                        "Tip: Enable USB Debugging in Quick Settings for 1-tap silent install over Local ADB (127.0.0.1:5555). Otherwise SharkOS-CE opens Android's package installer or exports to Downloads.",
                        13,
                        color = C.InkMuted,
                        maxLines = 3,
                    )
                }

                is AppUpdater.State.Installing -> {
                    ThinBar(null, color = accent, height = 6.dp)
                    T(
                        if (s.viaAdb) "Installing ${s.release.tagName} via Local ADB — SharkOS-CE will restart automatically…"
                        else "Opening installer for ${s.release.tagName}…",
                        15,
                        color = C.InkSecondary,
                        maxLines = 2,
                    )
                }

                is AppUpdater.State.SavedToDownloads -> {
                    T("Saved APK to ${s.location}", 16, FontWeight.Medium, C.Ok)
                    T(
                        "Open BYD File Management or Apps → Install APKs to install the saved file.",
                        14,
                        color = C.InkSecondary,
                        maxLines = 2,
                    )
                }

                is AppUpdater.State.Error -> {
                    T(s.message, 15, FontWeight.Medium, C.Alert, maxLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DButton(
                            text = "Retry check",
                            onClick = { updater.checkForUpdates() },
                            style = BtnStyle.Lightest,
                            height = 46.dp,
                            textSize = 15,
                        )
                    }
                }
            }
        }

        // SharkOS-CE helper check
        val helperState by updater.helper.collectAsState()
        LaunchedEffect(Unit) {
            while (true) {
                updater.refreshHelper() // also notices the install finishing in Android's installer
                kotlinx.coroutines.delay(3000)
            }
        }
        Panel(Modifier.fillMaxWidth(), spacing = 14.dp) {
            val (hBg, hFg, hText) = when (helperState) {
                AppUpdater.HelperState.Installed -> Triple(C.Ok.copy(alpha = 0.16f), C.Ok, "Installed")
                AppUpdater.HelperState.Missing -> Triple(C.Warn.copy(alpha = 0.18f), C.Warn, "Not installed")
                AppUpdater.HelperState.Downloading -> Triple(accent.copy(alpha = 0.18f), accent, "Downloading")
                AppUpdater.HelperState.Installing -> Triple(accent.copy(alpha = 0.18f), accent, "Installing")
                is AppUpdater.HelperState.Error -> Triple(C.Alert.copy(alpha = 0.16f), C.Alert, "Error")
                AppUpdater.HelperState.Unknown -> Triple(C.SurfaceRaised, C.InkSecondary, "Checking")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DsIcon(Ic.Download, accent, 24.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    T("SharkOS-CE helper (${AppUpdater.HELPER_PKG})", 20, FontWeight.SemiBold)
                    T("Reopens SharkOS-CE by itself when the car wakes from standby", 14, color = C.InkMuted)
                }
                Box(Modifier.clip(Shape10).background(hBg).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    T(hText, 14, FontWeight.SemiBold, hFg)
                }
            }
            when (val h = helperState) {
                AppUpdater.HelperState.Missing, is AppUpdater.HelperState.Error -> {
                    if (h is AppUpdater.HelperState.Error) T(h.message, 15, FontWeight.Medium, C.Alert, maxLines = 3)
                    else T(
                        "Without it, SharkOS-CE won't come back on its own after the car sleeps. Download it from the latest GitHub release and install it.",
                        15, color = C.InkSecondary, maxLines = 3,
                    )
                    DButton(
                        text = "Download & install SharkOS-CE helper",
                        onClick = { updater.downloadHelper() },
                        style = BtnStyle.Primary,
                        height = 52.dp,
                        textSize = 16,
                    )
                }
                AppUpdater.HelperState.Downloading, AppUpdater.HelperState.Installing -> ThinBar(null, color = accent, height = 6.dp)
                else -> {}
            }
        }

        // GitHub repo & auto-check settings
        Panel(Modifier.fillMaxWidth(), spacing = 14.dp) {
            T("Update source & behaviour", 20, FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    T("GitHub repository (owner/repo)", 16, FontWeight.Medium)
                    T("Point to a fork or custom release repo if needed", 13, color = C.InkMuted)
                }
                TextBox(p.githubRepo, { p.githubRepo = it }, Modifier.width(360.dp))
                DButton(
                    text = "Reset",
                    onClick = { p.githubRepo = AppUpdater.DEFAULT_REPO },
                    style = BtnStyle.Lightest,
                    height = 52.dp,
                    textSize = 15,
                )
            }
            Hairline()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    T("Auto-check on startup", 16, FontWeight.Medium)
                    T("Checks GitHub Releases at most once every 6 hours when online", 13, color = C.InkMuted)
                }
                PillSwitch(p.autoCheckUpdates, { p.autoCheckUpdates = it })
            }
        }

        // App info
        Panel(Modifier.fillMaxWidth(), spacing = 12.dp) {
            StatRow("Version", "$version ($versionCode)")
            StatRow("Package", ctx.packageName)
            StatRow("Platform", "BYD DiLink (Shark 6)")
            StatRow("Local ADB", if (adbConnected) "Connected (127.0.0.1:5555)" else "Disconnected", if (adbConnected) C.Ok else C.Text2)
        }
    }
}

@Composable
private fun ReleaseNotesBox(notes: String) {
    Column(
        Modifier.fillMaxWidth().clip(Shape10).background(C.Card2).border(1.dp, C.Line, Shape10).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        T("Release notes", 12, FontWeight.SemiBold, C.InkMuted, spacing = 0.06f)
        T(notes, 14, color = C.InkSecondary, maxLines = 10, lineHeight = 20)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
    else "${(bytes / 1024L).coerceAtLeast(1L)} KB"
}
