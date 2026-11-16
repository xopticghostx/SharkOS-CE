package com.sharkhome.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sharkhome.launcher.Screen
import com.sharkhome.launcher.container
import com.sharkhome.launcher.settings.ClimatePreset
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent
import com.sharkhome.launcher.ui.components.Car
import com.sharkhome.launcher.ui.components.CarImage
import com.sharkhome.launcher.ui.components.CarView
import com.sharkhome.launcher.ui.components.DButton
import com.sharkhome.launcher.ui.components.DCard
import com.sharkhome.launcher.ui.components.DsIcon
import com.sharkhome.launcher.ui.components.Hairline
import com.sharkhome.launcher.ui.components.Ic
import com.sharkhome.launcher.ui.components.LabelValue
import com.sharkhome.launcher.ui.components.LineSegmented
import com.sharkhome.launcher.ui.components.Radius4
import com.sharkhome.launcher.ui.components.T
import com.sharkhome.launcher.ui.components.ThinBar
import com.sharkhome.launcher.ui.components.fmt
import com.sharkhome.launcher.ui.components.prefsTick
import com.sharkhome.launcher.weather.Weather
import com.sharkhome.launcher.weather.WeatherRepository
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- model
// ---------------------------------------------------------------- model

/**
 * One widget on the home grid: its type, its cell rectangle ([x], [y], [w], [h] on a [GRID_COLS] × [GRID_ROWS] grid),
 * an optional parameter (the gauge's reading) and the sections the driver switched off in its Options.
 */
data class WidgetSpec(
    val type: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val param: String? = null,
    val hidden: Set<String> = emptySet(),
) {
    fun shows(option: String) = option !in hidden
    fun overlaps(o: WidgetSpec) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
    fun fits() = x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= GRID_COLS && y + h <= GRID_ROWS
}

data class WidgetOption(val id: String, val label: String)

data class WidgetType(
    val id: String,
    val title: String,
    val about: String,
    val w: Int,
    val h: Int,
    val minW: Int = 2,
    val minH: Int = 1,
    val multiple: Boolean = false,
    val options: List<WidgetOption> = emptyList(),
)

const val GRID_COLS = 12
const val GRID_ROWS = 8

/** Default home screen: vehicle on the left, climate + shortcuts in the middle, media + map on the right. */
const val DEFAULT_GRID = "vehicle,0,0,4,8;climate,4,0,4,5;shortcuts,4,5,4,3;media,8,0,4,5;map,8,5,4,3"

/** Ready-made widget combinations for the edit banner's Layout button (all fit the 12 × 8 grid, no overlaps). */
/** Type and position of each widget, sorted: two layouts with the same key look the same. */
private fun layoutKey(list: List<WidgetSpec>) = list.map { "${it.type},${it.x},${it.y},${it.w},${it.h}" }.sorted().joinToString(";")

val HOME_LAYOUT_PRESETS: List<Pair<String, String>> = listOf(
    "Default" to DEFAULT_GRID,
    "Driver focus" to "vehicle,0,0,4,8;power,4,0,4,2;trip,4,2,4,2;offroad,4,4,4,2;quick_controls,4,6,4,2;weather,8,0,4,3;media,8,3,4,5",
    "Charging & climate" to "climate,0,0,4,5;charging,0,5,4,3;map,4,0,8,5;battery_12v,4,5,4,3;tyres,8,5,4,3",
    "Media & map" to "media,0,0,4,4;weather,0,4,4,4;map,4,0,8,8",
)

private fun opts(vararg p: Pair<String, String>) = p.map { WidgetOption(it.first, it.second) }

// Lazy: it refers to QUICK_TILES, which is declared further down this file.
val WIDGET_TYPES: List<WidgetType> by lazy { listOf(
    WidgetType("vehicle", "Shark 6", "Battery, fuel, range, power bar, tyres. Switches to the driving view in D/R.", 4, 8, 3, 3,
        options = opts("image" to "Car picture", "energy" to "Battery & fuel", "range" to "Range", "power" to "Power bar",
            "odometer" to "Odometer", "averages" to "Average use", "charging" to "Charging & V2L", "tyres" to "Tyre pressures")),
    WidgetType("climate", "Climate", "Temperatures, fan, A/C and demist, seat heat/vent, presets.", 4, 5, 3, 2,
        options = opts("buttons" to "Wheel / Sync / Auto buttons", "temps" to "Temperatures", "fan" to "Fan speed",
            "tiles" to "A/C, recirculate & demist", "seats" to "Seat heat & vent", "presets" to "Presets")),
    WidgetType("media", "Media", "Now playing, volume, source and equaliser.", 4, 5, 3, 2,
        options = opts("controls" to "Track & controls", "volume" to "Volume", "source" to "Source", "eq" to "Equaliser")),
    WidgetType("map", "Map", "Live map, or projects Android Auto maps (Google Maps / Waze) when active.", 4, 3, 2, 2,
        options = opts("speedo" to "Waze speedometer", "search" to "Navigate bar", "places" to "Home & Work")),
    WidgetType("shortcuts", "Apps & shortcuts", "Favourite apps plus Quick, Off-Road, Gauges, Vehicle and Apps.", 4, 3, 3, 1,
        options = opts("apps" to "Favourite apps", "pages" to "Page shortcuts")),
    WidgetType("weather", "Weather", "Now and the next 5 days for where the car is.", 4, 3, 2, 2,
        options = opts("forecast" to "5-day forecast")),
    WidgetType("gauge", "Gauge", "Any reading you like: speed, power, battery, tilt, tyres…", 3, 3, 2, 1, multiple = true),
    WidgetType("power", "Power", "Big power / regen bar that reacts to the pedal.", 4, 2, 2, 2),
    WidgetType("trip", "Trip", "This trip's distance, time and energy.", 3, 2, 2, 2),
    WidgetType("clock", "Clock", "Big time and date.", 3, 2, 2, 1, options = opts("date" to "Date")),
    WidgetType("offroad", "Off-road", "Pitch, roll, drive mode and hill descent.", 3, 2, 2, 2),
    WidgetType("charging", "Charging", "Charge level, power and time to full.", 3, 2, 2, 2),
    WidgetType("tyres", "Tyres", "All four tyre pressures (and temperatures).", 4, 3, 2, 2, options = opts("temps" to "Tyre temperatures")),
    WidgetType("tow", "Tow & Trailer", "Trailer Mode switch, trailer size, solo light check and Sport feel.", 4, 3, 3, 2),
    WidgetType("fuel_ext", "Fuel", "Litres left (from your tank size), fuel range and economy.", 3, 2, 2, 2),
    WidgetType("battery_12v", "12V & Energy Split", "12V battery voltage, HV health, SOC target and the last 50 km energy split.", 4, 2, 3, 2),
    WidgetType("chassis", "Steering & Pedals", "Live steering angle, throttle & brake bars and the feel settings.", 4, 2, 3, 2),
    WidgetType("motors", "Motors & Engine", "Front/rear motor torque, bus voltage and engine RPM (unverified while driving).", 4, 2, 3, 2),
    WidgetType("quick_controls", "Quick Controls", "One-tap toggles: wheel heat, HUD, wireless pad, blind spot, trailer, regen…", 4, 2, 2, 1,
        options = QUICK_TILES.map { WidgetOption(it.id, it.label) }),
) }

