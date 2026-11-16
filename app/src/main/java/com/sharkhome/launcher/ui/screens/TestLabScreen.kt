package com.sharkhome.launcher.ui.screens

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sharkhome.launcher.car.ClusterWidget
import com.sharkhome.launcher.car.TestLabBridge
import com.sharkhome.launcher.container
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.components.BtnStyle
import com.sharkhome.launcher.ui.components.DButton
import com.sharkhome.launcher.ui.components.DCard
import com.sharkhome.launcher.ui.components.Eyebrow
import com.sharkhome.launcher.ui.components.Hairline
import com.sharkhome.launcher.ui.components.Radius4
import com.sharkhome.launcher.ui.components.T
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T("Test", 32, FontWeight.Medium)
                T("Try the cluster screen, BYD services and new signals on the car. Results appear in the log at the bottom.", 17, color = C.InkSecondary, maxLines = 2)
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
                            }, selected = now == "on", minWidth = 90.dp, height = 48.dp, textSize = 17)
                            DButton("Off", {
                                scope.launch { val r = c.vehicle.writeLabel(key, "off"); log("$title → off: ${if (r.confirmed) "confirmed" else "not confirmed"} (${r.message})") }
                            }, selected = now == "off", minWidth = 90.dp, height = 48.dp, textSize = 17)
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
