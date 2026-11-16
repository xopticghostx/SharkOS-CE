package com.sharkhome.launcher.ui.screens

import android.graphics.BitmapFactory
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sharkhome.launcher.container
import com.sharkhome.launcher.dashcam.Cam
import com.sharkhome.launcher.dashcam.Clip
import com.sharkhome.launcher.dashcam.DashCamEngine
import com.sharkhome.launcher.dashcam.Quality
import com.sharkhome.launcher.dashcam.StartMode
import com.sharkhome.launcher.dashcam.Trip
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent
import com.sharkhome.launcher.ui.components.BtnStyle
import com.sharkhome.launcher.ui.components.DButton
import com.sharkhome.launcher.ui.components.DCard
import com.sharkhome.launcher.ui.components.Eyebrow
import com.sharkhome.launcher.ui.components.Hairline
import com.sharkhome.launcher.ui.components.LineSegmented
import com.sharkhome.launcher.ui.components.PillSwitch
import com.sharkhome.launcher.ui.components.Radius4
import com.sharkhome.launcher.ui.components.T
import com.sharkhome.launcher.ui.components.ThinBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

private fun bytesText(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%d MB".format(b shr 20)
    else -> "%d KB".format(b shr 10)
}

private fun durText(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d h %02d min".format(s / 3600, (s % 3600) / 60) else if (s >= 60) "%d min".format(s / 60) else "$s s"
}

private fun clockText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** DashCam: live status, trips, saved clips and settings. Recording is done by a helper running as the shell user. */
@Composable
fun DashCamScreen() {
    val ctx = LocalContext.current
    val engine = ctx.container.dashcam
    val scope = rememberCoroutineScope()
    val status by engine.status.collectAsState()
    val busy by engine.busy.collectAsState()
    val result by engine.lastResult.collectAsState()
    val cfg by engine.settings.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Trips are re-read from disk every few seconds while the screen is open.
    val trips by produceState(emptyList<Trip>(), tab, status.running) {
        while (true) {
            value = engine.scan()
            delay(4000)
        }
    }

    LaunchedEffect(toast) { if (toast != null) { delay(4000); toast = null } }

    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            T("DashCam", 32, FontWeight.Medium)
            RecPill(status.running, status.startedMs, busy)
            Box(Modifier.weight(1f))
            LineSegmented(listOf("Live", "Trips (${trips.count { !it.moment }})", "Saved (${trips.count { it.moment || it.kept }})", "Settings"), tab, { tab = it }, Modifier.width(720.dp), height = 52.dp)
            Box(Modifier.weight(1f))
            DButton(
                if (status.running) "Stop recording" else "Start recording",
                { scope.launch { if (status.running) engine.stop() else engine.start() } },
                style = if (status.running) BtnStyle.Dark else BtnStyle.Primary, height = 52.dp, minWidth = 190.dp,
            )
            DButton(
                "Save last ${cfg.saveMinutes} min",
                {
                    scope.launch {
                        toast = "Saving the last ${cfg.saveMinutes} minutes…"
                        val n = engine.saveLastMinutes()
                        toast = if (n > 0) "Saved the last ${cfg.saveMinutes} minutes ($n clips) to Saved." else "Nothing to save yet. Start recording first."
                    }
                },
                height = 52.dp,
            )
        }

        val note = toast ?: result ?: busy
        if (note != null) {
            Box(Modifier.fillMaxWidth().clip(Radius4).background(C.SurfacePanel).border(1.dp, C.Line, Radius4).padding(horizontal = 20.dp, vertical = 12.dp)) {
                T(note, 17, color = if (result != null && toast == null) C.Alert else C.Ink, maxLines = 2)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> LiveTab(engine, trips)
                1 -> LibraryTab(engine, trips.filter { !it.moment }, "No trips yet. Drives appear here once recording has run for a minute.")
                2 -> LibraryTab(engine, trips.filter { it.moment || it.kept }, "Nothing saved. Use Save last ${cfg.saveMinutes} min, or Keep on a trip.")
                else -> SettingsTab(engine)
            }
        }
    }
}