fun widgetType(id: String) = WIDGET_TYPES.firstOrNull { it.id == id }

/** "type,x,y,w,h,param,hidden+hidden;…" */
fun serializeGrid(list: List<WidgetSpec>): String = list.joinToString(";") { s ->
    listOf(s.type, s.x, s.y, s.w, s.h, s.param ?: "", s.hidden.joinToString("+")).joinToString(",")
}

fun parseGrid(raw: String): List<WidgetSpec> {
    val out = mutableListOf<WidgetSpec>()
    raw.split(';').forEach { e ->
        val f = e.split(',')
        if (f.size < 5) return@forEach
        val t = widgetType(f[0].trim()) ?: return@forEach
        val n = f.subList(1, 5).map { it.trim().toIntOrNull() ?: return@forEach }
        val s = WidgetSpec(
            t.id, n[0], n[1], n[2].coerceAtLeast(1), n[3].coerceAtLeast(1),
            f.getOrNull(5)?.trim()?.ifBlank { null },
            f.getOrNull(6)?.split('+')?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
        )
        // Drop anything off the grid or on top of an earlier widget rather than drawing a mess.
        if (s.fits() && out.none { it.overlaps(s) }) out.add(s)
    }
    return out
}

/**
 * The old home layout (before v0.6) was three columns of "type[=param]:size" (size = share of the column height,
 * 0 = as tall as needed). Turn it into grid rectangles so the driver keeps their layout.
 */
fun migrateColumns(old: String): List<WidgetSpec> {
    val out = mutableListOf<WidgetSpec>()
    old.split(';').take(3).forEachIndexed { ci, col ->
        val items = col.split(',').filter { it.isNotBlank() }.mapNotNull { w ->
            val parts = w.split(':')
            val head = parts[0].split('=')
            val t = widgetType(head[0].trim()) ?: return@mapNotNull null
            Triple(t, head.getOrNull(1)?.trim(), parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 4)
        }
        if (items.isEmpty()) return@forEachIndexed
        val fixed = items.count { it.third <= 0 }
        val share = items.filter { it.third > 0 }.sumOf { it.third }.coerceAtLeast(1)
        val free = (GRID_ROWS - fixed).coerceAtLeast(0)
        var y = 0
        items.forEachIndexed { i, (t, param, size) ->
            val left = GRID_ROWS - y
            if (left <= 0) return@forEachIndexed
            var h = if (size <= 0) 1 else (free * size.toDouble() / share).roundToInt().coerceAtLeast(t.minH)
            if (i == items.lastIndex) h = left
            h = h.coerceIn(1, left)
            out.add(WidgetSpec(t.id, ci * 4, y, 4, h, param))
            y += h
        }
    }
    return out.ifEmpty { parseGrid(DEFAULT_GRID) }
}

/** First free spot for a new widget: its default size, else its smallest. */
private fun place(list: List<WidgetSpec>, t: WidgetType): WidgetSpec? {
    for ((w, h) in listOf(t.w to t.h, t.minW to t.h, t.w to t.minH, t.minW to t.minH).distinct()) {
        for (y in 0..GRID_ROWS - h) for (x in 0..GRID_COLS - w) {
            val s = WidgetSpec(t.id, x, y, w, h, if (t.id == "gauge") "speed" else null)
            if (list.none { it.overlaps(s) }) return s
        }
    }
    return null
}

/** Edit mode for the home screen; the side menu's "Layout" button (or holding a widget) turns it on. */
object HomeEdit {
    val editing = MutableStateFlow(false)
    val overlayOpen = MutableStateFlow(false)
}

/** Which edges of a widget a resize handle moves. */
private data class Edges(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false)

// ---------------------------------------------------------------- layout

/**
 * The home screen: widgets on a 12 × 8 grid, like Android's home screen. Hold a widget (or tap Layout) to edit:
 * drag a widget to move it, drag the handles on its edges to resize it, ⚙ chooses what it shows. When BYD's split
 * screen makes the window narrow, the same widgets stack in one scrolling column.
 */
@Composable
fun HomeLayout(car: Car, speed: Double?, driving: Boolean, onNavigate: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    prefsTick()
    LaunchedEffect(Unit) { c.weather.start() }
    val editing by HomeEdit.editing.collectAsState()
    val stored = c.prefs.homeGrid
    val oldLayout = c.prefs.homeLayout
    val widgets = remember(stored, oldLayout) { if (stored != null) parseGrid(stored) else migrateColumns(oldLayout) }
    var picker by remember { mutableStateOf(false) }
    var optionsFor by remember { mutableStateOf<Int?>(null) }
    var addClimatePreset by remember { mutableStateOf(false) }

    LaunchedEffect(picker, optionsFor, addClimatePreset) {
        HomeEdit.overlayOpen.value = picker || optionsFor != null || addClimatePreset
    }
    DisposableEffect(Unit) {
        onDispose { HomeEdit.overlayOpen.value = false }
    }

    fun save(list: List<WidgetSpec>) {
        c.prefs.homeGrid = serializeGrid(list)
    }

    val content: @Composable (WidgetSpec, Modifier) -> Unit = { spec, m ->
        WidgetContent(spec, car, speed, driving, onNavigate, m, onAddClimatePreset = { addClimatePreset = true })
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (editing) EditBanner(
                onAdd = { picker = true },
                onReset = { c.prefs.homeGrid = DEFAULT_GRID },
                onDone = { HomeEdit.editing.value = false },
                layoutName = HOME_LAYOUT_PRESETS.firstOrNull { layoutKey(parseGrid(it.second)) == layoutKey(widgets) }?.first ?: "Custom",
                onNextLayout = {
                    val cur = HOME_LAYOUT_PRESETS.indexOfFirst { layoutKey(parseGrid(it.second)) == layoutKey(widgets) }
                    c.prefs.homeGrid = HOME_LAYOUT_PRESETS[(cur + 1) % HOME_LAYOUT_PRESETS.size].second
                },
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth >= 1200.dp) {
                    GridArea(widgets, editing, maxWidth, maxHeight, ::save, { optionsFor = it }, content)
                } else {
                    NarrowList(widgets, editing, ::save, { optionsFor = it }, content)
                }
            }
        }

        if (picker) {
            WidgetPicker(
                car = car,
                present = widgets.map { it.type }.toSet(),
                onPick = { t ->
                    val spot = place(widgets, t)
                    if (spot == null) {
                        Toast.makeText(ctx, "No room for ${t.title}: make a widget smaller or remove one first", Toast.LENGTH_LONG).show()
                    } else {
                        save(widgets + spot)
                    }
                    picker = false
                },
                onClose = { picker = false },
            )
        }
        optionsFor?.let { i ->
            val spec = widgets.getOrNull(i)
            if (spec == null) {
                optionsFor = null
            } else {
                WidgetOptions(
                    spec = spec,
                    onChange = { new -> save(widgets.toMutableList().also { it[i] = new }) },
                    onRemove = { save(widgets.filterIndexed { j, _ -> j != i }); optionsFor = null },
                    onClose = { optionsFor = null },
                )
            }
        }
        if (addClimatePreset) {
            ClimatePresetModal(
                car = car,
                initial = car.capture(ClimatePreset.nextName(c.prefs.presets)),
                isEdit = false,
                onSave = { preset ->
                    c.prefs.customPresets = c.prefs.customPresets + preset
                    addClimatePreset = false
                    Toast.makeText(ctx, "Saved \"${preset.name}\"", Toast.LENGTH_SHORT).show()
                },
                onClose = { addClimatePreset = false },
            )
        }
    }
}

