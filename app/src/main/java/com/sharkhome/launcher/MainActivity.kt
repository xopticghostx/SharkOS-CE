package com.sharkhome.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.DesignCanvas
import com.sharkhome.launcher.ui.SharkTheme
import com.sharkhome.launcher.ui.components.NavRail
import com.sharkhome.launcher.ui.components.SideDrawer
import com.sharkhome.launcher.ui.components.prefsTick
import com.sharkhome.launcher.ui.components.rememberCar
import com.sharkhome.launcher.ui.components.rememberClock
import com.sharkhome.launcher.ui.components.StatusHeader
import com.sharkhome.launcher.ui.screens.AppsScreen
import com.sharkhome.launcher.ui.screens.ClimateScreen
import com.sharkhome.launcher.ui.screens.DashboardScreen
import com.sharkhome.launcher.ui.screens.ExplorerScreen
import com.sharkhome.launcher.ui.screens.OffRoadScreen
import com.sharkhome.launcher.ui.screens.QuickSettingsScreen
import com.sharkhome.launcher.ui.screens.CarScreen
import com.sharkhome.launcher.ui.screens.ControlsScreen
import com.sharkhome.launcher.ui.screens.DashCamScreen
import com.sharkhome.launcher.ui.screens.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class Screen { DASHBOARD, CLIMATE, APPS, VEHICLE, DASHCAM, OFFROAD, GAUGES, ADVANCED_SETTINGS, QUICK_SETTINGS, EXPLORER, INSTALL, UTILITIES, SETTINGS }

/**
 * Every dangerous permission the app declares in its manifest that isn't granted yet. On this head unit the
 * standard Settings permission pages are stubbed out, but the runtime "Allow" dialog (com.android.permissioncontroller)
 * works, so requesting these is the one grant path that needs neither adb nor a PC — the point of the USB install for
 * owners who can't enable adb. "normal" permissions (all the BYDAUTO_* car signals) are granted at install and skipped;
 * pure "signature" ones can't be granted this way and are skipped too.
 */
private fun android.content.Context.ungrantedDangerousPermissions(): Array<String> {
    val pm = packageManager
    val requested = runCatching {
        pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions
    }.getOrNull() ?: return emptyArray()
    return requested.filter { name ->
        val info = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull() ?: return@filter false
        info.protection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS &&
            checkSelfPermission(name) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }.toTypedArray()
}

class MainActivity : ComponentActivity() {

    /** Bumped when the Home button is pressed while we're already in front. */
    private val homePresses = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        val c = container
        c.vehicle.uiVisible = true // before start(), so the first seconds poll at full speed
        c.vehicle.start()
        c.audio.initEq()
        c.media.start()
        setContent { App(homePresses, ::applyImmersiveMode) }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        val c = container
        c.media.start()
        c.audio.refresh()
        c.motion.start()
        lifecycleScope.launch { c.apps.refresh() }
    }

    override fun onStart() {
        super.onStart()
        val c = container
        c.vehicle.uiVisible = true
        c.media.uiVisible = true
    }

    /** Not visible any more (another app is in front): stop GPS/sensors and the fast polling until we're back. */
    override fun onStop() {
        val c = container
        c.vehicle.uiVisible = false
        c.media.uiVisible = false
        c.motion.stop()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    fun applyImmersiveMode(fullScreen: Boolean = container.prefs.fullScreenMode) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (!fullScreen) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            return
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homePresses.value = homePresses.value + 1
    }
}