@Composable
private fun RecPill(running: Boolean, startedMs: Long, busy: String?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); delay(1000) } }
    val col = if (running) C.Alert else C.InkMuted
    Row(
        Modifier.clip(Radius4).border(1.dp, if (running) C.Alert else C.Line, Radius4).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(col))
        T(
            when {
                busy != null -> "Working…"
                running -> "Recording · ${clockText(now - startedMs)}"
                else -> "Not recording"
            }, 16, FontWeight.Medium, col,
        )
    }
}

// ------------------------------------------------------------------ live

@Composable
private fun LiveTab(engine: DashCamEngine, trips: List<Trip>) {
    val settings by engine.settings.collectAsState()
    val status by engine.status.collectAsState()
    val cams = settings.cams.sortedBy { it.ordinal }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val rows = if (cams.size <= 2) 1 else 2
            val perRow = if (cams.size <= 2) cams.size.coerceAtLeast(1) else 2
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (r in 0 until rows) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (c in 0 until perRow) {
                            val cam = cams.getOrNull(r * perRow + c)
                            if (cam != null) LivePreview(engine, cam, status.running, Modifier.weight(1f).fillMaxHeight())
                            else Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Column(Modifier.width(520.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val storage = engine.storage(trips)
            DCard {
                Eyebrow("Recorder")
                StatusLine("State", if (status.running) "Recording" else "Stopped", if (status.running) C.Ok else C.InkMuted)
                StatusLine("Start mode", if (settings.startMode == StartMode.AUTO) "Starts in D or R" else "Manual")
                StatusLine("Picture", "${settings.quality.label} · ${settings.quality.fps} fps · 1280×864")
                StatusLine("Clips", "1 minute each, no sound")
                if (status.error.isNotEmpty()) T(status.error, 16, color = C.Alert, maxLines = 3)
            }
            DCard {
                Eyebrow("Cameras")
                Cam.entries.forEach { cam ->
                    val on = cam in settings.cams
                    val frames = status.frames[cam.folder]
                    StatusLine(cam.label, when {
                        !on -> "Off"
                        status.running && frames != null -> "%,d frames".format(frames)
                        else -> "Ready"
                    }, if (!on) C.InkSubtle else C.Ink)
                }
            }
            DCard {
                Eyebrow("Loop storage")
                val frac = if (storage.limitBytes > 0) storage.loopBytes.toFloat() / storage.limitBytes else 0f
                ThinBar(frac, color = LocalAccent.current, height = 8.dp)
                T("${bytesText(storage.loopBytes)} of ${settings.storageGb} GB used · ${bytesText(storage.freeBytes)} free on the unit", 16, color = C.InkSecondary, maxLines = 2)
                T("When it's full the oldest clips are replaced. Kept trips and saved clips are never replaced.", 15, color = C.InkMuted, maxLines = 3)
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, valueColor: Color = C.Ink) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        T(label, 17, color = C.InkSecondary)
        T(value, 17, FontWeight.Medium, valueColor)
    }
}

@Composable
private fun LivePreview(engine: DashCamEngine, cam: Cam, recording: Boolean, modifier: Modifier) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, cam, recording) {
        while (true) {
            val f = engine.previewFile(cam)
            value = if (recording && f.exists() && System.currentTimeMillis() - f.lastModified() < 6000)
                runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() else null
            delay(1000)
        }
    }
    Box(modifier.clip(Radius4).background(Color(0xFF0B0B0B)).border(1.dp, C.Line, Radius4)) {
        val b = bmp
        if (b != null) Image(b, cam.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            T(if (recording) "Waiting for video…" else "Start recording to see the cameras", 18, color = Color(0xFF8A8A8A))
        }
        Box(Modifier.align(Alignment.TopStart).padding(12.dp).clip(Radius4).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp)) {
            T(cam.label, 15, FontWeight.Medium, Color.White)
        }
    }
}

