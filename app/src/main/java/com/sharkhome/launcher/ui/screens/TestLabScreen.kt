package com.sharkhome.launcher.ui.screens

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sharkhome.launcher.car.ClusterWidget
import com.sharkhome.launcher.car.TestLabBridge
import com.sharkhome.launcher.container
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent
import com.sharkhome.launcher.ui.components.BtnStyle
import com.sharkhome.launcher.ui.components.DButton
import com.sharkhome.launcher.ui.components.DCard
import com.sharkhome.launcher.ui.components.Eyebrow
import com.sharkhome.launcher.ui.components.Hairline
import com.sharkhome.launcher.ui.components.Radius4
import com.sharkhome.launcher.ui.components.T
import com.sharkhome.launcher.ui.components.prefsTick
import com.sharkhome.launcher.ui.components.rememberCar
import kotlinx.coroutines.launch

/**
 * Test tab: the cluster (second) screen, BYD service hooks, and read/write checks for car signals. Everything here is
 * for trying things on the car; each action writes its result to the log at the bottom.
 */
@Composable
fun TestLabScreen() {
    val ctx = LocalContext.current
    val c = ctx.container
    val car = rememberCar()
    val scope = rememberCoroutineScope()
    val presenting by TestLabBridge.presenting.collectAsState()
    val avmBound by TestLabBridge.avmBound.collectAsState()
    val selected by TestLabBridge.selected.collectAsState()

    var displays by remember { mutableStateOf(TestLabBridge.listDisplays(ctx)) }
    var dashMode by remember { mutableStateOf(TestLabBridge.getDashMapMode(c.bydAuto)) }
    val logs = remember { mutableStateListOf<String>() }
    fun log(msg: String) {
        logs.add(0, msg)
        if (logs.size > 10) logs.removeAt(logs.lastIndex)
    }
    val cluster = displays.firstOrNull { it.isPresentation }
    prefsTick()
    val marks = c.prefs.testMarks
    val worksCount = marks.values.count { it == MARK_WORKS }
    val failsCount = marks.values.count { it == MARK_FAILS }
    val laterCount = marks.values.count { it == MARK_LATER }
    fun setMark(id: String, title: String, mark: String) {
        val clearing = marks[id] == mark
        c.prefs.setTestMark(id, mark)
        val label = if (clearing) "cleared" else markLabel(mark)
        android.util.Log.e("SharkCar", "TEST_MARK $id ($title) = $label")
        log("$title: $label")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T("Test", 32, FontWeight.Medium)
                T("Mark each test as you try it: ✓ Works · − Doesn't work · ✕ Investigate later (saved automatically).", 16, color = C.InkSecondary, maxLines = 2)
            }
            if (marks.isNotEmpty()) {
                Row(
                    Modifier.clip(Radius4).background(C.SurfacePanel).border(1.dp, C.Line, Radius4).padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T("✓ $worksCount", 16, FontWeight.SemiBold, C.Ok)
                    T("− $failsCount", 16, FontWeight.SemiBold, C.Alert)
                    T("✕ $laterCount", 16, FontWeight.SemiBold, LocalAccent.current)
                }
                DButton("Clear marks", {
                    c.prefs.testMarks = emptyMap()
                    log("Cleared all test marks")
                }, height = 48.dp, textSize = 15)
            }
            DButton("Refresh", {
                displays = TestLabBridge.listDisplays(ctx)
                dashMode = TestLabBridge.getDashMapMode(c.bydAuto)
                c.vehicle.pollOnce()
                log("Refreshed: ${displays.size} displays, dash map mode ${dashMode ?: "unknown"}")
            }, minWidth = 140.dp)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // ------------------------------------------------ left: cluster screen
            Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                DCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Eyebrow("Cluster screen")
                            T(
                                if (cluster != null) "${cluster.name}  ·  ${cluster.width} × ${cluster.height}" else "No second screen found",
                                20, FontWeight.Medium, if (cluster != null) C.Ink else C.Alert,
                            )
                        }
                        T(if (presenting) "Showing" else "Off", 18, FontWeight.Medium, if (presenting) C.Ok else C.InkMuted)
                    }
                    T("Widgets: up to ${TestLabBridge.MAX_WIDGETS}, shown in this order. Tap to show or hide.", 17, color = C.InkSecondary)
                    ClusterWidget.entries.chunked(4).forEach { rowWidgets ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowWidgets.forEach { w ->
                                DButton(
                                    w.label,
                                    { if (!TestLabBridge.setWidgets(ctx, if (w in selected) selected - w else selected + w)) log("Up to ${TestLabBridge.MAX_WIDGETS} widgets fit on the cluster screen. Hide one first.") },
                                    Modifier.weight(1f), selected = w in selected, height = 56.dp, textSize = 16,
                                )
                            }
                            repeat(4 - rowWidgets.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DButton(
                            if (presenting) "Restart on cluster" else "Show on cluster",
                            { log(TestLabBridge.startPresentation(ctx, cluster?.id ?: 1)) },
                            Modifier.weight(1f), style = BtnStyle.Primary,
                        )
                        DButton("Stop", { log(TestLabBridge.stopPresentation()) }, Modifier.weight(1f))
                    }
                    T("The cluster screen keeps updating with live car data while it's showing, whatever screen is open here.", 15, color = C.InkMuted, maxLines = 2)
                    TestMarkFooter("cluster_screen", "Cluster screen", marks["cluster_screen"], ::setMark)
                }

                DCard {
                    Eyebrow("Open a BYD screen")
                    val shortcuts = listOf(
                        "Smart charging" to "com.byd.smarttravel", "Scene mode" to "com.byd.scenemode",
                        "My Car" to "com.byd.mycar", "360° camera" to "com.byd.avm",
                        "Data monitor" to "com.byd.trafficmonitor", "Rage mode" to "com.byd.dlc.drivingmode",
                        "Photos" to "com.byd.auto_photo", "Manual" to "com.byd.auto_emanual",
                        "Car settings" to "com.byd.carsettings", "BYD climate" to "com.byd.hvac",
                    )
                    shortcuts.chunked(4).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowItems.forEach { (label, pkg) ->
                                DButton(label, {
                                    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                                    if (intent == null) log("$label isn't installed on this unit")
                                    else { runCatching { ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); log("Opened $label") }.onFailure { log("$label wouldn't open: ${it.message}") } }
                                }, Modifier.weight(1f), textSize = 16)
                            }
                        }
                    }
                    T("These only open BYD's own screens. Anything that changes the car still asks for BYD's usual confirmation there.", 15, color = C.InkMuted, maxLines = 2)
                    TestMarkFooter("byd_screens", "Open BYD screens", marks["byd_screens"], ::setMark)
                }

                DCard {
                    Eyebrow("Navigation on the cluster  ·  map mode: ${dashMode ?: "unknown"}")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DButton("Simple map", {
                            log(TestLabBridge.setDashMapMode(c.bydAuto, 1)); dashMode = TestLabBridge.getDashMapMode(c.bydAuto)
                        }, Modifier.weight(1f), selected = dashMode == 1, textSize = 16)
                        DButton("Full map", {
                            log(TestLabBridge.setDashMapMode(c.bydAuto, 2)); dashMode = TestLabBridge.getDashMapMode(c.bydAuto)
                        }, Modifier.weight(1f), selected = dashMode == 2, textSize = 16)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DButton("Ask for map focus", {
                            log(TestLabBridge.sendClusterCommand(ctx, TestLabBridge.DATA_TYPE_NAVI_REQUEST_MAP_FOCUS, Bundle().apply { putString("mapSource", ctx.packageName) }))
                        }, Modifier.weight(1f), textSize = 16)
                        DButton("Screen ready", {
                            log(TestLabBridge.sendClusterCommand(ctx, TestLabBridge.DATA_TYPE_NAVI_SEND_PRESENTATION_STATE, Bundle().apply { putInt("state", 1) }))
                        }, Modifier.weight(1f), textSize = 16)
                        DButton("Give up focus", {
                            log(TestLabBridge.sendClusterCommand(ctx, TestLabBridge.DATA_TYPE_NAVI_ABANDON_MAP_FOCUS, Bundle()))
                        }, Modifier.weight(1f), textSize = 16)
                    }
                    TestMarkFooter("cluster_navi", "Cluster navigation", marks["cluster_navi"], ::setMark)
                }
            }

            // ------------------------------------------------ right: services and signals
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                DCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Eyebrow("360° camera service")
                            T(if (avmBound) "Connected" else "Not connected", 20, FontWeight.Medium, if (avmBound) C.Ok else C.InkMuted)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DButton("Connect", { TestLabBridge.bindAvm(ctx) { log(it) } }, Modifier.weight(1f), textSize = 16)
                        DButton("Status", { log(TestLabBridge.callAvm("getAvmStatus")) }, Modifier.weight(1f), textSize = 16)
                        DButton("Open", { log(TestLabBridge.callAvm("startAvm")) }, Modifier.weight(1f), textSize = 16)
                        DButton("Close", { log(TestLabBridge.callAvm("stopAvm")) }, Modifier.weight(1f), textSize = 16)
                    }
                    Hairline()
                    Eyebrow("BYD hooks")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DButton("Quick settings", { log(TestLabBridge.sendBydBroadcast(ctx, "com.byd.action.qsPanel.auto")) }, Modifier.weight(1f), textSize = 16)
                        DButton("Voice key", { log(TestLabBridge.sendBydBroadcast(ctx, "com.byd.action.key.voice")) }, Modifier.weight(1f), textSize = 16)
                        DButton("Cluster music", { log(TestLabBridge.sendClusterMedia(c.bydAuto, "SharkOS-CE test", "BYD Shark 6")) }, Modifier.weight(1f), textSize = 16)
                    }
                    TestMarkFooter("avm_and_hooks", "360° service & BYD hooks", marks["avm_and_hooks"], ::setMark)
                }

                DCard {
                    Eyebrow("Write tests  ·  tap and the result is read back")
                    listOf(
                        "energy.socSave" to "Hold battery charge",
                        "advanced.hudSnow" to "HUD snow mode",
                        "advanced.mirrorFold" to "Auto-fold mirrors",
                        "body.cargoLight" to "Cargo bed light",
                    ).forEachIndexed { i, (key, title) ->
                        if (i > 0) Hairline()
                        val now = car.label(key)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                T(title, 19, FontWeight.Medium)
                                T("Now: ${now ?: "unknown"}", 15, color = C.InkMuted)
                            }
                            DButton("On", {
                                scope.launch { val r = c.vehicle.writeLabel(key, "on"); log("$title → on: ${if (r.confirmed) "confirmed" else "not confirmed"} (${r.message})") }
                            }, selected = now == "on", minWidth = 78.dp, height = 48.dp, textSize = 16)
                            DButton("Off", {
                                scope.launch { val r = c.vehicle.writeLabel(key, "off"); log("$title → off: ${if (r.confirmed) "confirmed" else "not confirmed"} (${r.message})") }
                            }, selected = now == "off", minWidth = 78.dp, height = 48.dp, textSize = 16)
                            TestMarkCompact("write_$key", title, marks["write_$key"], ::setMark)
                        }
                    }
                }

                DCard {
                    Eyebrow("Read-only signals")
                    val reads = listOf(
                        "adas.reverseRadar" to "Reverse radar", "cabin.pm25" to "Cabin PM2.5",
                        "cabin.doorLight" to "Indoor light", "health.oilLifetime" to "Oil life",
                        "charging.v2lToggle" to "V2L", "charging.v2lVoltage" to "V2L voltage",
                        "charging.v2lCurrent" to "V2L current", "display.screenRotation" to "Screen angle",
                    )
                    reads.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { (key, label) ->
                                val v = car.n(key)
                                Column(Modifier.weight(1f).clip(Radius4).background(C.SurfacePanel).border(1.dp, C.Line, Radius4).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    T(label, 15, color = C.InkMuted)
                                    T(v?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } ?: "no reading", 21, FontWeight.Medium, if (v == null) C.InkSubtle else C.Ink)
                                }
                            }
                        }
                    }
                    TestMarkFooter("readonly_signals", "Read-only signals", marks["readonly_signals"], ::setMark)
                }

                AndroidEqTestCard(marks, ::setMark, ::log)
            }
        }

        // ------------------------------------------------ parked / on-road unverified read-only tests
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                DCard(Modifier.fillMaxWidth()) {
                    Eyebrow("V2L live test (read-only · plug in load to verify)")
                    val v2lReads = listOf(
                        "charging.v2lToggle" to "V2L toggle (raw)",
                        "charging.v2lVoltage" to "V2L voltage (V)",
                        "charging.v2lCurrent" to "V2L current (A)",
                        "vehicle.dischargePower" to "External power (kW)",
                    )
                    v2lReads.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { (key, label) ->
                                val v = car.n(key)
                                Column(Modifier.weight(1f).clip(Radius4).background(C.SurfacePanel).border(1.dp, C.Line, Radius4).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    T(label, 15, color = C.InkMuted)
                                    T(v?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.2f".format(it) } ?: "no reading", 21, FontWeight.Medium, if (v == null) C.InkSubtle else C.Ink)
                                }
                            }
                        }
                    }
                    T("Parked baseline: toggle/V/A read 0; external power read 4.42 kW (likely stale/cumulative value when idle). Check with a V2L appliance connected.", 15, color = C.InkMuted, maxLines = 3)
                    TestMarkFooter("test_v2l", "V2L live test", marks["test_v2l"], ::setMark)
                }

                DCard(Modifier.fillMaxWidth()) {
                    Eyebrow("Windows open % (read-only · open a window to verify scale)")
                    val winReads = listOf(
                        "window.frontLeft" to "Front left",
                        "window.frontRight" to "Front right",
                        "window.rearLeft" to "Rear left",
                        "window.rearRight" to "Rear right",
                    )
                    winReads.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { (key, label) ->
                                val v = car.n(key)
                                Column(Modifier.weight(1f).clip(Radius4).background(C.SurfacePanel).border(1.dp, C.Line, Radius4).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    T(label, 15, color = C.InkMuted)
                                    T(v?.let { "${it.toInt()} %" } ?: "no reading", 21, FontWeight.Medium, if (v == null) C.InkSubtle else C.Ink)
                                }
                            }
                        }
                    }
                    T("Parked with windows shut reads 0 on all four doors. Crack a window to check whether the scale is 0–100.", 15, color = C.InkMuted, maxLines = 2)
                    TestMarkFooter("test_windows", "Windows open %", marks["test_windows"], ::setMark)
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                DCard(Modifier.fillMaxWidth()) {
                    Eyebrow("Seatbelts (read-only · buckle a belt to verify encoding)")
                    val beltReads = listOf(
                        "belt.driver" to "Driver",
                        "belt.passenger" to "Passenger",
                        "belt.rearLeft" to "Rear left",
                        "belt.rearMiddle" to "Rear middle",
                        "belt.rearRight" to "Rear right",
                    )
                    beltReads.chunked(3).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowItems.forEach { (key, label) ->
                                val v = car.n(key)
                                Column(Modifier.weight(1f).clip(Radius4).background(C.SurfacePanel).border(1.dp, C.Line, Radius4).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    T(label, 15, color = C.InkMuted)
                                    T(v?.let { "raw ${it.toInt()}" } ?: "no reading", 20, FontWeight.Medium, if (v == null) C.InkSubtle else C.Ink)
                                }
                            }
                            repeat(3 - rowItems.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                    T("Empty seats read raw 1 when parked. Buckle driver or passenger belt to confirm buckled value (likely 0 or 2).", 15, color = C.InkMuted, maxLines = 2)
                    TestMarkFooter("test_seatbelts", "Seatbelts", marks["test_seatbelts"], ::setMark)
                }

                DCard(Modifier.fillMaxWidth()) {
                    Eyebrow("Drive telemetry & parking radar (read-only · check while moving)")
                    val driveReads = listOf(
                        "vehicle.heading" to "Compass heading (°)",
                        "chassis.slope" to "Slope (raw)",
                        "trip.drivingHours" to "Driving hours",
                        "radar.frontLeft" to "Radar FL",
                        "radar.frontRight" to "Radar FR",
                        "radar.rearLeft" to "Radar RL",
                        "radar.rearMidLeft" to "Radar RML",
                        "radar.rearMidRight" to "Radar RMR",
                        "radar.rearRight" to "Radar RR",
                    )
                    driveReads.chunked(3).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowItems.forEach { (key, label) ->
                                val v = car.n(key)
                                Column(Modifier.weight(1f).clip(Radius4).background(C.SurfacePanel).border(1.dp, C.Line, Radius4).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    T(label, 14, color = C.InkMuted)
                                    T(v?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } ?: "—", 19, FontWeight.Medium, if (v == null) C.InkSubtle else C.Ink)
                                }
                            }
                        }
                    }
                    T("Parked: slope reads 255 (sentinel), radar reads 0. Check in Drive/Reverse to confirm units.", 15, color = C.InkMuted, maxLines = 2)
                    TestMarkFooter("test_drive_radar", "Drive telemetry & radar", marks["test_drive_radar"], ::setMark)
                }
            }
        }

        // ------------------------------------------------ every signal the car catalogue can read
        DCard(Modifier.fillMaxWidth()) {
            val keys = remember { c.codes.signals.keys.sorted() }
            val groups = remember { keys.map { it.substringBefore('.') }.distinct() }
            var group by remember { mutableStateOf<String?>(null) }
            var onlyAnswering by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("All signals  ·  ${keys.size} in the catalogue")
                    T("Live values the car answers right now. A dash means this unit doesn't answer that signal.", 16, color = C.InkMuted, maxLines = 2)
                }
                DButton("Answering only", { onlyAnswering = !onlyAnswering }, selected = onlyAnswering, height = 48.dp, textSize = 16)
            }
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf<String?>(null) + groups) { g ->
                    DButton(g ?: "All", { group = g }, selected = group == g, height = 44.dp, textSize = 15)
                }
            }
            val shown = keys.filter { (group == null || it.startsWith("$group.")) && (!onlyAnswering || car.available(it)) }
            Box(Modifier.fillMaxWidth().height(520.dp).clip(Radius4).background(C.SurfacePanel).border(1.dp, C.Line, Radius4)) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    items(shown, key = { it }) { k ->
                        val r = car.readings[k]
                        val label = car.label(k)
                        val v = r?.value
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            T(k, 16, color = C.InkSecondary, modifier = Modifier.weight(1f))
                            T(
                                when {
                                    label != null && v != null -> "$label  (${if (v % 1.0 == 0.0) v.toInt().toString() else "%.2f".format(v)})"
                                    v != null -> if (v % 1.0 == 0.0) v.toInt().toString() else "%.2f".format(v)
                                    else -> "—"
                                },
                                16, FontWeight.Medium, if (v == null) C.InkSubtle else C.Ink,
                            )
                        }
                    }
                }
            }
        }
        DCard(Modifier.fillMaxWidth()) {
            Eyebrow("Log")
            if (logs.isEmpty()) T("Tap any button above and the result shows here.", 17, color = C.InkMuted)
            else logs.forEach { T(it, 16, color = C.Ink, maxLines = 2, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

/**
 * Android's own output-mix equaliser (bass boost, presets, bands), for testing whether it audibly changes the car's
 * speakers. This is separate from BYD's Dynaudio EQ (which the car won't let any app set). Changes persist across starts.
 */
@Composable
private fun AndroidEqTestCard(
    marks: Map<String, String>,
    onMark: (String, String, String) -> Unit,
    log: (String) -> Unit,
) {
    val c = LocalContext.current.container
    val eq by c.audio.eq.collectAsState()
    val bass by c.audio.bass.collectAsState()
    DCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Bass boost (output mix)")
                T(
                    if (!bass.available) "Not available${bass.error?.let { " · $it" } ?: ""}"
                    else if (bass.enabled) "On · ${bass.strength / 10}%" else "Off",
                    20, FontWeight.Medium, if (bass.available && bass.enabled) C.Ok else C.InkMuted,
                )
            }
        }
        if (bass.available) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0 to "Off", 250 to "Low", 500 to "Medium", 750 to "High", 1000 to "Max").forEach { (s, label) ->
                    DButton(
                        label, { c.audio.setBassStrength(s); log("Bass boost: $label ($s/1000)") }, Modifier.weight(1f),
                        selected = if (s == 0) !bass.enabled else bass.enabled && kotlin.math.abs(bass.strength - s) < 125,
                        height = 52.dp, textSize = 15,
                    )
                }
            }
            T("Bass boost is a separate effect from the bands below and is usually the stronger of the two.", 15, color = C.InkMuted, maxLines = 2)
        }
        TestMarkFooter("audio_bass", "Bass boost", marks["audio_bass"], onMark)
        Hairline()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Android equaliser (output mix)")
                T(
                    if (!eq.available) "Not available${eq.error?.let { " · $it" } ?: ""}"
                    else if (eq.enabled) "On" else "Off",
                    20, FontWeight.Medium, if (eq.available && eq.enabled) C.Ok else C.InkMuted,
                )
            }
            if (eq.available) {
                DButton("On", { c.audio.setEnabled(true); log("Android EQ on") }, selected = eq.enabled, minWidth = 84.dp, height = 48.dp, textSize = 16)
                DButton("Off", { c.audio.setEnabled(false); log("Android EQ off") }, selected = !eq.enabled, minWidth = 84.dp, height = 48.dp, textSize = 16)
            }
        }
        if (!eq.available) {
            T("This head unit didn't allow a system equaliser. Nothing to test here.", 15, color = C.InkMuted, maxLines = 2)
            TestMarkFooter("audio_eq", "Android equaliser", marks["audio_eq"], onMark)
            return@DCard
        }
        T("Play music through the speakers, then try a preset or push a band. If you hear it change, the EQ reaches the car's audio.", 15, color = C.InkMuted, maxLines = 3)

        if (eq.presets.isNotEmpty()) {
            Hairline()
            Eyebrow("Presets")
            eq.presets.chunked(3).forEach { rowPresets ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowPresets.forEach { name ->
                        val idx = eq.presets.indexOf(name)
                        DButton(name, { c.audio.usePreset(idx); log("Preset: $name") }, Modifier.weight(1f), selected = idx == eq.currentPreset, height = 52.dp, textSize = 15)
                    }
                    repeat(3 - rowPresets.size) { Box(Modifier.weight(1f)) }
                }
            }
        }

        Hairline()
        Eyebrow("Bands")
        val step = ((eq.maxLevel - eq.minLevel) / 10).coerceAtLeast(100)
        eq.bands.forEach { b ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val freq = if (b.centerHz >= 1000) "${b.centerHz / 1000} kHz" else "${b.centerHz} Hz"
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    T(freq, 18, FontWeight.Medium)
                    T((b.level / 100.0).let { if (it > 0) "+%.0f dB".format(it) else "%.0f dB".format(it) }, 15, color = C.InkMuted)
                }
                DButton("−", { c.audio.setBand(b.index, b.level - step) }, minWidth = 72.dp, height = 48.dp, textSize = 20)
                DButton("+", { c.audio.setBand(b.index, b.level + step) }, minWidth = 72.dp, height = 48.dp, textSize = 20)
            }
        }
        TestMarkFooter("audio_eq", "Android equaliser", marks["audio_eq"], onMark)
    }
}

