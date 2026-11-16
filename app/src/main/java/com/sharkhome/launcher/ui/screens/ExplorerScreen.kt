package com.sharkhome.launcher.ui.screens

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharkhome.launcher.car.CarExplorer
import com.sharkhome.launcher.container
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent
import com.sharkhome.launcher.ui.components.Panel
import com.sharkhome.launcher.ui.components.Segmented
import com.sharkhome.launcher.ui.components.Shape10
import com.sharkhome.launcher.ui.components.SmallButton
import com.sharkhome.launcher.ui.components.Txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Quick notes for the change log: press one, then do that thing in the car. */
// Only things still unconfirmed. Already verified on the car (7 Nov) and removed: indicators, hazards, low beam,
// brake lights, rear demist, Auto, airflow, hill descent.
private val MARKS = listOf(
    // Doors (cabin.is*DoorOpen): open then close each one.
    "Driver door", "Passenger door", "Rear door", "Tailgate", "Bonnet",
    // Drive energy mode (vehicle.powerMode reads 3 today).
    "EV mode", "HEV mode",
    // Charge time left (body.getChargingRestTime) — mark when a charge starts.
    "Charging started",
    // Drive modes beyond Normal = 1 are guesses.
    "Eco", "Sport", "Snow", "Mud", "Sand",
    // Still unknown.
    "High beam", "Fog lights", "Seatbelt", "Wipers", "Nav started",
)

/**
 * Read-only car explorer: every value the head unit will give us, live, with a change log.
 * Workflow: Record → tap a note ("Left indicator") → do it in the car → see which values changed → Export.
 */
@Composable
fun ExplorerScreen() {
    val ctx = LocalContext.current
    val ex = ctx.container.explorer
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        ex.start()
        onDispose { ex.stop() }
    }

    val status by ex.status.collectAsState()
    val recording by ex.recording.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().padding(end = 24.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Txt("Car explorer", 30, FontWeight.Medium)
                Txt("Read-only. $status", 15, color = C.Text2)
            }
            Box(
                Modifier.height(52.dp).clip(Shape10).background(if (recording) C.Red else C.Raised)
                    .clickable { ex.setRecording(!recording) }.padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) { Txt(if (recording) "Stop recording" else "Record", 16, FontWeight.Medium, if (recording) Color.White else C.Text) }
            SmallButton("Export", {
                scope.launch {
                    val msg = ex.exportAsync()
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            })
            SmallButton("Rescan", { ex.rediscover() })
        }

        // Notes for the change log.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("MARK", 13, color = C.Text2)
            MARKS.forEach { m -> Chip(m, false) { ex.mark(m) } }
        }

        Segmented(listOf("Live values", "Change log", "Sources", "System services"), tab, { tab = it }, Modifier.width(820.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> LiveTab(ex)
                1 -> LogTab(ex)
                2 -> SourcesTab(ex)
                else -> ServicesTab(ex)
            }
        }
    }
}

