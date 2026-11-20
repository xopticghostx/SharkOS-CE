package com.sharkhome.launcher.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sharkhome.launcher.container
import com.sharkhome.launcher.system.AppPermissions
import com.sharkhome.launcher.system.InstalledApp
import com.sharkhome.launcher.system.LocalAdb
import com.sharkhome.launcher.system.LocalAdb.Companion.q
import com.sharkhome.launcher.system.RuntimePerm
import com.sharkhome.launcher.system.SpecialAccess
import com.sharkhome.launcher.system.UsbDebugging
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.components.BtnStyle
import com.sharkhome.launcher.ui.components.DButton
import com.sharkhome.launcher.ui.components.DCard
import com.sharkhome.launcher.ui.components.Eyebrow
import com.sharkhome.launcher.ui.components.Hairline
import com.sharkhome.launcher.ui.components.PillSwitch
import com.sharkhome.launcher.ui.components.Radius4
import com.sharkhome.launcher.ui.components.T
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- shared

/**
 * USB debugging 1-click switch. Uses Android's own setting (Settings.Global.ADB_ENABLED), which SharkOS-CE
 * can enable directly when WRITE_SECURE_SETTINGS is granted (or via LocalAdb / BYD ADB screen fallback).
 */
@Composable
private fun DebuggingRow() {
    val ctx = LocalContext.current
    val adb = ctx.container.adb
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var on by remember { mutableStateOf(UsbDebugging.isEnabled(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                on = UsbDebugging.isEnabled(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (on) C.Ok else C.Warn))
            T(
                note ?: if (on) "USB debugging is on" else "USB debugging is off — tap once to turn it on",
                16,
                color = C.InkSecondary,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            DButton(
                text = when {
                    busy -> "Enabling…"
                    on -> "USB Debugging · ON"
                    else -> "Turn on USB Debugging"
                },
                onClick = {
                    if (busy) return@DButton
                    scope.launch {
                        busy = true
                        val res = UsbDebugging.enable(ctx, adb)
                        on = res.enabled
                        note = res.message
                        Toast.makeText(ctx, res.message, Toast.LENGTH_SHORT).show()
                        busy = false
                    }
                },
                style = if (on) BtnStyle.Lightest else BtnStyle.Primary,
                selected = on,
            )
            DButton(
                text = "BYD ADB Screen",
                onClick = {
                    if (!UsbDebugging.openBydAdbScreen(ctx)) {
                        Toast.makeText(ctx, "BYD ADB screen unavailable", Toast.LENGTH_SHORT).show()
                    }
                },
                style = BtnStyle.Lightest,
            )
        }
        Hairline(Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val prefs = ctx.container.prefs
            var keep by remember { mutableStateOf(prefs.keepAdbOn) }
            Column(Modifier.weight(1f)) {
                T("Keep USB debugging on", 16)
                T("Switches it straight back on when BYD turns it off (its update app does this whenever it restarts, which drops scrcpy and PC adb)", 13, color = C.InkMuted, maxLines = 2)
            }
            PillSwitch(keep, { keep = it; prefs.keepAdbOn = it; if (it && !on) scope.launch { on = UsbDebugging.enable(ctx, adb).enabled } })
        }
    }
}

/** Connection status for the head unit's own adb, with a Connect button. Connects automatically on first show. */
@Composable
internal fun AdbBar() {
    DebuggingRow()
    val adb = LocalContext.current.container.adb
    val state by adb.state.collectAsState()
    val message by adb.message.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (state != LocalAdb.State.CONNECTED) adb.connect() }
    DCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val dot = when (state) {
                LocalAdb.State.CONNECTED -> C.Ok
                LocalAdb.State.FAILED -> C.Alert
                else -> C.Warn
            }
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
            T(message, 16, color = C.InkSecondary, maxLines = 2, modifier = Modifier.weight(1f))
            if (state != LocalAdb.State.CONNECTED) {
                DButton(if (state == LocalAdb.State.CONNECTING) "Connecting…" else "Connect", { scope.launch { adb.connect() } })
            }
        }
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    else -> "%.0f KB".format(bytes / 1e3)
}

@Composable
internal fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        T(text, 18, color = C.InkMuted, maxLines = 3)
    }
}

// ---------------------------------------------------------------- utilities (permissions)

/**
 * Gives other apps the permissions BYD's settings won't let you change: runtime permissions (location, microphone,
 * contacts...) with pm grant/revoke, and special access (display over apps, battery optimisation...) with appops.
 */