// ------------------------------------------------------------------ trips / saved

@Composable
private fun LibraryTab(engine: DashCamEngine, items: List<Trip>, emptyText: String) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = items.firstOrNull { it.id == selectedId } ?: items.firstOrNull()
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T(emptyText, 20, color = C.InkMuted) }
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        LazyColumn(Modifier.width(560.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { (if (it.moment) "m_" else "t_") + it.id }) { t ->
                TripRow(t, t.id == selected?.id && t.moment == selected.moment) { selectedId = t.id }
            }
        }
        if (selected != null) TripPlayer(engine, selected, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun TripRow(t: Trip, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        Modifier.fillMaxWidth().clip(Radius4).background(C.SurfaceCard)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else C.Line, Radius4)
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T("${dayFmt.format(t.startMs)} · ${timeFmt.format(t.startMs)}" + if (t.moment) " · Saved moment" else "", 19, FontWeight.Medium, modifier = Modifier.weight(1f))
            if (t.active) T("Recording now", 15, FontWeight.Medium, C.Alert) else if (t.kept) T("Kept", 15, FontWeight.Medium, accent)
        }
        val parts = buildList {
            add(durText(t.durationMs))
            t.distanceKm?.takeIf { it >= 0.1 }?.let { add("%.1f km".format(it)) }
            add(bytesText(t.sizeBytes))
            add(t.cams.joinToString(" ") { it.label.first().toString() })
        }
        T(parts.joinToString("  ·  "), 16, color = C.InkSecondary)
    }
}