@Composable
private fun App(homePresses: MutableStateFlow<Int>, onFullScreenChanged: (Boolean) -> Unit) {
    val c = LocalContext.current.container
    val tick = prefsTick()
    val defaultScreen = Screen.DASHBOARD
    var screen by rememberSaveable { mutableStateOf(defaultScreen) }
    var drawerOpen by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    var controlsTab by rememberSaveable { mutableStateOf(0) }
    var driveTab by rememberSaveable { mutableStateOf(0) }
    var settingsTab by rememberSaveable { mutableStateOf(0) }
    val home by homePresses.collectAsState()

    LaunchedEffect(c.prefs.fullScreenMode) {
        onFullScreenChanged(c.prefs.fullScreenMode)
    }

    LaunchedEffect(home) {
        if (home > 0) {
            drawerOpen = false; panelOpen = false
            com.sharkhome.launcher.ui.screens.HomeEdit.editing.value = false
            screen = defaultScreen
        }
    }
    BackHandler(enabled = screen != defaultScreen || drawerOpen || panelOpen) {
        if (panelOpen) panelOpen = false else if (drawerOpen) drawerOpen = false else screen = defaultScreen
    }

    val ctx = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { c.motion.start() }
    LaunchedEffect(Unit) {
        // One Allow-dialog round grants everything a USB install can get without adb or a PC: the BYDAUTO_* car
        // permissions are "normal" and already granted at install, so only the dangerous ones (location, phone
        // state, car energy…) are left, and those are exactly what this standard request shows a dialog for.
        val need = ctx.ungrantedDangerousPermissions()
        if (need.isNotEmpty()) permissions.launch(need)
    }

    val car = rememberCar()
    val uiNight = androidx.compose.ui.platform.LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
    val hour = remember(rememberClock() / 600_000) { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val dark = when (c.prefs.themeMode) {
        "dark", "shark" -> true
        "light" -> false
        // Auto: the car's day/night mode when it answers 1 or 2 (it reads -1 on this firmware), else Android's own UI mode
        // (which BYD's system sets), else the time of day.
        else -> car.label("display.dayNight")?.let { it == "night" } ?: when (uiNight) {
            android.content.res.Configuration.UI_MODE_NIGHT_YES -> true
            android.content.res.Configuration.UI_MODE_NIGHT_NO -> false
            else -> hour >= 19 || hour < 6
        }
    }

    val go: (Screen) -> Unit = { target ->
        when (target) {
            Screen.OFFROAD -> { driveTab = 0; screen = Screen.OFFROAD }
            Screen.GAUGES -> { driveTab = 2; screen = Screen.GAUGES }
            Screen.QUICK_SETTINGS -> { controlsTab = 0; screen = Screen.QUICK_SETTINGS }
            Screen.ADVANCED_SETTINGS -> { controlsTab = 1; screen = Screen.QUICK_SETTINGS }
            Screen.SETTINGS -> { settingsTab = 0; screen = Screen.SETTINGS }
            Screen.UTILITIES -> { settingsTab = 1; screen = Screen.SETTINGS }
            Screen.INSTALL -> { settingsTab = 2; screen = Screen.SETTINGS }
            else -> screen = target
        }
    }

    SharkTheme(accent = Color(c.prefs.accent), dark = dark, shark = c.prefs.themeMode == "shark") {
        DesignCanvas {
            Column(Modifier.fillMaxSize().background(C.Surface)) {
                // BYD's own status bar is visible when not full screen, so ours would be a duplicate.
                if (c.prefs.fullScreenMode) StatusHeader(car, onOpenPanel = { panelOpen = true })
                val pos = c.prefs.navPosition
                val vertical = pos == "left" || pos == "right"
                // The left rail doubles as the page's left margin; every other position needs it added.
                val eff = if (c.prefs.sidebarHidden && !vertical) "left" else pos
                val content: @Composable (Modifier) -> Unit = { m ->
                    Box(m.padding(start = if (eff != "left") 24.dp else 0.dp)) {
                        when (screen) {
                            Screen.DASHBOARD -> DashboardScreen(go)
                            Screen.CLIMATE -> ClimateScreen(go)
                            Screen.QUICK_SETTINGS, Screen.ADVANCED_SETTINGS -> ControlsScreen(go, controlsTab)
                            Screen.APPS -> AppsScreen()
                            Screen.VEHICLE -> CarScreen()
                            Screen.DASHCAM -> DashCamScreen()
                            Screen.SETTINGS, Screen.UTILITIES, Screen.INSTALL -> SettingsScreen(go, settingsTab)
                            Screen.OFFROAD, Screen.GAUGES -> OffRoadScreen(initialTab = driveTab)
                            Screen.EXPLORER -> ExplorerScreen()
                        }
                    }
                }
                val bar: @Composable () -> Unit = {
                    if (c.prefs.sidebarHidden) {
                        if (vertical) com.sharkhome.launcher.ui.components.EdgeTab(onOpen = { drawerOpen = true }, right = pos == "right")
                        else com.sharkhome.launcher.ui.components.EdgeTab(onOpen = { drawerOpen = true })
                    } else {
                        NavRail(current = screen, onGo = go, onOpenDrawer = { drawerOpen = true }, position = pos)
                    }
                }
                // Hidden bar on top or bottom: just the pull tab on the left edge.
                when (eff) {
                    "top" -> Column(Modifier.weight(1f).fillMaxWidth()) { bar(); content(Modifier.weight(1f).fillMaxWidth()) }
                    "bottom" -> Column(Modifier.weight(1f).fillMaxWidth()) { content(Modifier.weight(1f).fillMaxWidth()); bar() }
                    "right" -> Row(Modifier.weight(1f).fillMaxWidth()) { content(Modifier.weight(1f).fillMaxHeight()); bar() }
                    else -> Row(Modifier.weight(1f).fillMaxWidth()) { bar(); content(Modifier.weight(1f).fillMaxHeight()) }
                }
            }
            com.sharkhome.launcher.ui.components.NotificationPanel(panelOpen && c.prefs.fullScreenMode, car) { panelOpen = false }
            com.sharkhome.launcher.ui.components.SupportPrompt()
            SideDrawer(
                open = drawerOpen,
                current = screen,
                onClose = { drawerOpen = false },
                onGo = { go(it); drawerOpen = false },
                onControlsTab = { controlsTab = it; screen = Screen.QUICK_SETTINGS; drawerOpen = false },
                onDriveTab = { driveTab = it; screen = if (it == 2) Screen.GAUGES else Screen.OFFROAD; drawerOpen = false },
                onSettingsTab = { settingsTab = it; screen = Screen.SETTINGS; drawerOpen = false },
                fromRight = c.prefs.navPosition == "right",
            )
        }
    }
}
