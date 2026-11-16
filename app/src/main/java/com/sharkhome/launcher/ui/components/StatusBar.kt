package com.sharkhome.launcher.ui.components

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import com.sharkhome.launcher.container
import com.sharkhome.launcher.media.MediaListenerService
import com.sharkhome.launcher.ui.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import com.sharkhome.launcher.ui.C
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun rememberClock(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(5_000)
        }
    }
    return now
}

/**
 * The 80 px status bar from the design: P R N D with the current gear in bold, battery, lock;
 * clock and outside temperature on the right.
 */
@Composable
fun StatusHeader(car: Car, modifier: Modifier = Modifier, onOpenPanel: () -> Unit = {}) {
    val now = rememberClock()
    val ctx0 = androidx.compose.ui.platform.LocalContext.current
    val gear = car.label("vehicle.gear")
    val battery = car.n("vehicle.batteryPct")
    val fuel = car.n("vehicle.fuelPct")
    val locked = car.label("body.locked") == "locked"
    val low = car.label("light.lowBeam") == "on"
    val high = car.label("light.highBeam") == "on"
    val outside = car.n("climate.outsideTemp")
    Row(
        modifier.fillMaxWidth().height(72.dp)
            // Swipe down anywhere on the bar, like Android's own.
            .swipeDownToOpen(onOpenPanel)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Text(
                buildAnnotatedString {
                    for (g in listOf("P", "R", "N", "D")) {
                        if (g == gear) withStyle(SpanStyle(color = C.Ink, fontWeight = FontWeight.Bold)) { append(g) }
                        else withStyle(SpanStyle(color = C.InkSubtle)) { append(g) }
                    }
                },
                fontSize = 16.sp, letterSpacing = 0.3.em,
            )
            if (battery != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BatteryGlyph((battery / 100).toFloat(), C.Ink)
                    T("${battery.toInt()}%", 20, FontWeight.Medium)
                }
            }
            if (fuel != null) {
                T("Fuel ${fuel.toInt()}%", 16, FontWeight.Medium, C.InkSecondary)
            }
            if (locked) DsIcon(Ic.Lock, C.Ink, 22.dp)
            DsIcon(Ic.Headlight, if (low) C.Ok else C.InkMuted.copy(alpha = 0.28f), 26.dp)
            DsIcon(Ic.HighBeam, if (high) Color(0xFF2F80ED) else C.InkMuted.copy(alpha = 0.28f), 26.dp)
            // Dash cam: while recording, show REC and a one-tap "save the last few minutes" from any screen.
            val dashEngine = androidx.compose.ui.platform.LocalContext.current.container.dashcam
            val dashStatus by dashEngine.status.collectAsState()
            val dashCfg by dashEngine.settings.collectAsState()
            val dashScope = androidx.compose.runtime.rememberCoroutineScope()
            if (dashStatus.running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(C.Alert))
                    T("REC", 16, FontWeight.Bold, C.Alert)
                }
                Box(
                    Modifier.clip(Radius4).background(C.P.btnLightest).border(1.dp, C.Line, Radius4)
                        .clickable {
                            dashScope.launch {
                                val n = dashEngine.saveLastMinutes()
                                android.widget.Toast.makeText(ctx0, if (n > 0) "Saved the last ${dashCfg.saveMinutes} min" else "Nothing to save yet", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }.padding(horizontal = 14.dp, vertical = 6.dp),
                ) { T("Save ${dashCfg.saveMinutes} min", 15, FontWeight.Medium, C.P.btnInk) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            SystemIcons(onOpenPanel)
            T(SimpleDateFormat("h:mma", Locale.getDefault()).format(Date(now)).lowercase(Locale.getDefault()), 24, FontWeight.Medium, C.InkMuted, spacing = 0.01f, lineHeight = 32)
            if (outside != null) T("${outside.toInt()}°C", 24, FontWeight.Medium, C.InkMuted, spacing = 0.01f, lineHeight = 32)
        }
    }
}