@Composable
private fun GridArea(
    widgets: List<WidgetSpec>,
    editing: Boolean,
    width: Dp,
    height: Dp,
    save: (List<WidgetSpec>) -> Unit,
    onOptions: (Int) -> Unit,
    content: @Composable (WidgetSpec, Modifier) -> Unit,
) {
    val gap = 16.dp
    val pitchX = (width + gap) / GRID_COLS
    val pitchY = (height + gap) / GRID_ROWS
    val density = LocalDensity.current
    val pxX = with(density) { pitchX.toPx() }
    val pxY = with(density) { pitchY.toPx() }
    val accent = LocalAccent.current
    val current by rememberUpdatedState(widgets)

    // The widget being dragged, how far the finger has moved, and which edges it moves (null = the whole widget).
    var active by remember { mutableStateOf<Int?>(null) }
    var delta by remember { mutableStateOf(Offset.Zero) }
    var edges by remember { mutableStateOf<Edges?>(null) }

    fun candidate(s: WidgetSpec, d: Offset, e: Edges?): WidgetSpec {
        val dx = (d.x / pxX).roundToInt()
        val dy = (d.y / pxY).roundToInt()
        if (e == null) return s.copy(x = (s.x + dx).coerceIn(0, GRID_COLS - s.w), y = (s.y + dy).coerceIn(0, GRID_ROWS - s.h))
        val t = widgetType(s.type) ?: return s
        var x = s.x
        var y = s.y
        var w = s.w
        var h = s.h
        if (e.left) { val nx = (s.x + dx).coerceIn(0, s.x + s.w - t.minW); w = s.w + (s.x - nx); x = nx }
        if (e.right) w = (s.w + dx).coerceIn(t.minW, GRID_COLS - s.x)
        if (e.top) { val ny = (s.y + dy).coerceIn(0, s.y + s.h - t.minH); h = s.h + (s.y - ny); y = ny }
        if (e.bottom) h = (s.h + dy).coerceIn(t.minH, GRID_ROWS - s.y)
        return s.copy(x = x, y = y, w = w, h = h)
    }

    fun valid(i: Int, s: WidgetSpec) = s.fits() && current.withIndex().none { (j, o) -> j != i && o.overlaps(s) }

    fun begin(i: Int, e: Edges?) { active = i; delta = Offset.Zero; edges = e }
    fun finish() {
        val i = active ?: return
        val s = current.getOrNull(i)
        if (s != null) {
            val cand = candidate(s, delta, edges)
            if (cand != s && valid(i, cand)) save(current.toMutableList().also { it[i] = cand })
        }
        active = null
        delta = Offset.Zero
        edges = null
    }

    Box(Modifier.fillMaxSize()) {
        if (editing) {
            // Faint cell grid so it's clear where things will snap.
            for (gy in 0 until GRID_ROWS) for (gx in 0 until GRID_COLS) {
                Box(
                    Modifier.offset(pitchX * gx, pitchY * gy).size(pitchX - gap, pitchY - gap)
                        .clip(Radius4).border(1.dp, C.Line.copy(alpha = 0.45f), Radius4),
                )
            }
        }
        widgets.forEachIndexed { i, s ->
            key(i, s) {
                val moving = active == i && edges == null
                Box(
                    Modifier.offset(pitchX * s.x, pitchY * s.y).size(pitchX * s.w - gap, pitchY * s.h - gap)
                        .zIndex(if (active == i) 2f else 0f)
                        .graphicsLayer {
                            if (moving) {
                                translationX = delta.x
                                translationY = delta.y
                                alpha = 0.85f
                            }
                        },
                ) {
                    WidgetFrame(
                        spec = s,
                        editing = editing,
                        onOptions = { onOptions(i) },
                        onRemove = { save(current.filterIndexed { j, _ -> j != i }) },
                        dragModifier = Modifier.pointerInput(i, pxX, pxY) {
                            detectDragGestures(
                                onDragStart = { begin(i, null) },
                                onDragEnd = { finish() },
                                onDragCancel = { finish() },
                            ) { ch, d -> ch.consume(); delta += d }
                        },
                        handle = { e -> Modifier.pointerInput(i, e, pxX, pxY) {
                            detectDragGestures(
                                onDragStart = { begin(i, e) },
                                onDragEnd = { finish() },
                                onDragCancel = { finish() },
                            ) { ch, d -> ch.consume(); delta += d }
                        } },
                        content = content,
                    )
                }
            }
        }
        // Where the widget will land: accent outline if it fits, red if it would overlap another widget.
        active?.let { i ->
            val s = widgets.getOrNull(i) ?: return@let
            val g = candidate(s, delta, edges)
            val ok = valid(i, g)
            Box(
                Modifier.offset(pitchX * g.x, pitchY * g.y).size(pitchX * g.w - gap, pitchY * g.h - gap).zIndex(3f)
                    .clip(Radius4).background((if (ok) accent else C.Alert).copy(alpha = 0.12f))
                    .border(3.dp, if (ok) accent else C.Alert, Radius4),
                contentAlignment = Alignment.Center,
            ) {
                T("${g.w} × ${g.h}", 22, FontWeight.Medium, if (ok) accent else C.Alert)
            }
        }
    }
}