private const val MARK_WORKS = "works"
private const val MARK_FAILS = "fails"
private const val MARK_LATER = "later"

private fun markLabel(mark: String?): String = when (mark) {
    MARK_WORKS -> "Works"
    MARK_FAILS -> "Doesn't work"
    MARK_LATER -> "Investigate later"
    else -> "Untested"
}

/**
 * Full-width persisted test-status footer for a Test tab card:
 * ✓ Works (green) · ✗ Doesn't work (red) · ✕ Investigate (accent). Tapping the active choice clears it.
 */
@Composable
private fun TestMarkFooter(
    id: String,
    title: String,
    current: String?,
    onMark: (String, String, String) -> Unit,
) {
    val accent = LocalAccent.current
    Hairline()
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        T(
            markLabel(current),
            14,
            FontWeight.SemiBold,
            when (current) {
                MARK_WORKS -> C.Ok
                MARK_FAILS -> C.Alert
                MARK_LATER -> accent
                else -> C.InkMuted
            },
            modifier = Modifier.weight(1f),
        )
        TestMarkChip("✓ Works", current == MARK_WORKS, C.Ok) { onMark(id, title, MARK_WORKS) }
        TestMarkChip("− Doesn't work", current == MARK_FAILS, C.Alert) { onMark(id, title, MARK_FAILS) }
        TestMarkChip("✕ Investigate", current == MARK_LATER, accent) { onMark(id, title, MARK_LATER) }
    }
}