internal data class SysStatus(
    val sim: Boolean = false,
    val cellLevel: Int? = null,
    val cellType: String = "",
    val wifi: Boolean = false,
    val btOn: Boolean = false,
    val btConnected: Boolean = false,
    val notifications: Int? = null,
)

internal fun readSysStatus(ctx: Context): SysStatus {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val wifi = runCatching {
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)
    // The Shark's mobile data comes from the T-Box, which Android sees as a network with the CELLULAR transport
    // (type "Tbox", interface vlan3). Android's own telephony has no modem here: no SIM state, no signal level.
    @Suppress("DEPRECATION")
    val mobile = runCatching {
        cm?.allNetworks?.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true } == true
    }.getOrDefault(false)
    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    val sim = runCatching { tm?.simState == TelephonyManager.SIM_STATE_READY }.getOrDefault(false)
    val level = runCatching { tm?.signalStrength?.level }.getOrNull()
    // Needs READ_PHONE_STATE; without it the bars still show, just without the "4G" label.
    val type = runCatching {
        when (tm?.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE, 19 -> "4G"
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            else -> ""
        }
    }.getOrDefault("")
    @Suppress("DEPRECATION")
    val bt = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
    val btOn = runCatching { bt?.isEnabled == true }.getOrDefault(false)
    // The head unit is the hands-free / audio-sink side: HEADSET_CLIENT = 16, A2DP_SINK = 11, PBAP_CLIENT = 17.
    val btConnected = btOn && listOf(16, 11, 17, BluetoothProfile.HEADSET, BluetoothProfile.A2DP).any { profile ->
        runCatching { bt?.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
    }
    return SysStatus(sim || mobile, level ?: if (mobile) 4 else 0, type, wifi, btOn, btConnected, MediaListenerService.count())
}

/**
 * Mobile signal, Wi-Fi, Bluetooth, phone projection (Android Auto / CarPlay) and notification count: the same things
 * BYD's own top bar shows, which full screen hides.
 */
@Composable
private fun SystemIcons(onClick: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val c = ctx.container
    val st by produceState(SysStatus(), ctx) {
        while (true) {
            value = withContext(Dispatchers.Default) { readSysStatus(ctx) }
            delay(3_000)
        }
    }
    val aa by c.media.androidAutoConnected.collectAsState()
    val cp by c.media.carPlayConnected.collectAsState()
    val dim = C.InkMuted.copy(alpha = 0.28f)
    Row(Modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        // Bars lit = mobile data link up. The T-Box doesn't tell Android its signal strength, so it is all or nothing.
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (st.cellType.isNotEmpty()) T(st.cellType, 14, FontWeight.Medium, C.InkSecondary)
            val on = C.Ink
            Canvas(Modifier.size(22.dp, 18.dp)) {
                val bw = size.width / 4f
                for (i in 0 until 4) {
                    val h = size.height * (i + 1) / 4f
                    drawRect(if (i < (st.cellLevel ?: 0)) on else dim, Offset(i * bw, size.height - h), Size(bw - 2f, h))
                }
            }
        }
        if (st.wifi) DsIcon(Ic.Wifi, C.Ink, 22.dp)
        if (st.btOn) DsIcon(Ic.Bluetooth, if (st.btConnected) C.Ink else dim, 22.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DsIcon(Ic.Projection, if (aa || cp) LocalAccent.current else dim, 22.dp)
            if (aa || cp) T(if (aa) "Android Auto" else "CarPlay", 14, FontWeight.Medium, C.InkSecondary)
        }
        st.notifications?.let { n ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DsIcon(Ic.Bell, if (n > 0) C.Ink else dim, 22.dp)
                if (n > 0) T("$n", 14, FontWeight.Medium)
            }
        }
    }
}


/** Calls [onOpen] once a downward drag of about 40 px has started on this element. */
private fun Modifier.swipeDownToOpen(onOpen: () -> Unit): Modifier = pointerInput(onOpen) {
    var total = 0f
    detectVerticalDragGestures(
        onDragStart = { total = 0f },
        onVerticalDrag = { _, dy -> total += dy; if (total > 40f) { total = Float.NEGATIVE_INFINITY; onOpen() } },
    )
}