@Composable
private fun TripPlayer(engine: DashCamEngine, trip: Trip, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var camIdx by remember(trip.id, trip.moment) { mutableIntStateOf(0) }
    var quad by remember(trip.id, trip.moment) { mutableStateOf(false) }
    var playing by remember(trip.id, trip.moment) { mutableStateOf(true) }
    var posMs by remember(trip.id, trip.moment) { mutableLongStateOf(0L) }
    var seekToken by remember(trip.id, trip.moment) { mutableIntStateOf(0) }
    var seekAbs by remember(trip.id, trip.moment) { mutableLongStateOf(trip.startMs) }
    var confirmDelete by remember(trip.id, trip.moment) { mutableStateOf(false) }
    val cams = trip.cams
    val cam = cams.getOrNull(camIdx) ?: cams.firstOrNull() ?: return
    val total = trip.durationMs.coerceAtLeast(1)

    fun seekTo(rel: Long) { seekAbs = trip.startMs + rel.coerceIn(0, total); posMs = rel.coerceIn(0, total); seekToken++ }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val labels = cams.map { it.label } + if (cams.size > 1) listOf("All") else emptyList()
            LineSegmented(labels, if (quad) cams.size else camIdx, { i -> if (i >= cams.size) quad = true else { quad = false; camIdx = i }; seekTo(posMs) }, Modifier.weight(1f), height = 48.dp, textSize = 16)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(Radius4).background(Color.Black)) {
            if (quad) {
                Column(Modifier.fillMaxSize()) {
                    val rows = cams.chunked(2)
                    rows.forEachIndexed { ri, row ->
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            row.forEachIndexed { ci, c ->
                                Box(Modifier.weight(1f).fillMaxHeight().padding(1.dp)) {
                                    VideoPane(trip.clipsFor(c), seekAbs, seekToken, playing, ri == 0 && ci == 0) { abs -> posMs = abs - trip.startMs }
                                    Tag(c.label, Modifier.align(Alignment.TopStart))
                                }
                            }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                VideoPane(trip.clipsFor(cam), seekAbs, seekToken, playing, true) { abs -> posMs = abs - trip.startMs }
                Tag(cam.label, Modifier.align(Alignment.TopStart))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DButton(if (playing) "Pause" else "Play", { playing = !playing }, minWidth = 100.dp, height = 48.dp)
            DButton("−15 s", { seekTo(posMs - 15_000) }, height = 48.dp)
            DButton("+15 s", { seekTo(posMs + 15_000) }, height = 48.dp)
            Scrubber(posMs.toFloat() / total, Modifier.weight(1f)) { f -> seekTo((f * total).toLong()) }
            T("${clockText(posMs)} / ${clockText(total)}", 16, color = C.InkSecondary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!trip.moment) {
                DButton(if (trip.kept) "Remove keep" else "Keep this trip", { scope.launch { engine.setKept(trip, !trip.kept) } }, height = 48.dp)
            }
            DButton(
                if (confirmDelete) "Tap again to delete" else "Delete",
                {
                    if (trip.active) return@DButton
                    if (!confirmDelete) { confirmDelete = true; scope.launch { delay(4000); confirmDelete = false } }
                    else scope.launch { engine.delete(trip) }
                },
                style = if (confirmDelete) BtnStyle.Dark else BtnStyle.Lightest, height = 48.dp,
            )
            Box(Modifier.weight(1f))
            T("${bytesText(trip.sizeBytes)} · ${trip.clips.size} clips", 15, color = C.InkMuted, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier) {
    Box(modifier.padding(10.dp).clip(Radius4).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        T(text, 14, FontWeight.Medium, Color.White)
    }
}

@Composable
private fun Scrubber(fraction: Float, modifier: Modifier, onSeek: (Float) -> Unit) {
    val accent = LocalAccent.current
    BoxWithConstraints(
        modifier.height(40.dp).pointerInput(Unit) { detectTapGestures { o -> onSeek(o.x / size.width) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> onSeek((change.position.x / size.width).coerceIn(0f, 1f)) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        ThinBar(fraction, color = accent, height = 6.dp)
        Box(Modifier.padding(start = (maxWidth - 16.dp) * fraction.coerceIn(0f, 1f)).size(16.dp).clip(CircleShape).background(accent))
    }
}

/** Plays one camera's clips back to back, starting at wall-clock [seekAbs]. Reports the playback position when [report]. */
@Composable
private fun VideoPane(clips: List<Clip>, seekAbs: Long, seekToken: Int, playing: Boolean, report: Boolean, onPos: (Long) -> Unit) {
    val sorted = remember(clips) { clips.sortedBy { it.startMs } }
    var view by remember { mutableStateOf<VideoView?>(null) }
    var idx by remember { mutableIntStateOf(0) }
    var pendingOffset by remember { mutableLongStateOf(0L) }
    val playingNow by androidx.compose.runtime.rememberUpdatedState(playing)

    fun load(v: VideoView, i: Int, offset: Long) {
        val c = sorted.getOrNull(i) ?: return
        idx = i
        pendingOffset = offset
        v.setVideoPath(c.file.path)
    }

    LaunchedEffect(seekToken, sorted, view) {
        val v = view ?: return@LaunchedEffect
        if (sorted.isEmpty()) return@LaunchedEffect
        val i = sorted.indexOfLast { it.startMs <= seekAbs }.let { if (it < 0) 0 else it }
        val c = sorted[i]
        // Past the end of this clip and a later one exists: take the next one from its start.
        if (seekAbs > c.endMs && i + 1 < sorted.size) load(v, i + 1, 0) else load(v, i, (seekAbs - c.startMs).coerceAtLeast(0))
    }
    LaunchedEffect(playing, view) {
        val v = view ?: return@LaunchedEffect
        if (playing) v.start() else if (v.isPlaying) v.pause()
    }
    LaunchedEffect(view, report, idx) {
        while (report) {
            val v = view
            val c = sorted.getOrNull(idx)
            if (v != null && c != null && v.isPlaying) onPos(c.startMs + v.currentPosition)
            delay(300)
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            VideoView(c).also { v ->
                v.setOnPreparedListener { mp ->
                    mp.isLooping = false
                    if (pendingOffset > 0) v.seekTo(pendingOffset.toInt())
                    if (playingNow) v.start()
                }
                v.setOnCompletionListener {
                    val next = idx + 1
                    if (next < sorted.size) load(v, next, 0)
                }
                v.setOnErrorListener { _, _, _ ->
                    val next = idx + 1
                    if (next < sorted.size) load(v, next, 0)
                    true
                }
                view = v
            }
        },
    )
}

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsTab(engine: DashCamEngine) {
    val s by engine.settings.collectAsState()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            DCard(Modifier.weight(1f)) {
                Eyebrow("When to record")
                LineSegmented(StartMode.entries.map { it.label }, s.startMode.ordinal, { i -> engine.update { it.copy(startMode = StartMode.entries[i]) } }, height = 52.dp)
                T(
                    if (s.startMode == StartMode.AUTO) "Starts by itself when the car is in D or R, and stops two minutes after you park. You can still start and stop it from the top of this screen."
                    else "Only records when you press Start recording.", 16, color = C.InkMuted, maxLines = 3,
                )
            }
            DCard(Modifier.weight(1f)) {
                Eyebrow("Loop storage")
                LineSegmented(listOf(10, 25, 50).map { "$it GB" }, listOf(10, 25, 50).indexOf(s.storageGb).takeIf { it >= 0 }, { i -> engine.update { it.copy(storageGb = listOf(10, 25, 50)[i]) } }, height = 52.dp)
                T("When the loop is full the oldest clips are replaced. Kept trips and saved clips don't count and are never replaced.", 16, color = C.InkMuted, maxLines = 3)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            DCard(Modifier.weight(1f)) {
                Eyebrow("Cameras")
                Cam.entries.forEach { cam ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        T(cam.label, 19, modifier = Modifier.weight(1f))
                        PillSwitch(cam in s.cams, { on ->
                            engine.update { cur ->
                                val next = if (on) cur.cams + cam else cur.cams - cam
                                if (next.isEmpty()) cur else cur.copy(cams = next)
                            }
                        })
                    }
                    Hairline()
                }
                T("Front and rear are on by default. Changes apply the next time recording starts.", 15, color = C.InkMuted, maxLines = 2)
            }
            DCard(Modifier.weight(1f)) {
                Eyebrow("Picture quality")
                LineSegmented(Quality.entries.map { it.label }, s.quality.ordinal, { i -> engine.update { it.copy(quality = Quality.entries[i]) } }, height = 52.dp)
                T(s.quality.note, 16, color = C.InkMuted, maxLines = 2)
                Hairline()
                Eyebrow("Privacy")
                T("Only the four outside cameras are used, never the cabin. No sound is recorded. Footage stays on this unit in SharkDashCam.", 16, color = C.InkMuted, maxLines = 3)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            DCard(Modifier.weight(1f)) {
                Eyebrow("Save an event")
                val opts = listOf(1, 3, 5, 10, 15)
                LineSegmented(opts.map { "$it min" }, opts.indexOf(s.saveMinutes).takeIf { it >= 0 }, { i -> engine.update { it.copy(saveMinutes = opts[i]) } }, height = 52.dp)
                T("The Save button keeps this much footage from before you pressed it, from every camera, and files it under Saved. Saved events are never replaced.", 16, color = C.InkMuted, maxLines = 3)
            }
            DCard(Modifier.weight(1f)) {
                Eyebrow("Before turning on Automatic")
                T("Automatic starts the cameras when you shift into D or R. Check once in Park first: start recording, open the 360° view, shift to R briefly and make sure BYD's own camera view stays live. Then switch to Automatic.", 16, color = C.InkMuted, maxLines = 5)
            }
        }
    }
}