/** Narrow window (BYD split screen): widgets stack in one scrolling column, in grid order. */
@Composable
private fun NarrowList(
    widgets: List<WidgetSpec>,
    editing: Boolean,
    save: (List<WidgetSpec>) -> Unit,
    onOptions: (Int) -> Unit,
    content: @Composable (WidgetSpec, Modifier) -> Unit,
) {
    val order = widgets.withIndex().sortedWith(compareBy({ it.value.y }, { it.value.x }))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        order.forEach { (i, s) ->
            key(i, s) {
                val h = when (s.type) {
                    "shortcuts" -> if (s.h <= 1) 104.dp else (s.h * 120).coerceIn(220, 480).dp
                    "vehicle" -> 980.dp
                    "climate" -> (s.h * 120).coerceIn(600, 820).dp
                    "media" -> 640.dp
                    else -> (s.h * 120).coerceIn(160, 700).dp
                }
                Box(Modifier.fillMaxWidth().height(h)) {
                    WidgetFrame(
                        spec = s,
                        editing = editing,
                        onOptions = { onOptions(i) },
                        onRemove = { save(widgets.filterIndexed { j, _ -> j != i }) },
                        dragModifier = Modifier,
                        handle = null,
                        content = content,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp)) // room under the last card
    }
}

@Composable
private fun WidgetContent(
    spec: WidgetSpec,
    car: Car,
    speed: Double?,
    driving: Boolean,
    onNavigate: (Screen) -> Unit,
    m: Modifier,
    preview: Boolean = false,
    onAddClimatePreset: (() -> Unit)? = null,
) {
    val hidden = spec.hidden
    when (spec.type) {
        "vehicle" -> if (driving && !preview) DrivingCard(car, speed, m) else VehicleCard(car, m, hidden) { onNavigate(Screen.VEHICLE) }
        "climate" -> ClimateCard(car, m, hidden, onOpen = { onNavigate(Screen.CLIMATE) }, onAddPreset = if (preview) null else onAddClimatePreset)
        "media" -> MediaCard(m, hidden)
        "map" -> if (preview) MapPreviewCard(m, hidden) else MapCard(car, speed, m, hidden)
        "shortcuts" -> ShortcutRow(onNavigate, m, hidden, spec)
        "weather" -> WeatherWidget(m, hidden)
        "gauge" -> GaugeWidget(spec, car, m)
        "power" -> DCard(m) { PowerBar(car) }
        "trip" -> TripWidget(car, m)
        "clock" -> ClockWidget(m, hidden)
        "offroad" -> OffRoadWidget(car, m)
        "charging" -> ChargingWidget(car, m)
        "tyres" -> TyresWidget(car, m, hidden)
        "tow" -> TowWidget(car, m) { onNavigate(Screen.OFFROAD) }
        "fuel_ext" -> FuelWidget(car, m) { onNavigate(Screen.VEHICLE) }
        "battery_12v" -> Battery12vWidget(car, m) { onNavigate(Screen.VEHICLE) }
        "chassis" -> ChassisWidget(car, m) { onNavigate(Screen.OFFROAD) }
        "motors" -> MotorsWidget(car, m) { onNavigate(Screen.GAUGES) }
        "quick_controls" -> QuickControlsWidget(car, spec, m, onOpen = { onNavigate(Screen.QUICK_SETTINGS) }, preview = preview)
    }
}

// ---------------------------------------------------------------- edit mode

@Composable
private fun EditBanner(onAdd: () -> Unit, onReset: () -> Unit, onDone: () -> Unit, layoutName: String, onNextLayout: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Radius4).background(C.SurfaceCard).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            T("Editing home screen", 22, FontWeight.Medium)
            T("Drag a widget to move it. Drag the round handles on its edges to resize it. ⚙ chooses what it shows.", 16, color = C.InkMuted, maxLines = 2)
        }
        DButton("Layout: $layoutName", onNextLayout, minWidth = 220.dp)
        DButton("+ Add widget", onAdd)
        DButton("Reset layout", onReset)
        DButton("Done", onDone, selected = true, minWidth = 140.dp)
    }
}

/**
 * A widget on the home screen. Outside edit mode it's just the content (with hold-to-edit). In edit mode a dimmed
 * overlay takes the touches: drag it to move, the four edge handles and the corner resize, ⚙ opens the options.
 */
@Composable
private fun WidgetFrame(
    spec: WidgetSpec,
    editing: Boolean,
    onOptions: () -> Unit,
    onRemove: () -> Unit,
    dragModifier: Modifier,
    handle: ((Edges) -> Modifier)?,
    content: @Composable (WidgetSpec, Modifier) -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val accent = LocalAccent.current
    // The hold detector stays attached in edit mode too (it ignores touches then): swapping it out mid-touch cancelled it
    // before it could swallow the release, which then landed on the edit controls (added a stray widget on 8 Nov).
    Box(Modifier.fillMaxSize().holdToEdit { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }) {
        content(spec, Modifier.fillMaxSize())
        if (!editing) return@Box
        val t = widgetType(spec.type)
        val title = t?.title ?: spec.type
        val small = spec.h <= 1
        Box(
            Modifier.matchParentSize().clip(Radius4).background(Color.Black.copy(alpha = 0.62f))
                .border(2.dp, accent, Radius4).then(dragModifier),
            contentAlignment = Alignment.Center,
        ) {
            if (small) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(title, 20, FontWeight.Medium, Color.White)
                    DButton("⚙ Options", onOptions, height = 48.dp, textSize = 16)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T(if (spec.type == "gauge") "Gauge · ${ALL_GAUGES.firstOrNull { it.id == spec.param }?.label ?: spec.param}" else title, 22, FontWeight.Medium, Color.White)
                    T("${spec.w} × ${spec.h}", 16, color = Color.White.copy(alpha = 0.7f))
                    DButton("⚙ Options", onOptions, height = 52.dp, textSize = 17)
                }
            }
        }
        // Remove badge in the corner. Two taps: the first arms it ("Remove?" for 3 s), the second deletes, so a stray
        // touch can't delete a widget.
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(armed) { if (armed) { kotlinx.coroutines.delay(3000); armed = false } }
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).height(48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                .background(C.Alert)
                .clickable(onClickLabel = "Remove $title") { if (armed) onRemove() else armed = true },
            contentAlignment = Alignment.Center,
        ) { if (armed) T("Remove?", 17, FontWeight.Medium, Color.White, modifier = Modifier.padding(horizontal = 16.dp)) else T("×", 30, FontWeight.Medium, Color.White) }
        if (handle != null) {
            val edgeH = if (small) 40.dp else 56.dp
            val edgeOff = if (small) 8.dp else 0.dp
            ResizeHandle(Modifier.align(Alignment.CenterStart).then(handle(Edges(left = true))), accent)
            ResizeHandle(Modifier.align(Alignment.CenterEnd).then(handle(Edges(right = true))), accent)
            ResizeHandle(Modifier.align(Alignment.TopCenter).offset(y = -edgeOff).then(handle(Edges(top = true))), accent, height = edgeH)
            ResizeHandle(Modifier.align(Alignment.BottomCenter).offset(y = edgeOff).then(handle(Edges(bottom = true))), accent, height = edgeH)
            ResizeHandle(Modifier.align(Alignment.BottomEnd).then(handle(Edges(right = true, bottom = true))), accent, corner = true)
        }
    }
}

/** A round grab handle on a widget's edge (bigger touch area than it looks, for gloves and bumpy roads). */
@Composable
private fun ResizeHandle(modifier: Modifier, accent: Color, corner: Boolean = false, width: Dp = 56.dp, height: Dp = 56.dp) {
    Box(modifier.size(width, height), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(if (corner) 30.dp else 24.dp).clip(CircleShape).background(Color.White)
                .border(4.dp, accent, CircleShape),
        )
    }
}

/**
 * Hold anywhere on a widget (~0.5 s, finger still) to enter edit mode, like Android's home screen. Watches touches in
 * the Initial pass so it works on top of buttons, and swallows the release so the button under the finger doesn't
 * fire. Moving the finger (dragging the volume bar, panning the map) cancels it.
 */
private fun Modifier.holdToEdit(onStart: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (HomeEdit.editing.value) return@awaitEachGesture
            val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                    if (!ch.pressed) return@withTimeoutOrNull true
                    if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE") true
            }
            if (endedEarly == null) {
                // Held long enough: edit mode, and eat the rest of this touch.
                HomeEdit.editing.value = true
                onStart()
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    ev.changes.forEach { it.consume() }
                    if (ev.changes.none { it.pressed }) break
                }
            }
        }
    },
)

@Composable
private fun Modal(
    title: String,
    onClose: () -> Unit,
    width: Dp = 1100.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(minOf(width, maxWidth * 0.94f)).fillMaxHeight(0.86f).clip(Radius4).background(C.SurfaceCard).clickable { }.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(title, 28, FontWeight.Medium, modifier = Modifier.weight(1f))
                DButton("Close", onClose)
            }
            content()
        }
    }
}