/** Compact 3-icon persisted test-status picker for single rows (Write tests). */
@Composable
private fun TestMarkCompact(
    id: String,
    title: String,
    current: String?,
    onMark: (String, String, String) -> Unit,
) {
    val accent = LocalAccent.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TestMarkSquare("✓", current == MARK_WORKS, C.Ok) { onMark(id, title, MARK_WORKS) }
        TestMarkSquare("−", current == MARK_FAILS, C.Alert) { onMark(id, title, MARK_FAILS) }
        TestMarkSquare("✕", current == MARK_LATER, accent) { onMark(id, title, MARK_LATER) }
    }
}

@Composable
private fun TestMarkChip(label: String, selected: Boolean, activeColor: Color, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp)
            .clip(Radius4)
            .background(if (selected) activeColor else C.SurfacePanel)
            .border(1.dp, if (selected) activeColor else C.Line, Radius4)
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(label, 14, if (selected) FontWeight.SemiBold else FontWeight.Medium, if (selected) Color.White else C.InkSecondary)
    }
}

@Composable
private fun TestMarkSquare(symbol: String, selected: Boolean, activeColor: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp)
            .clip(Radius4)
            .background(if (selected) activeColor else C.SurfacePanel)
            .border(1.dp, if (selected) activeColor else C.Line, Radius4)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        T(symbol, 18, FontWeight.SemiBold, if (selected) Color.White else C.InkSecondary)
    }
}
