package com.sharkhome.launcher.ui.components

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sharkhome.launcher.container
import com.sharkhome.launcher.media.MediaListenerService
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent
import com.sharkhome.launcher.ui.screens.QUICK_TILES
import com.sharkhome.launcher.ui.screens.QuickTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Car switches shown in the panel besides the Quick Controls ones (every one has a verified write in shark6_codes.json). */
private val EXTRA_TILES = listOf(
    QuickTile("lane", "Lane Assist", Ic.Shield, "advanced.laneAssist", { it.on("advanced.laneAssist") }, { it.toggle("advanced.laneAssist") }),
    QuickTile("tsr", "Traffic Signs", Ic.About, "advanced.trafficSign", { it.on("advanced.trafficSign") }, { it.toggle("advanced.trafficSign") }),
    QuickTile("slw", "Speed Warning", Ic.Gauges, "advanced.speedLimitWarning", { it.on("advanced.speedLimitWarning") }, { it.toggle("advanced.speedLimitWarning") }),
    QuickTile("dms", "Driver Monitor", Ic.Profile, "advanced.driverMonitoring", { it.on("advanced.driverMonitoring") }, { it.toggle("advanced.driverMonitoring") }),
    QuickTile("cpd", "Child Presence", Ic.Profile, "advanced.childPresence", { it.on("advanced.childPresence") }, { it.toggle("advanced.childPresence") }),
    QuickTile("hdc", "Hill Descent", Ic.HillDescent, "drive.hillDescent", { it.on("drive.hillDescent") }, { it.toggle("drive.hillDescent") }),
)

/** Opens BYD's own settings on its Connection tab, which holds Wi-Fi, Bluetooth and Cellular Data. */
private fun openBydConnection(ctx: Context) {
    val i = Intent().setComponent(android.content.ComponentName("com.byd.carsettings", "com.byd.carsettings.MainActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val ok = runCatching { ctx.startActivity(i) }.isSuccess
    if (!ok) ctx.container.apps.launchPackage("com.byd.carsettings", "BYD settings")
}

private fun openSettings(ctx: Context, vararg actions: String) {
    for (a in actions) {
        val ok = runCatching { ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (ok) return
    }
    android.widget.Toast.makeText(ctx, "Settings screen unavailable", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun PanelTile(icon: Ic, label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val fg = if (on) Color.White else C.Ink
    Column(
        modifier.height(104.dp).clip(Radius4)
            .background(if (on) accent else C.SurfaceCard)
            .border(1.dp, if (on) accent else C.Line, Radius4)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        DsIcon(icon, if (on) fg else C.InkSecondary, 30.dp)
        T(label, 17, FontWeight.SemiBold, fg, maxLines = 1)
    }
}

/**
 * The pull-down panel opened from the status bar: system shortcuts, every car switch BYD puts in its own quick
 * settings that this car answers for, and the notifications with tap-to-open and swipe-free dismiss.
 */
@Composable
fun NotificationPanel(open: Boolean, car: Car, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        AnimatedVisibility(open, enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
            // Poll only while the panel is open.
            val st by produceState(SysStatus(), ctx) {
                while (true) {
                    value = withContext(Dispatchers.Default) { readSysStatus(ctx.applicationContext) }
                    delay(1_500)
                }
            }
            val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
            val muted by produceState(false, audio) {
                while (true) {
                    value = runCatching { audio.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
                    delay(1_000)
                }
            }
            var refresh by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            val notes by produceState<List<MediaListenerService.Companion.Item>?>(null, refresh) {
                while (true) {
                    value = withContext(Dispatchers.Default) { MediaListenerService.items(ctx.applicationContext) }
                    delay(2_000)
                }
            }
            val carTiles = (QUICK_TILES + EXTRA_TILES).filter { car.available(it.key) }

            Column(
                Modifier.fillMaxWidth().background(C.Surface)
                    .border(1.dp, C.Line)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("Quick panel", 26, FontWeight.Medium, modifier = Modifier.weight(1f))
                    DButton("Close", onClose, height = 48.dp, textSize = 16)
                }

                // System shortcuts
                val tilesPerRow = 6
                val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                    android.content.res.Configuration.ORIENTATION_PORTRAIT
                val system = listOf<Triple<String, Ic, Pair<Boolean, () -> Unit>>>(
                    Triple("Wi-Fi", Ic.Wifi, st.wifi to { openBydConnection(ctx) }),
                    Triple("Bluetooth", Ic.Bluetooth, st.btOn to { openBydConnection(ctx) }),
                    Triple("Dark mode", Ic.Display, C.P.dark to { c.prefs.themeMode = if (C.P.dark) "light" else "dark" }),
                    Triple("Mute", Ic.Volume, muted to {
                        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0) }
                    }),
                    // Full screen hides BYD's dock, whose first button is the only other way to turn the screen (GitHub #5).
                    Triple("Rotate screen", Ic.ScreenRotate, portrait to {
                        car.setLabel("display.screenRotation", if (portrait) "Landscape" else "Portrait")
                    }),
                    Triple("Mobile data", Ic.Cast, st.sim to { openBydConnection(ctx) }),
                    Triple("Settings", Ic.Gear, false to { openSettings(ctx, Settings.ACTION_SETTINGS) }),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    system.forEach { (label, icon, st2) -> PanelTile(icon, label, st2.first, Modifier.weight(1f), st2.second) }
                }

                // Car switches (BYD quick settings)
                if (carTiles.isNotEmpty()) {
                    T("Vehicle controls", 18, FontWeight.Medium, C.InkSecondary)
                    carTiles.chunked(tilesPerRow).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { t -> PanelTile(t.icon, t.label, t.isOn(car), Modifier.weight(1f)) { t.toggle(car) } }
                            repeat(tilesPerRow - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }

                // Notifications
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(
                        when {
                            notes == null -> "Notifications (needs notification access)"
                            notes!!.isEmpty() -> "No notifications"
                            else -> "Notifications (${notes!!.size})"
                        },
                        18, FontWeight.Medium, C.InkSecondary, modifier = Modifier.weight(1f),
                    )
                    if (!notes.isNullOrEmpty()) DButton("Clear all", { MediaListenerService.dismissAll(); refresh++ }, height = 44.dp, textSize = 15)
                }
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    notes.orEmpty().forEach { n ->
                        Row(
                            Modifier.fillMaxWidth().clip(Radius4).background(C.SurfaceCard).border(1.dp, C.Line, Radius4)
                                .clickable {
                                    runCatching { n.open?.send() }
                                    MediaListenerService.dismiss(n.key)
                                    onClose()
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                T(n.app, 14, color = C.InkMuted, maxLines = 1)
                                T(listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(" · "), 18, FontWeight.Medium, maxLines = 2)
                            }
                            Box(
                                Modifier.clip(Radius4).clickable { MediaListenerService.dismiss(n.key); refresh++ }.padding(horizontal = 14.dp, vertical = 8.dp),
                            ) { T("Dismiss", 15, FontWeight.Medium, C.InkSecondary) }
                        }
                    }
                }
            }
        }
    }
}