@Composable
private fun WidgetPicker(car: Car, present: Set<String>, onPick: (WidgetType) -> Unit, onClose: () -> Unit) {
    Modal("Add a widget", onClose, width = 1280.dp) {
        LazyVerticalGrid(
            GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(WIDGET_TYPES, key = { it.id }) { t ->
                val added = t.id in present && !t.multiple
                Column(
                    Modifier.fillMaxWidth().height(312.dp).clip(Radius4).background(C.P.btnLightest)
                        .border(1.dp, C.Line, Radius4)
                        .clickable(enabled = !added) { onPick(t) }.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(184.dp).clip(Radius4).background(C.Surface).padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        WidgetPreview(type = t, car = car, modifier = Modifier.fillMaxSize())
                        if (added) {
                            Box(Modifier.matchParentSize().background(C.Surface.copy(alpha = 0.5f)))
                        }
                        // Capture taps over the preview so internal widget buttons don't intercept tile selection.
                        Box(
                            Modifier.matchParentSize().clearAndSetSemantics {}.clickable(
                                enabled = !added,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onPick(t) },
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            T(t.title, 20, FontWeight.Medium, if (added) C.InkMuted else C.Ink, modifier = Modifier.weight(1f), maxLines = 1)
                            Box(Modifier.clip(Radius4).background(C.SurfaceCard).padding(horizontal = 8.dp, vertical = 3.dp)) {
                                T(if (added) "Added" else "${t.w} × ${t.h}", 13, FontWeight.Medium, C.InkMuted)
                            }
                        }
                        T(if (added) "Already on the home screen" else t.about, 15, color = C.InkMuted, maxLines = 2)
                    }
                }
            }
        }
    }
}

/**
 * Renders a live, non-interactive scaled preview of [type] at its natural grid proportions inside the picker card.
 */
@Composable
private fun WidgetPreview(type: WidgetType, car: Car, modifier: Modifier = Modifier) {
    val previewSpec = remember(type.id) {
        WidgetSpec(
            type = type.id,
            x = 0,
            y = 0,
            w = type.w,
            h = type.h,
            param = if (type.id == "gauge") "speed" else null,
        )
    }
    val natW = (type.w * 150 - 16).coerceAtLeast(180).dp
    val natH = (type.h * 124 - 16).coerceAtLeast(100).dp
    Layout(
        modifier = modifier.clipToBounds().clearAndSetSemantics {},
        content = {
            Box(Modifier.fillMaxSize()) {
                WidgetContent(
                    spec = previewSpec,
                    car = car,
                    speed = null,
                    driving = false,
                    onNavigate = {},
                    m = Modifier.fillMaxSize(),
                    preview = true,
                )
            }
        },
    ) { measurables, constraints ->
        val natWpx = natW.roundToPx().coerceAtLeast(1)
        val natHpx = natH.roundToPx().coerceAtLeast(1)
        val availW = constraints.maxWidth.coerceAtLeast(1)
        val availH = constraints.maxHeight.coerceAtLeast(1)
        val scale = minOf(availW.toFloat() / natWpx, availH.toFloat() / natHpx).coerceAtMost(1f)
        val placeable = measurables.first().measure(Constraints.fixed(natWpx, natHpx))
        layout(availW, availH) {
            val x = (availW - natWpx) / 2
            val y = (availH - natHpx) / 2
            placeable.placeRelativeWithLayer(x, y) {
                scaleX = scale
                scaleY = scale
            }
        }
    }
}

/** Lightweight static preview of [MapCard] so the widget picker never spins up MapLibre GL TextureViews. */
@Composable
private fun MapPreviewCard(modifier: Modifier, hidden: Set<String> = emptySet()) {
    val accent = LocalAccent.current
    val roadLine = C.LineStrong
    val streetLine = C.Line
    val blockTint = C.SurfaceCard.copy(alpha = 0.7f)
    val navArrowFill = C.P.surfaceRaised
    DCard(modifier, padding = 0.dp, spacing = 0.dp) {
        Box(Modifier.weight(1f).fillMaxWidth().background(C.P.mapGround)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawRect(blockTint, Offset(w * 0.08f, h * 0.14f), Size(w * 0.24f, h * 0.28f))
                drawRect(blockTint, Offset(w * 0.62f, h * 0.45f), Size(w * 0.28f, h * 0.32f))
                // Minor street grid
                drawLine(streetLine, Offset(0f, h * 0.28f), Offset(w, h * 0.22f), strokeWidth = 4f)
                drawLine(streetLine, Offset(0f, h * 0.68f), Offset(w, h * 0.74f), strokeWidth = 4f)
                drawLine(streetLine, Offset(w * 0.22f, 0f), Offset(w * 0.28f, h), strokeWidth = 4f)
                drawLine(streetLine, Offset(w * 0.75f, 0f), Offset(w * 0.68f, h), strokeWidth = 4f)
                // Main road & route highlight
                drawLine(roadLine, Offset(w * 0.1f, h), Offset(w * 0.88f, 0f), strokeWidth = 10f, cap = StrokeCap.Round)
                drawLine(accent.copy(alpha = 0.85f), Offset(w * 0.48f, h * 0.52f), Offset(w * 0.72f, h * 0.21f), strokeWidth = 10f, cap = StrokeCap.Round)
                // Vehicle puck
                val center = Offset(w * 0.48f, h * 0.52f)
                drawCircle(accent.copy(alpha = 0.22f), radius = 26f, center = center)
                drawCircle(accent, radius = 14f, center = center)
                val arrow = Path().apply {
                    moveTo(center.x, center.y - 8f)
                    lineTo(center.x + 7f, center.y + 6f)
                    lineTo(center.x, center.y + 2f)
                    lineTo(center.x - 7f, center.y + 6f)
                    close()
                }
                drawPath(arrow, color = navArrowFill)
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.clip(Radius4).background(C.SurfacePanel.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T("BYD Navigation", 14, FontWeight.Medium)
                }
                Row(
                    Modifier.clip(Radius4).background(C.SurfacePanel.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DsIcon(Ic.SplitScreen, C.Ink, 14.dp)
                    T("Split", 14, FontWeight.Medium)
                }
            }
            if ("speedo" !in hidden) {
                WazeSpeedo(
                    speed = 60.0,
                    speedLimit = 60,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp),
                )
            }
        }
        if ("search" !in hidden) Hairline()
        if ("search" !in hidden) Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DsIcon(Ic.Search, C.InkMuted, 24.dp)
            T("Navigate", 18, FontWeight.Medium, C.InkMuted, spacing = -0.01f)
        }
        if ("places" !in hidden) Hairline()
        if ("places" !in hidden) Row(Modifier.fillMaxWidth().height(64.dp)) {
            Row(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DsIcon(Ic.Home, C.Ink, 22.dp)
                Column {
                    T("Home", 16, FontWeight.Medium)
                    T("Saved in BYD Navigation", 14, color = C.InkMuted)
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(C.Line))
            Row(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DsIcon(Ic.Work, C.Ink, 22.dp)
                Column {
                    T("Work", 16, FontWeight.Medium)
                    T("Saved in BYD Navigation", 14, color = C.InkMuted)
                }
            }
        }
    }
}

