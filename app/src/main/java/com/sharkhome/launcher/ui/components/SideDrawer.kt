package com.sharkhome.launcher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sharkhome.launcher.Screen
import com.sharkhome.launcher.container
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent

private val TabShape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
private val TabShapeStart = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)

/** Which seat is on the left / right of the screen for the chosen driving side. */
@Composable
fun seatSides(): Pair<String, String> {
    prefsTick()
    val right = LocalContext.current.container.prefs.driverOnRight
    return if (right) "Passenger" to "Driver" else "Driver" to "Passenger"
}

/**
 * The Tesla-style pull tab on the left edge when the permanent sidebar is hidden.
 * Swipe right anywhere along the edge or tap the top tab to open the menu, or tap the bottom pin button to restore the sidebar.
 */
@Composable
fun EdgeTab(onOpen: () -> Unit, modifier: Modifier = Modifier, right: Boolean = false) {
    val c = LocalContext.current.container
    val open by rememberUpdatedState(onOpen)
    var drag by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.width(42.dp).fillMaxHeight().pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = { if ((!right && drag > 36f) || (right && drag < -36f)) open(); drag = 0f },
                onDragCancel = { drag = 0f },
            ) { _, dx -> drag += dx }
        },
        contentAlignment = if (right) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.width(28.dp).height(112.dp).clip(if (right) TabShapeStart else TabShape).background(C.SurfaceRaised)
                    .border(1.dp, C.Line, if (right) TabShapeStart else TabShape).clickable { open() },
                contentAlignment = Alignment.Center,
            ) {
                DsIcon(if (right) Ic.ChevronLeft else Ic.ChevronRight, C.InkSecondary, 20.dp)
            }
            Box(
                Modifier.width(28.dp).height(44.dp).clip(TabShape).background(C.SurfacePanel)
                    .border(1.dp, C.Line, TabShape).clickable { c.prefs.sidebarHidden = false },
                contentAlignment = Alignment.Center,
            ) {
                DsIcon(Ic.SidebarHide, LocalAccent.current, 16.dp)
            }
            if (c.prefs.fullScreenMode) {
                val sysNav = rememberSystemNav()
                for ((icon, desc, action) in listOf(Triple(Ic.Back, "Back", sysNav.back), Triple(Ic.Home, sysNav.homeLabel, sysNav.home))) {
                    Box(
                        Modifier.width(28.dp).height(56.dp).clip(TabShape).background(C.SurfacePanel)
                            .border(1.dp, C.Line, TabShape).clickable(onClickLabel = desc, onClick = action),
                        contentAlignment = Alignment.Center,
                    ) {
                        DsIcon(icon, C.InkSecondary, 18.dp)
                    }
                }
            }
        }
    }
}

/** What BYD's dock Back and Home buttons do, for when full screen hides the dock. */
class SystemNav(val back: () -> Unit, val home: () -> Unit, val homeLabel: String)