@Composable
private fun LiveTab(ex: CarExplorer) {
    val probes by ex.probes.collectAsState()
    val values by ex.values.collectAsState()
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(250)
            value = SystemClock.elapsedRealtime()
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var changedOnly by rememberSaveable { mutableStateOf(false) }
    var recentFirst by rememberSaveable { mutableStateOf(true) }
    var interval by rememberSaveable { mutableStateOf(1) }
    var indexed by rememberSaveable { mutableStateOf(ex.includeIndexed) }
    var hideErrors by rememberSaveable { mutableStateOf(true) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Panel(Modifier.width(380.dp).fillMaxHeight(), spacing = 14.dp) {
            SearchField(query, { query = it }, "Search, e.g. light, turn, beam")
            Toggle("Only values that changed", changedOnly) { changedOnly = it }
            Toggle("Most recent change first", recentFirst) { recentFirst = it }
            Toggle("Hide values that error", hideErrors) { hideErrors = it }
            Toggle("Indexed getters (0–7)", indexed) {
                indexed = it
                ex.includeIndexed = it
                ex.rediscover()
            }
            Txt("Read every", 14, color = C.Text2)
            Segmented(listOf("0.5 s", "1 s", "2 s"), interval, {
                interval = it
                ex.intervalMs = listOf(500L, 1000L, 2000L)[it]
            })
            Box(Modifier.weight(1f))
            Txt(
                "Tip: press Record, tap a note above, then do that thing in the car. " +
                    "Values that change flash blue for a few seconds and land in the change log.",
                14, color = C.Text2, maxLines = 6,
            )
        }

        val q = query.trim().lowercase(Locale.US)
        val rows = remember(probes, values, q, changedOnly, recentFirst, hideErrors) {
            probes.asSequence()
                .filter { q.isEmpty() || it.id.lowercase(Locale.US).contains(q) }
                .map { it to values[it.id] }
                .filter { (_, v) -> !changedOnly || (v?.changes ?: 0) > 0 }
                .filter { (_, v) -> !hideErrors || v?.error != true }
                .toList()
                .let { l -> if (recentFirst) l.sortedByDescending { it.second?.changedAt ?: 0L } else l }
        }
        Panel(Modifier.weight(1f).fillMaxHeight(), padding = 0.dp, spacing = 0.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Txt("${rows.size} shown", 14, color = C.Text2, modifier = Modifier.weight(1f))
                Txt("changes", 14, color = C.Text2, modifier = Modifier.width(90.dp))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = { it.first.id }) { (p, v) ->
                    val age = if (v != null && v.changedAt > 0) now - v.changedAt else Long.MAX_VALUE
                    val flash = age < 3000
                    Row(
                        Modifier.fillMaxWidth().background(if (flash) LocalAccent.current.copy(alpha = 0.28f) else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Txt(p.name, 16, FontWeight.Medium)
                            Txt(p.source, 13, color = C.Text2)
                        }
                        Mono(v?.text ?: "…", if (v?.error == true) C.Text2 else C.Text, Modifier.width(520.dp))
                        Txt((v?.changes ?: 0).toString(), 15, color = if ((v?.changes ?: 0) > 0) C.Text else C.Text2, modifier = Modifier.width(90.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogTab(ex: CarExplorer) {
    val log by ex.log.collectAsState()
    val recording by ex.recording.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    Panel(Modifier.fillMaxSize(), padding = 0.dp, spacing = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(
                if (log.isEmpty()) (if (recording) "Recording… changes will appear here" else "Press Record to start a change log") else "${log.size} entries, newest first",
                15, color = C.Text2, modifier = Modifier.weight(1f),
            )
            SmallButton("Clear", { ex.clearLog() }, height = 44.dp)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(log.asReversed()) { c ->
                if (c.note != null) {
                    Row(Modifier.fillMaxWidth().background(C.Card2).padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Mono(fmt.format(Date(c.wall)), C.Text2, Modifier.width(150.dp))
                        Txt(c.note, 16, FontWeight.Medium, LocalAccent.current)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Mono(fmt.format(Date(c.wall)), C.Text2, Modifier.width(150.dp))
                        Txt(c.id, 15, modifier = Modifier.weight(1f))
                        Mono("${c.old}  →  ${c.new}", C.Text, Modifier.width(560.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesTab(ex: CarExplorer) {
    val sources by ex.sources.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    Panel(Modifier.fillMaxSize(), padding = 0.dp, spacing = 0.dp) {
        Txt(
            "Where values come from. Tap a source to see every method it offers (setters are listed, never called).",
            15, color = C.Text2, modifier = Modifier.padding(20.dp),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(sources, key = { it.name }) { s ->
                Column(Modifier.fillMaxWidth().clickable(enabled = s.ok) { open = if (open == s.name) null else s.name }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(10.dp).height(10.dp).clip(Shape10).background(if (s.ok) C.Green else C.Raised))
                        Txt(s.name, 17, FontWeight.Medium, modifier = Modifier.padding(start = 12.dp).weight(1f))
                        Txt(if (s.ok) "${s.readable} readable" else s.detail, 14, color = C.Text2, modifier = Modifier.width(560.dp))
                    }
                    if (open == s.name) {
                        Txt(s.detail, 13, color = C.Text2, modifier = Modifier.padding(start = 22.dp, top = 6.dp))
                        s.methods.forEach { Mono(it, C.Text3, Modifier.padding(start = 22.dp, top = 2.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServicesTab(ex: CarExplorer) {
    var services by remember { mutableStateOf<List<CarExplorer.ServiceEntry>?>(null) }
    var comps by remember { mutableStateOf<List<CarExplorer.Component>>(emptyList()) }
    var onlyHints by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Panel(Modifier.width(380.dp).fillMaxHeight(), spacing = 14.dp) {
            Txt("Finding the driver display link", 18, FontWeight.Medium)
            Txt(
                "Lists the head unit's system services and the BYD/TS app parts, so we can find what sends the map " +
                    "and turn-by-turn to the screen in front of the driver. Nothing here is called, only listed.",
                14, color = C.Text2, maxLines = 8,
            )
            SmallButton("Scan", {
                scope.launch {
                    val (s, c) = withContext(Dispatchers.IO) { ex.listServices() to ex.listCarComponents() }
                    services = s
                    comps = c
                }
            }, primary = true)
            SearchField(query, { query = it }, "Filter")
            Toggle("Only likely nav / cluster", onlyHints) { onlyHints = it }
        }
        Panel(Modifier.weight(1f).fillMaxHeight(), padding = 0.dp, spacing = 0.dp) {
            val q = query.trim().lowercase(Locale.US)
            val s = (services ?: emptyList()).filter { (!onlyHints || it.interesting) && (q.isEmpty() || (it.name + it.descriptor).lowercase(Locale.US).contains(q)) }
            val c = comps.filter { (!onlyHints || it.interesting) && (q.isEmpty() || (it.pkg + it.name).lowercase(Locale.US).contains(q)) }
            if (services == null) {
                Txt("Press Scan.", 15, color = C.Text2, modifier = Modifier.padding(20.dp))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (services != null) {
                    item { Txt("SYSTEM SERVICES (${s.size})", 13, color = C.Text2, modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 6.dp)) }
                    items(s) { e ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                            Txt(e.name, 15, FontWeight.Medium, if (e.interesting) LocalAccent.current else C.Text, Modifier.width(420.dp))
                            Mono(e.descriptor.ifEmpty { "—" }, C.Text3, Modifier.weight(1f))
                        }
                    }
                    item { Txt("BYD / TS APP PARTS (${c.size})", 13, color = C.Text2, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 6.dp)) }
                    items(c) { e ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                            Txt(e.kind, 14, color = C.Text2, modifier = Modifier.width(90.dp))
                            Column(Modifier.weight(1f)) {
                                Mono(e.name, if (e.interesting) LocalAccent.current else C.Text, Modifier)
                                Txt(e.pkg + (if (e.exported) " · exported" else " · private") + (e.permission?.let { " · needs $it" } ?: ""), 13, color = C.Text2)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- small pieces

@Composable
private fun Mono(text: String, color: Color, modifier: Modifier) {
    androidx.compose.material3.Text(text, modifier, color = color, fontSize = 15.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).clip(Shape10).background(if (on) LocalAccent.current else C.Card2)
            .border(1.dp, C.Line, Shape10).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(label, 15) }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 16, modifier = Modifier.weight(1f))
        Box(Modifier.width(44.dp).height(24.dp).clip(Shape10).background(if (on) LocalAccent.current else C.Raised), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.padding(3.dp).width(18.dp).height(18.dp).clip(Shape10).background(Color.White))
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    Box(Modifier.fillMaxWidth().height(52.dp).clip(Shape10).background(C.Seg).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Txt(hint, 16, color = C.Text2)
        BasicTextField(
            value, onChange, Modifier.fillMaxWidth(), singleLine = true,
            textStyle = TextStyle(color = C.Text, fontSize = 16.sp), cursorBrush = SolidColor(C.Text),
        )
    }
}