/** ⚙ Options for one widget: which sections it shows, and for a gauge which reading. */
@Composable
private fun WidgetOptions(spec: WidgetSpec, onChange: (WidgetSpec) -> Unit, onRemove: () -> Unit, onClose: () -> Unit) {
    val t = widgetType(spec.type) ?: return
    val c = LocalContext.current.container
    val car = com.sharkhome.launcher.ui.components.rememberCar()
    val motion by c.motion.motion.collectAsState()
    val accent = LocalAccent.current
    Modal("${t.title} · options", onClose) {
        prefsTick()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (spec.type == "map") {
                val sources = listOf("auto", "androidauto", "osm")
                val sourceLabels = listOf("Auto (AA maps)", "Always AA", "Local map")
                T("Map source", 20, FontWeight.Medium, C.InkSecondary)
                LineSegmented(
                    options = sourceLabels,
                    selected = sources.indexOf(c.prefs.mapSource).coerceAtLeast(0),
                    onSelect = { c.prefs.mapSource = sources[it] },
                )
                val modes = listOf("cluster", "main", "tbt")
                val modeLabels = listOf("Map Only (AA)", "Full AA Screen", "Turn-by-Turn HUD")
                T("Android Auto projection", 20, FontWeight.Medium, C.InkSecondary)
                LineSegmented(
                    options = modeLabels,
                    selected = modes.indexOf(c.prefs.aaProjectionMode).coerceAtLeast(0),
                    onSelect = { c.prefs.aaProjectionMode = modes[it] },
                )
            }
            if (spec.type == "gauge") {
                T("Reading", 20, FontWeight.Medium, C.InkSecondary)
                ALL_GAUGES.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { g ->
                            val sel = g.id == spec.param
                            Column(
                                Modifier.weight(1f).height(96.dp).clip(Radius4).background(if (sel) accent else C.P.btnLightest)
                                    .clickable { onChange(spec.copy(param = g.id)) }.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                            ) {
                                T(g.label, 18, FontWeight.Medium, if (sel) Color.White else C.Ink)
                                T("${g.read(car, motion).fmt(g.decimals)} ${g.unit}", 15, color = if (sel) Color.White else C.InkMuted)
                            }
                        }
                        repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
            if (t.options.isNotEmpty()) {
                T("Show", 20, FontWeight.Medium, C.InkSecondary)
                t.options.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { o ->
                            val on = spec.shows(o.id)
                            val unavailable = spec.type == "quick_controls" &&
                                QUICK_TILES.firstOrNull { it.id == o.id }?.let { !car.available(it.key) } == true
                            Row(
                                Modifier.weight(1f).height(72.dp).clip(Radius4).background(C.P.btnLightest)
                                    .clickable { onChange(spec.copy(hidden = if (on) spec.hidden + o.id else spec.hidden - o.id)) }
                                    .padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    T(o.label, 18, FontWeight.Medium)
                                    if (unavailable) T("Not reported by this car: hidden anyway", 14, color = C.InkMuted)
                                }
                                com.sharkhome.launcher.ui.components.PillSwitch(on, { onChange(spec.copy(hidden = if (on) spec.hidden + o.id else spec.hidden - o.id)) })
                            }
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            if (spec.type != "gauge" && t.options.isEmpty()) {
                T("This widget has nothing to switch off. Resize it with the handles on its edges.", 18, color = C.InkMuted, maxLines = 2)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DButton("Show everything", { onChange(spec.copy(hidden = emptySet())) })
            Box(Modifier.weight(1f))
            DButton("× Remove widget", onRemove)
            DButton("Done", onClose, selected = true, minWidth = 140.dp)
        }
    }
}

// ---------------------------------------------------------------- quick control tiles

/** A one-tap car toggle, shared by the Quick Controls widget and the Quick Settings screen. */
internal data class QuickTile(
    val id: String,
    val label: String,
    val icon: Ic,
    val key: String,
    val isOn: (Car) -> Boolean,
    val toggle: (Car) -> Unit,
)

private fun onOff(id: String, label: String, icon: Ic, key: String) =
    QuickTile(id, label, icon, key, { it.on(key) }, { it.toggle(key) })

/**
 * Only tiles whose signal the car actually reports are shown (8 Nov 2026 probe: HUD snow mode, mirror auto-fold,
 * cargo light and screen rotation have no method on this firmware, so they'd be dead buttons).
 */
internal val QUICK_TILES = listOf(
    onOff("wheel", "Wheel Heat", Ic.SteeringHeat, "seat.steeringWheelHeat"),
    onOff("hud", "HUD", Ic.Hud, "advanced.hud"),
    onOff("wireless", "Wireless Pad", Ic.WirelessPad, "advanced.wirelessCharge"),
    onOff("blindspot", "Blind Spot", Ic.BlindSpot, "advanced.blindSpot"),
    onOff("trailer", "Trailer Mode", Ic.Vehicle, "trailer.mode"),
    QuickTile("regen", "High Regen", Ic.Regen, "drive.regenLevel",
        { it.label("drive.regenLevel") == "High" },
        { car -> car.setLabel("drive.regenLevel", if (car.label("drive.regenLevel") == "High") "Standard" else "High") }),
    onOff("hudsnow", "HUD Snow", Ic.HudSnow, "advanced.hudSnow"),
    onOff("cargo", "Bed Light", Ic.CargoLight, "body.cargoLight"),
    onOff("mirrors", "Auto-Fold", Ic.MirrorFold, "advanced.mirrorFold"),
)

// ---------------------------------------------------------------- widgets

@Composable
private fun GaugeWidget(spec: WidgetSpec, car: Car, m: Modifier) {
    val c = LocalContext.current.container
    val motion by c.motion.motion.collectAsState()
    val def = ALL_GAUGES.firstOrNull { it.id == spec.param } ?: ALL_GAUGES[0]
    val colour = def.customColor ?: LocalAccent.current
    // Short or narrow widgets get the compact bar style; bigger ones the dial.
    if (spec.h <= 2 || spec.w <= 2) BarGaugeRow(def, def.read(car, motion), colour, m) {}
    else HeroGauge(def, def.read(car, motion), colour, m) {}
}

private fun weatherIcon(code: Int, day: Boolean): ImageVector = when (code) {
    0, 1 -> if (day) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay
    2 -> Icons.Rounded.WbCloudy
    3, 45, 48 -> Icons.Rounded.Cloud
    51, 53, 55, 56, 57 -> Icons.Rounded.Grain
    61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Rounded.Umbrella
    71, 73, 75, 77, 85, 86 -> Icons.Rounded.AcUnit
    95, 96, 99 -> Icons.Rounded.Bolt
    else -> Icons.Rounded.Cloud
}

@Composable
private fun WeatherWidget(m: Modifier, hidden: Set<String> = emptySet()) {
    val c = LocalContext.current.container
    val w by c.weather.weather.collectAsState()
    val status by c.weather.status.collectAsState()
    DCard(m, onClick = { c.weather.refreshNow() }) {
        val wx: Weather? = w
        if (wx == null) {
            T("Weather", 24, FontWeight.SemiBold)
            T(status, 18, color = C.InkMuted, maxLines = 2)
            return@DCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(weatherIcon(wx.code, wx.isDay), null, tint = C.Ink, modifier = Modifier.size(64.dp))
            T("${wx.tempC.fmt(0)}°", 72, FontWeight.Light, lineHeight = 78)
            Column(Modifier.weight(1f)) {
                T(WeatherRepository.describe(wx.code), 22, FontWeight.Medium)
                T(listOfNotNull(wx.feelsC?.let { "Feels ${it.fmt(0)}°" }, wx.windKmh?.let { "Wind ${it.fmt(0)} km/h" }).joinToString(" · "), 16, color = C.InkMuted)
                wx.days.firstOrNull()?.let { d -> T("H ${d.maxC.fmt(0)}°  L ${d.minC.fmt(0)}°" + (d.rainPct?.let { "  ·  Rain $it%" } ?: ""), 16, color = C.InkMuted) }
            }
        }
        Box(Modifier.weight(1f))
        if (wx.days.size > 1 && "forecast" !in hidden) {
            Row(Modifier.fillMaxWidth()) {
                wx.days.drop(1).forEach { d ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val day = runCatching { java.time.LocalDate.parse(d.dateIso).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) }.getOrDefault("")
                        T(day, 16, color = C.InkMuted)
                        Icon(weatherIcon(d.code, true), null, tint = C.InkSecondary, modifier = Modifier.size(30.dp))
                        T("${d.maxC.fmt(0)}° / ${d.minC.fmt(0)}°", 16)
                    }
                }
            }
        }
    }
}