@Composable
fun UtilitiesScreen() {
    val ctx = LocalContext.current
    val c = ctx.container
    val adb = c.adb
    val state by adb.state.collectAsState()
    val perms = remember { AppPermissions(ctx, adb) }
    val scope = rememberCoroutineScope()
    val apps = remember { perms.installedApps() }
    var selected by remember { mutableStateOf(apps.firstOrNull { it.pkg == ctx.packageName } ?: apps.firstOrNull { it.pkg == "com.waze" } ?: apps.firstOrNull()) }
    var runtime by remember { mutableStateOf<List<RuntimePerm>>(emptyList()) }
    var special by remember { mutableStateOf<List<SpecialAccess>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var grantSelfBusy by remember { mutableStateOf(false) }

    LaunchedEffect(selected, state, reload) {
        val app = selected ?: return@LaunchedEffect
        runtime = perms.runtimePerms(app.pkg)
        special = if (state == LocalAdb.State.CONNECTED) runCatching { perms.specialAccess(app.pkg) }.getOrNull() else null
    }

    fun act(label: String, block: suspend () -> LocalAdb.Result?) {
        scope.launch {
            status = "$label…"
            val r = runCatching { block() }
            val res = r.getOrNull()
            status = when {
                r.isFailure -> "Failed: ${r.exceptionOrNull()?.message}"
                res == null || res.ok -> "Done: $label"
                else -> "Failed: ${res.output.trim().take(160)}"
            }
            if (selected?.pkg == ctx.packageName) {
                c.media.start()
                c.motion.start()
            }
            reload++
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T("Permissions & ADB", 32, FontWeight.Medium)
                T("Give SharkOS-CE and other apps the permissions BYD's settings hide using Local ADB.", 16, color = C.InkMuted)
            }
            DButton(
                text = if (grantSelfBusy) "Granting…" else "Grant SharkOS-CE permissions",
                onClick = {
                    if (grantSelfBusy) return@DButton
                    scope.launch {
                        grantSelfBusy = true
                        status = "Granting SharkOS-CE permissions via Local ADB…"
                        val res = perms.grantSelfAllPermissions()
                        c.media.start()
                        c.motion.start()
                        status = res.message
                        Toast.makeText(ctx, res.message, Toast.LENGTH_LONG).show()
                        reload++
                        grantSelfBusy = false
                    }
                },
                style = BtnStyle.Primary,
            )
        }
        AdbBar()
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // App list
            DCard(Modifier.width(420.dp).fillMaxHeight(), padding = 0.dp, spacing = 0.dp) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(apps, key = { it.pkg }) { app ->
                        AppRow(app, app.pkg == selected?.pkg) { selected = app; status = null }
                        Hairline()
                    }
                }
            }
            // Permissions of the selected app
            DCard(Modifier.weight(1f).fillMaxHeight(), padding = 24.dp, spacing = 16.dp) {
                val app = selected
                if (app == null) {
                    Empty("No apps installed yet.")
                    return@DCard
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    app.icon?.let { Image(it, null, Modifier.size(48.dp)) }
                    Column(Modifier.weight(1f)) {
                        T(app.label, 24, FontWeight.SemiBold)
                        T(app.pkg, 14, color = C.InkMuted)
                    }
                    if (state == LocalAdb.State.CONNECTED) {
                        DButton("Grant all", {
                            if (app.pkg == ctx.packageName) {
                                scope.launch {
                                    status = "Granting all SharkOS-CE permissions…"
                                    val res = perms.grantSelfAllPermissions()
                                    c.media.start()
                                    c.motion.start()
                                    status = res.message
                                    Toast.makeText(ctx, res.message, Toast.LENGTH_LONG).show()
                                    reload++
                                }
                            } else {
                                act("granting everything to ${app.label}") {
                                    var last: LocalAdb.Result? = null
                                    for (p in runtime.filter { !it.granted }) last = perms.setPerm(app.pkg, p.name, true).takeIf { !it.ok } ?: last
                                    for (s in special.orEmpty().filter { !it.on }) last = perms.setSpecial(app.pkg, s.key, true).takeIf { !it.ok } ?: last
                                    last
                                }
                            }
                        }, style = BtnStyle.Primary, minWidth = 140.dp)
                    }
                }
                // Always takes the same space, so rows don't jump under your finger when a message appears.
                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) {
                    val s = status ?: "Changes apply straight away."
                    T(s, 15, color = if (s.startsWith("Failed")) C.Alert else C.InkSecondary, maxLines = 2)
                }
                Hairline()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Eyebrow("Permissions")
                    if (runtime.isEmpty()) T("This app doesn't ask for any runtime permissions.", 16, color = C.InkMuted)
                    for (p in runtime) {
                        SwitchRow(p.label, p.name.substringAfterLast('.'), p.granted, enabled = state == LocalAdb.State.CONNECTED) { on ->
                            act("${if (on) "granting" else "removing"} ${p.label}") { perms.setPerm(app.pkg, p.name, on) }
                        }
                    }
                    Box(Modifier.height(12.dp))
                    Eyebrow("Special access")
                    val sp = special
                    if (sp == null) T("Connect to see and change special access.", 16, color = C.InkMuted)
                    else for (s in sp) {
                        SwitchRow(s.title, null, s.on, enabled = true) { on ->
                            act("${if (on) "allowing" else "removing"} ${s.title}") { perms.setSpecial(app.pkg, s.key, on) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(if (selected) C.SurfaceRaised else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (app.icon != null) Image(app.icon, null, Modifier.size(36.dp).clip(Radius4))
        else Box(Modifier.size(36.dp).clip(Radius4).background(C.SurfacePanel))
        T(app.label, 18, if (selected) FontWeight.Medium else FontWeight.Normal, if (selected) C.Ink else C.InkSecondary)
    }
}

@Composable
private fun SwitchRow(title: String, detail: String?, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            T(title, 18)
            if (detail != null) T(detail, 13, color = C.InkMuted)
        }
        if (enabled) PillSwitch(on, onChange) else T(if (on) "Allowed" else "Not allowed", 15, color = C.InkMuted)
    }
}