@Composable
fun rememberSystemNav(): SystemNav {
    val ctx = LocalContext.current
    val dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val homeIntent = remember {
        android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // When another launcher (BYD's) is the default Home app, say so: this button leaves SharkOS-CE.
    val oursIsDefault = remember {
        ctx.packageManager.resolveActivity(homeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == ctx.packageName
    }
    return SystemNav(
        // Same as the system Back key inside the app: closes a dialog or the drawer, or returns to the home screen.
        // On the home screen there is nothing to go back to, so it does nothing rather than closing the launcher.
        back = { if (dispatcher?.hasEnabledCallbacks() == true) dispatcher.onBackPressed() },
        home = { runCatching { ctx.startActivity(homeIntent) } },
        homeLabel = if (oursIsDefault) "Home" else "BYD Home",
    )
}

/** One entry on the navigation bar. */
private class NavEntry(val icon: Ic, val label: String, val selected: Boolean, val badge: Boolean = false, val onClick: () -> Unit)

/**
 * The one navigation bar. [position] is "left", "right", "top" or "bottom" (Display & themes > Menu position).
 * Left/right: a vertical icon rail with a pull tab on the side facing the content. Top/bottom: the same icons in a row,
 * with a "More" button at the end. Either way the pull tab / More button opens the detail drawer.
 */
@Composable
fun NavRail(
    current: Screen,
    onGo: (Screen) -> Unit,
    onOpenDrawer: () -> Unit,
    position: String = "left",
    modifier: Modifier = Modifier,
) {
    val c = LocalContext.current.container
    prefsTick()
    val editing by com.sharkhome.launcher.ui.screens.HomeEdit.editing.collectAsState()
    val hasUpdate by c.updater.hasUpdate.collectAsState()
    val openDrawer by rememberUpdatedState(onOpenDrawer)
    val accent = LocalAccent.current
    val sysNav = rememberSystemNav()
    val isHome = current == Screen.DASHBOARD
    val isSettings = current == Screen.SETTINGS || current == Screen.UTILITIES || current == Screen.INSTALL || current == Screen.EXPLORER
    fun go(target: Screen) {
        com.sharkhome.launcher.ui.screens.HomeEdit.editing.value = false
        onGo(target)
    }
    val main = listOf(
        NavEntry(Ic.Home, "Home", isHome && !editing) { go(Screen.DASHBOARD) },
        NavEntry(Ic.Fan, "Climate", current == Screen.CLIMATE) { go(Screen.CLIMATE) },
        NavEntry(Ic.Sliders, "Controls", current == Screen.QUICK_SETTINGS || current == Screen.ADVANCED_SETTINGS) { go(Screen.QUICK_SETTINGS) },
        NavEntry(Ic.OffRoad, "Drive", current == Screen.OFFROAD || current == Screen.GAUGES) { go(Screen.OFFROAD) },
        NavEntry(Ic.Apps, "Apps", current == Screen.APPS) { go(Screen.APPS) },
        NavEntry(Ic.Vehicle, "Car", current == Screen.VEHICLE) { go(Screen.VEHICLE) },
        NavEntry(Ic.DashCam, "DashCam", current == Screen.DASHCAM) { go(Screen.DASHCAM) },
    )
    // Full screen hides BYD's dock, so its Back and Home buttons live here instead.
    val system = if (c.prefs.fullScreenMode) listOf(
        NavEntry(Ic.Back, "Back", false) { sysNav.back() },
        NavEntry(Ic.Home, sysNav.homeLabel, false) { sysNav.home() },
    ) else emptyList()
    val tools = listOf(
        NavEntry(Ic.Gear, "Settings", isSettings, badge = hasUpdate) { go(Screen.SETTINGS) },
        NavEntry(Ic.Sparkle, if (editing) "Done" else "Layout", editing) {
            if (!isHome) onGo(Screen.DASHBOARD)
            com.sharkhome.launcher.ui.screens.HomeEdit.editing.value = !editing
        },
        NavEntry(Ic.FullScreen, "Full", c.prefs.fullScreenMode) { c.prefs.fullScreenMode = !c.prefs.fullScreenMode },
        NavEntry(Ic.SidebarHide, "Hide", false) { c.prefs.sidebarHidden = true },
    )

    if (position == "top" || position == "bottom") {
        Row(
            modifier.fillMaxWidth().height(72.dp)
                .padding(start = 24.dp, end = 24.dp, top = if (position == "bottom") 8.dp else 0.dp, bottom = if (position == "top") 8.dp else 12.dp)
                .clip(Radius4).background(C.SurfacePanel).border(1.dp, C.Line, Radius4)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            main.forEach { RailItem(it, accent, horizontal = true, modifier = Modifier.width(96.dp).fillMaxHeight()) }
            Box(Modifier.weight(1f))
            (system + tools).forEach { RailItem(it, accent, horizontal = true, modifier = Modifier.width(80.dp).fillMaxHeight()) }
            RailItem(NavEntry(Ic.ChevronRight, "More", false) { openDrawer() }, accent, horizontal = true, modifier = Modifier.width(80.dp).fillMaxHeight())
        }
        return
    }

    val right = position == "right"
    var drag by remember { mutableFloatStateOf(0f) }
    val tab: @Composable () -> Unit = {
        Box(
            Modifier.width(22.dp).height(120.dp)
                .clip(if (right) TabShapeStart else TabShape)
                .background(C.SurfaceRaised)
                .border(1.dp, C.Line, if (right) TabShapeStart else TabShape)
                .clickable { openDrawer() },
            contentAlignment = Alignment.Center,
        ) { DsIcon(if (right) Ic.ChevronLeft else Ic.ChevronRight, C.InkSecondary, 18.dp) }
    }
    Row(
        modifier.fillMaxHeight()
            .padding(start = if (right) 8.dp else 12.dp, end = if (right) 12.dp else 8.dp, bottom = 24.dp)
            .pointerInput(right) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if ((!right && drag > 36f) || (right && drag < -36f)) openDrawer(); drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, dx -> drag += dx }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (right) tab()
        Column(
            Modifier.width(78.dp).fillMaxHeight().clip(Radius4).background(C.SurfacePanel).border(1.dp, C.Line, Radius4)
                .padding(vertical = 8.dp, horizontal = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            main.forEach { RailItem(it, accent, horizontal = false, modifier = Modifier.fillMaxWidth().height(52.dp)) }
            Box(Modifier.weight(1f))
            Hairline(Modifier.padding(horizontal = 6.dp), color = C.Line)
            if (system.isNotEmpty()) {
                system.forEach { RailItem(it, accent, horizontal = false, modifier = Modifier.fillMaxWidth().height(50.dp)) }
                Hairline(Modifier.padding(horizontal = 6.dp), color = C.Line)
            }
            tools.forEach { RailItem(it, accent, horizontal = false, modifier = Modifier.fillMaxWidth().height(52.dp)) }
        }
        if (!right) tab()
    }
}

@Composable
private fun RailItem(entry: NavEntry, accent: Color, horizontal: Boolean, modifier: Modifier) {
    Box(
        modifier.clip(Radius4).background(if (entry.selected) C.SurfaceRaised else Color.Transparent).clickable(onClick = entry.onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (entry.selected) {
            Box(
                Modifier.align(if (horizontal) Alignment.BottomCenter else Alignment.CenterStart)
                    .then(if (horizontal) Modifier.width(28.dp).height(3.dp) else Modifier.width(3.dp).height(28.dp))
                    .clip(Radius4).background(accent),
            )
        }
        if (entry.badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 10.dp).size(7.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape).background(accent),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            DsIcon(entry.icon, if (entry.selected) accent else C.InkSecondary, 21.dp)
            T(entry.label, 14, if (entry.selected) FontWeight.SemiBold else FontWeight.Normal, if (entry.selected) C.Ink else C.InkSecondary)
        }
    }
}

/** The expanded slide-out menu (Option B details): Shark 6 telemetry header, hubs with subtitles + quick sub-tab chips. */
@Composable
fun SideDrawer(
    open: Boolean,
    current: Screen,
    onClose: () -> Unit,
    onGo: (Screen) -> Unit,
    onControlsTab: (Int) -> Unit,
    onDriveTab: (Int) -> Unit = {},
    onSettingsTab: (Int) -> Unit = {},
    fromRight: Boolean = false,
) {
    val close by rememberUpdatedState(onClose)
    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = if (fromRight) Alignment.CenterEnd else Alignment.CenterStart) {
        AnimatedVisibility(
            open,
            enter = slideInHorizontally { if (fromRight) it else -it },
            exit = slideOutHorizontally { if (fromRight) it else -it },
        ) {
            var drag by remember { mutableFloatStateOf(0f) }
            Row(
                Modifier.fillMaxHeight().pointerInput(fromRight) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = { if ((!fromRight && drag < -40f) || (fromRight && drag > 40f)) close(); drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { _, dx -> drag += dx }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val handle: @Composable () -> Unit = {
                    Box(
                        Modifier.width(30.dp).height(132.dp).clip(if (fromRight) TabShapeStart else TabShape).background(C.SurfacePanel)
                            .border(1.dp, C.Line, if (fromRight) TabShapeStart else TabShape).clickable { close() },
                        contentAlignment = Alignment.Center,
                    ) { DsIcon(if (fromRight) Ic.ChevronRight else Ic.ChevronLeft, C.InkSecondary, 20.dp) }
                }
                if (fromRight) handle()
                DrawerPanel(current, onClose, onGo, onControlsTab, onDriveTab, onSettingsTab)
                if (!fromRight) handle()
            }
        }
    }
}

@Composable
private fun DrawerPanel(
    current: Screen,
    onClose: () -> Unit,
    onGo: (Screen) -> Unit,
    onControlsTab: (Int) -> Unit,
    onDriveTab: (Int) -> Unit,
    onSettingsTab: (Int) -> Unit,
) {
    val car = rememberCar()
    val c = LocalContext.current.container
    prefsTick()
    val hasUpdate by c.updater.hasUpdate.collectAsState()
    val accent = LocalAccent.current
    Column(
        Modifier.width(468.dp).fillMaxHeight().background(C.SurfacePanel).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Vehicle summary header with extra detail when expanded
        Column(
            Modifier.fillMaxWidth().clip(Radius4).background(C.SurfaceCard).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CarImage(Modifier.width(150.dp).height(64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        T("BYD Shark 6", 19, FontWeight.SemiBold)
                        val g = car.label("vehicle.gear")
                        val state = when (g) { null, "P" -> "Parked"; else -> "Gear $g" }
                        T(if (car.label("body.locked") == "locked") "$state · Locked" else state, 13, color = C.InkMuted)
                    }
                    val range = car.totalKm()?.takeIf { it > 0 }
                    val v12 = car.n("battery.voltage")
                    val odo = car.n("vehicle.odometerKm")
                    T(
                        "Range ${range.fmt(0, " km")}  ·  12V ${v12.fmt(1, "V")}  ·  Odo ${odo.fmt(0, " km")}",
                        12,
                        color = C.InkSecondary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MiniEnergy("HV Battery", car.n("vehicle.batteryPct"), Modifier.weight(1f))
                MiniEnergy("Fuel", car.n("vehicle.fuelPct"), Modifier.weight(1f))
            }
        }

        Box(Modifier.height(2.dp))

        Item(
            icon = Ic.Home, label = "Home", subtitle = "Widget dashboard with climate, media and navigation",
            selected = current == Screen.DASHBOARD, accent = accent,
        ) { onGo(Screen.DASHBOARD) }

        Item(
            icon = Ic.Fan, label = "Climate", subtitle = "Dual-zone A/C, seat heat and vent, wheel heat",
            selected = current == Screen.CLIMATE, accent = accent,
        ) { onGo(Screen.CLIMATE) }

        Item(
            icon = Ic.Sliders, label = "Controls", subtitle = "Quick toggles, driving feel, startup and BYD settings",
            selected = current == Screen.QUICK_SETTINGS || current == Screen.ADVANCED_SETTINGS, accent = accent,
            chips = listOf("Quick controls" to { onControlsTab(0) }, "Startup controls" to { onControlsTab(1) }, "BYD settings" to { onControlsTab(2) }),
        ) { onControlsTab(0) }

        Item(
            icon = Ic.OffRoad, label = "Drive", subtitle = "Trail mode, towing and live gauges",
            selected = current == Screen.OFFROAD || current == Screen.GAUGES, accent = accent,
            chips = listOf("Trail mode" to { onDriveTab(0) }, "Towing" to { onDriveTab(1) }, "Gauges" to { onDriveTab(2) }),
        ) { onDriveTab(0) }

        Item(
            icon = Ic.Apps, label = "Apps", subtitle = "Your apps and the pinned dock",
            selected = current == Screen.APPS, accent = accent,
        ) { onGo(Screen.APPS) }

        Item(
            icon = Ic.Vehicle, label = "Car", subtitle = "Battery, fuel, range and tyre pressures",
            selected = current == Screen.VEHICLE, accent = accent,
        ) { onGo(Screen.VEHICLE) }

        Item(
            icon = Ic.DashCam, label = "DashCam", subtitle = "Trip loop recording, 4-camera viewer and saved clips",
            selected = current == Screen.DASHCAM, accent = accent,
        ) { onGo(Screen.DASHCAM) }

        Item(
            icon = Ic.Gear, label = "Settings", subtitle = "Set once: display, permissions, installs, startup, diagnostics",
            selected = current == Screen.SETTINGS || current == Screen.UTILITIES || current == Screen.INSTALL || current == Screen.EXPLORER,
            accent = accent,
            chips = listOf(
                "Display" to { onSettingsTab(0) },
                "Install" to { onSettingsTab(2) },
                "Startup" to { onSettingsTab(3) },
                (if (hasUpdate) "Updates •" else "Updates") to { onSettingsTab(5) },
            ),
        ) { onSettingsTab(0) }

        Box(Modifier.weight(1f))
        Hairline(color = C.Line)
        Box(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DButton(
                text = if (c.prefs.sidebarHidden) "Sidebar · Hidden" else "Hide Sidebar",
                onClick = {
                    c.prefs.sidebarHidden = !c.prefs.sidebarHidden
                    if (c.prefs.sidebarHidden) onClose()
                },
                modifier = Modifier.weight(1f),
                selected = c.prefs.sidebarHidden,
                height = 42.dp,
                textSize = 14,
            )
            DButton(
                text = if (c.prefs.fullScreenMode) "Full Screen · ON" else "Full Screen · OFF",
                onClick = { c.prefs.fullScreenMode = !c.prefs.fullScreenMode },
                modifier = Modifier.weight(1f),
                selected = c.prefs.fullScreenMode,
                height = 42.dp,
                textSize = 14,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DButton(
                text = "Edit Home Layout",
                onClick = {
                    com.sharkhome.launcher.ui.screens.HomeEdit.editing.value = true
                    onGo(Screen.DASHBOARD)
                },
                modifier = Modifier.weight(1f),
                style = BtnStyle.Lightest,
                height = 42.dp,
                textSize = 14,
            )
            DButton(
                text = "BYD App List ↗",
                onClick = { c.apps.openBydApps() },
                modifier = Modifier.weight(1f),
                style = BtnStyle.Lightest,
                height = 42.dp,
                textSize = 14,
            )
        }
    }
}

@Composable
private fun MiniEnergy(label: String, pct: Double?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(label, 14, color = C.InkMuted, modifier = Modifier.weight(1f))
            T(pct.fmt(0, "%"), 14, FontWeight.Medium)
        }
        ThinBar(pct?.let { (it / 100).toFloat() })
    }
}

@Composable
private fun Item(
    icon: Ic,
    label: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    chips: List<Pair<String, () -> Unit>> = emptyList(),
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius4)
            .background(if (selected) C.SurfaceRaised else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DsIcon(icon, if (selected) accent else C.InkSecondary, 24.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(label, 18, if (selected) FontWeight.SemiBold else FontWeight.Medium, if (selected) C.Ink else C.InkSecondary)
                T(subtitle, 14, color = C.InkMuted)
            }
            if (chips.size <= 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { (chipLabel, chipClick) ->
                        QuickChip(chipLabel, chipClick)
                    }
                }
            }
        }
        if (chips.size > 2) {
            Row(
                Modifier.fillMaxWidth().padding(start = 38.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chips.forEach { (chipLabel, chipClick) ->
                    QuickChip(chipLabel, chipClick, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(28.dp)
            .clip(Radius4)
            .background(C.P.btnLightest)
            .border(1.dp, C.Line, Radius4)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(label, 14, FontWeight.Medium, C.InkSecondary)
    }
}