@Composable
private fun TripWidget(car: Car, m: Modifier) {
    DCard(m) {
        T("This trip", 24, FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BigStat("Distance", car.n("trip.distance").fmt(1), "km", Modifier.weight(1f))
            BigStat("Time", car.n("trip.time").fmt(0), "min", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LabelValue("Energy", car.n("trip.elec").fmt(1, " kWh"), Modifier.weight(1f), valueSize = 20, labelSize = 16)
            LabelValue("Fuel", car.n("trip.fuel").fmt(1, " L"), Modifier.weight(1f), valueSize = 20, labelSize = 16)
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier) {
        T(label, 18, color = C.InkMuted)
        Row(verticalAlignment = Alignment.Bottom) {
            T(value, 52, FontWeight.Light, lineHeight = 58)
            T(" $unit", 18, color = C.InkMuted, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun ClockWidget(m: Modifier, hidden: Set<String> = emptySet()) {
    val ctx = LocalContext.current
    val now = rememberNow()
    val time = android.text.format.DateFormat.getTimeFormat(ctx).format(Date(now))
    DCard(m) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(time, 96, FontWeight.Light, lineHeight = 104)
                if ("date" !in hidden) T(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now)), 22, color = C.InkMuted)
            }
        }
    }
}

@Composable
private fun OffRoadWidget(car: Car, m: Modifier) {
    val c = LocalContext.current.container
    val motion by c.motion.motion.collectAsState()
    DCard(m) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Off-road", 24, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            T(car.label(com.sharkhome.launcher.car.VehicleRepository.ROAD_MODE) ?: "—", 20, FontWeight.Medium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            fun warn(a: Float?) = a != null && kotlin.math.abs(a) >= 25f
            Column(Modifier.weight(1f)) {
                T("Pitch", 18, color = C.InkMuted)
                T(motion.pitch.fmt(0, "°"), 52, FontWeight.Light, if (warn(motion.pitch)) C.Warn else C.Ink, lineHeight = 58)
            }
            Column(Modifier.weight(1f)) {
                T("Roll", 18, color = C.InkMuted)
                T(motion.roll.fmt(0, "°"), 52, FontWeight.Light, if (warn(motion.roll)) C.Warn else C.Ink, lineHeight = 58)
            }
        }
        T("Hill descent ${if (car.on("drive.hillDescent")) "on" else "off"}", 18, color = C.InkSecondary)
    }
}

@Composable
private fun ChargingWidget(car: Car, m: Modifier) {
    val c = LocalContext.current.container
    val left by c.vehicle.chargeMinutesLeft.collectAsState()
    val plugged = (car.n("vehicle.chargingGun") ?: 0.0) > 0.0
    val chargeKw = car.n("vehicle.chargingPower")?.takeIf { plugged && it in 0.05..200.0 }
    DCard(m) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DsIcon(Ic.BatteryCharging, if (plugged) C.Ok else C.InkMuted, 28.dp)
            T(if (chargeKw != null) "Charging" else if (plugged) "Plugged in" else "Not plugged in", 24, FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            T(car.n("vehicle.batteryPct").fmt(0), 56, FontWeight.Light, lineHeight = 62)
            T(" %", 20, color = C.InkMuted, modifier = Modifier.padding(bottom = 8.dp).weight(1f))
            if (chargeKw != null) T("+${chargeKw.fmt(1)} kW", 24, FontWeight.Medium, C.Ok)
        }
        ThinBar(car.n("vehicle.batteryPct")?.let { (it / 100).toFloat() }, color = if (plugged) C.Ok else C.Ink, height = 6.dp)
        if (plugged) {
            left?.let { T("Full in ${if (it >= 60) "${it / 60} h ${it % 60} m" else "$it min"}", 18, color = C.InkSecondary) }
        }
    }
}

@Composable
private fun TyresWidget(car: Car, m: Modifier, hidden: Set<String> = emptySet()) {
    val temps = "temps" !in hidden
    fun tyre(label: String, k: String) =
        car.n("tyre.$k").fmt(0, " psi") + (if (temps) car.n("tyre.temp${k.replaceFirstChar { it.uppercase() }}")?.let { " · ${it.fmt(0)}°" } ?: "" else "")
    DCard(m) {
        T("Tyres", 24, FontWeight.SemiBold)
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LabelValue("Front left", tyre("Front left", "fl"), valueSize = 24, labelSize = 16)
                LabelValue("Rear left", tyre("Rear left", "rl"), valueSize = 24, labelSize = 16)
            }
            CarImage(Modifier.weight(0.8f).heightIn(max = 180.dp), CarView.TOP)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LabelValue("Front right", tyre("Front right", "fr"), valueSize = 24, labelSize = 16)
                LabelValue("Rear right", tyre("Rear right", "rr"), valueSize = 24, labelSize = 16)
            }
        }
    }
}

@Composable
private fun TowWidget(car: Car, m: Modifier, onOpen: () -> Unit) {
    val trailerOn = car.on("trailer.mode")
    val size = car.label("trailer.size") ?: "Small"
    val lightCheck = car.on("trailer.lightCheck")
    DCard(m, spacing = 14.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("Tow & Trailer", 24, FontWeight.SemiBold, modifier = Modifier.weight(1f).clickable(onClick = onOpen))
            DButton(
                text = if (trailerOn) "Trailer ON" else "Trailer OFF",
                onClick = { car.toggle("trailer.mode") },
                selected = trailerOn,
                height = 44.dp,
            )
        }
        val sizes = listOf("Small", "Medium", "Large")
        com.sharkhome.launcher.ui.components.LineSegmented(
            options = sizes,
            selected = sizes.indexOf(size).takeIf { it >= 0 },
            onSelect = { idx ->
                if (!trailerOn) car.setLabel("trailer.mode", "on")
                car.setLabel("trailer.size", sizes[idx])
            },
            height = 48.dp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DButton(
                text = if (lightCheck) "Stop Light Check" else "Light Check",
                onClick = { car.toggle("trailer.lightCheck") },
                selected = lightCheck,
                modifier = Modifier.weight(1f),
                height = 48.dp,
                textSize = 16,
            )
            val sport = car.label("chassis.brakeFeel") == "Sport"
            DButton(
                text = if (sport) "Sport Tow · ON" else "Sport Handling",
                onClick = {
                    val target = if (sport) "Comfort" else "Sport"
                    car.setLabel("chassis.brakeFeel", target)
                    car.setLabel("chassis.steerFeel", target)
                },
                selected = sport,
                modifier = Modifier.weight(1f),
                height = 48.dp,
                textSize = 16,
            )
        }
    }
}

/**
 * Fuel: litres left from the gauge % and the tank size set in Status & Fuel (default 60 L), plus the car's own fuel
 * range and economy. (An earlier version assumed a hidden 6 L reserve below 0%; nothing on the car backs that up, so
 * it's gone: the car's range is the one to trust.)
 */
@Composable
private fun FuelWidget(car: Car, m: Modifier, onOpen: () -> Unit) {
    val tank = LocalContext.current.container.prefs.fuelTankL
    val fuelPct = car.n("vehicle.fuelPct")
    val litres = fuelPct?.let { it / 100.0 * tank }
    DCard(m, onClick = onOpen, spacing = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("Fuel", 24, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            T("${tank.fmt(0)} L tank", 15, color = C.InkMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigStat("Left (approx.)", litres.fmt(1), "L", Modifier.weight(1f))
            BigStat("Fuel level", fuelPct.fmt(0), "%", Modifier.weight(1f))
        }
        ThinBar(fuelPct?.let { (it / 100).toFloat() }, color = if ((fuelPct ?: 100.0) < 15) C.Warn else C.Ink, height = 6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LabelValue("Avg (last 50 km)", car.n("vehicle.fuelAvg").fmt(1, " L/100 km"), Modifier.weight(1f), valueSize = 18, labelSize = 14)
            LabelValue("This trip", car.n("trip.fuel").fmt(1, " L"), Modifier.weight(1f), valueSize = 18, labelSize = 14)
        }
    }
}

@Composable
private fun Battery12vWidget(car: Car, m: Modifier, onOpen: () -> Unit) {
    DCard(m, onClick = onOpen, spacing = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("12V & Energy Split", 24, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            T("Health ${car.n("vehicle.batteryHealth").fmt(0, "%")}", 15, color = C.InkMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigStat("12V Aux", car.n("battery.voltage").fmt(1), "V", Modifier.weight(1f))
            BigStat("SOC Hold", car.n("energy.socTarget").fmt(0), "%", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LabelValue("50km Drive", car.n("energy.drivePct50km").fmt(0, "%"), Modifier.weight(1f), valueSize = 18, labelSize = 14)
            LabelValue("50km A/C", car.n("energy.acPct50km").fmt(0, "%"), Modifier.weight(1f), valueSize = 18, labelSize = 14)
            LabelValue("50km Aux", car.n("energy.auxPct50km").fmt(0, "%"), Modifier.weight(1f), valueSize = 18, labelSize = 14)
        }
    }
}

@Composable
private fun ChassisWidget(car: Car, m: Modifier, onOpen: () -> Unit) {
    val accent = LocalAccent.current
    val accel = car.n("pedal.accelerator") ?: 0.0
    val brake = car.n("pedal.brake") ?: 0.0
    DCard(m, onClick = onOpen, spacing = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("Steering & Pedals", 24, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            T("Stability ${if (car.label("chassis.stability") == "off") "OFF" else "ON"}", 15, color = C.Ok)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            BigStat("Steering", car.n("chassis.steeringAngle").fmt(1), "°", Modifier.width(160.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    T("Throttle", 14, color = C.InkMuted, modifier = Modifier.weight(1f))
                    T("${accel.toInt()}%", 14, FontWeight.Medium)
                }
                ThinBar((accel / 100.0).toFloat().coerceIn(0f, 1f), color = accent, height = 6.dp)
                Row(Modifier.fillMaxWidth()) {
                    T("Brake", 14, color = C.InkMuted, modifier = Modifier.weight(1f))
                    T("${brake.toInt()}%", 14, FontWeight.Medium)
                }
                ThinBar((brake / 100.0).toFloat().coerceIn(0f, 1f), color = C.Alert, height = 6.dp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LabelValue("Brake Feel", car.label("chassis.brakeFeel") ?: "Comfort", Modifier.weight(1f), valueSize = 18, labelSize = 14)
            LabelValue("Steering", car.label("chassis.steerFeel") ?: "Comfort", Modifier.weight(1f), valueSize = 18, labelSize = 14)
            LabelValue("Regen", car.label("drive.regenLevel") ?: "Standard", Modifier.weight(1f), valueSize = 18, labelSize = 14)
        }
    }
}

@Composable
private fun MotorsWidget(car: Car, m: Modifier, onOpen: () -> Unit) {
    DCard(m, onClick = onOpen, spacing = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("Motors & Engine", 24, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            T(car.n("engine.rpm").fmt(0, " rpm"), 16, FontWeight.Medium, color = C.InkSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigStat("Front Motor", car.n("motor.frontTorque").fmt(0), "Nm", Modifier.weight(1f))
            BigStat("Rear Motor", car.n("motor.rearTorque").fmt(0), "Nm", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabelValue("Front Bus", car.n("motor.frontVolt").fmt(0, " V"), Modifier.weight(1f), valueSize = 18, labelSize = 14)
            LabelValue("Rear Bus", car.n("motor.rearVolt").fmt(0, " V"), Modifier.weight(1f), valueSize = 18, labelSize = 14)
            LabelValue("1.5T Engine", car.n("engine.rpm").fmt(0, " rpm"), Modifier.weight(1f), valueSize = 18, labelSize = 14)
        }
    }
}

/**
 * One-tap car toggles. Only the ones this car reports are shown, minus any switched off in ⚙ Options; the tiles flow
 * into as many rows as the widget's size allows and stretch to fill it.
 */
@Composable
private fun QuickControlsWidget(car: Car, spec: WidgetSpec, m: Modifier, onOpen: () -> Unit, preview: Boolean = false) {
    val availableTiles = QUICK_TILES.filter { spec.shows(it.id) && car.available(it.key) }
    val tiles = if (preview && availableTiles.isEmpty()) QUICK_TILES.take(4) else availableTiles
    DCard(m, spacing = 12.dp, padding = 20.dp) {
        if (spec.h >= 2) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("Quick Controls", 22, FontWeight.SemiBold, modifier = Modifier.weight(1f).clickable(onClick = onOpen))
            T("All settings ▸", 15, color = C.Link, modifier = Modifier.clickable(onClick = onOpen))
        }
        if (tiles.isEmpty()) {
            T("Nothing to show: pick toggles in ⚙ Options.", 16, color = C.InkMuted, maxLines = 2)
            return@DCard
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Roughly square-ish tiles: as many per row as fit at ~150 px wide, then enough rows for the rest.
            val perRow = (maxWidth / 150.dp).toInt().coerceIn(1, tiles.size)
            val rows = tiles.chunked(perRow)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row ->
                    com.sharkhome.launcher.ui.components.TileRow(
                        row.map { t -> com.sharkhome.launcher.ui.components.TileSpec(t.label, t.icon, t.isOn(car)) { t.toggle(car) } } +
                            List(perRow - row.size) { com.sharkhome.launcher.ui.components.TileSpec("", Ic.Plus, false, enabled = false) {} },
                        Modifier.weight(1f).fillMaxWidth(),
                        height = Dp.Unspecified,
                    )
                }
            }
        }
    }
}
