package com.sharkhome.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import android.widget.Toast
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sharkhome.launcher.container
import com.sharkhome.launcher.media.NowPlaying
import com.sharkhome.launcher.system.AppPermissions
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent
import kotlinx.coroutines.launch

private fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun AlbumArt(np: NowPlaying?, size: Dp) {
    val art = np?.art
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(C.P.btnLight),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) Image(art.asImageBitmap(), null, Modifier.size(size), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, tint = C.InkMuted, modifier = Modifier.size(size * 0.4f))
    }
}

@Composable
fun TrackProgress(np: NowPlaying?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(mmss(np?.positionMs ?: 0), 13, color = C.Text2)
        val frac = if (np != null && np.durationMs > 0) np.positionMs.toFloat() / np.durationMs else 0f
        Meter(frac, C.Text, Modifier.weight(1f), height = 4.dp)
        Txt(mmss(np?.durationMs ?: 0), 13, color = C.Text2)
    }
}

@Composable
fun TransportButtons(big: Dp = 72.dp) {
    val media = LocalContext.current.container.media
    val np by media.nowPlaying.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBox(Icons.Rounded.SkipPrevious, "Previous track", { media.previous() }, size = 64.dp, iconSize = 32.dp)
        Box(
            Modifier.size(big).clip(CircleShape).background(C.Text).clickable { media.playPause() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (np?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (np?.playing == true) "Pause" else "Play",
                tint = C.Bg, modifier = Modifier.size(34.dp),
            )
        }
        IconBox(Icons.Rounded.SkipNext, "Next track", { media.next() }, size = 64.dp, iconSize = 32.dp)
    }
}

/** Shown in place of track info until notification access is granted. */
@Composable
fun MediaAccessPrompt(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = ctx.container
    val media = c.media
    val scope = rememberCoroutineScope()
    var busyMedia by remember { mutableStateOf(false) }
    var busyAll by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("Allow media access to show what's playing", 17, FontWeight.Medium, maxLines = 2)
        Txt("SharkOS-CE only uses it for track info and controls — it never reads notifications.", 14, color = C.Text2, maxLines = 2)
        Txt("With USB Debugging enabled, SharkOS-CE grants access directly inside the app via Local ADB.", 14, color = C.Text2, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallButton(
                if (busyMedia) "Enabling…" else "Allow media access",
                {
                    if (busyMedia || busyAll) return@SmallButton
                    scope.launch {
                        busyMedia = true
                        media.enableAccessWithAdb()
                        busyMedia = false
                    }
                },
                primary = true,
                height = 48.dp,
            )
            SmallButton(
                if (busyAll) "Granting…" else "Grant all permissions",
                {
                    if (busyMedia || busyAll) return@SmallButton
                    scope.launch {
                        busyAll = true
                        val res = AppPermissions(ctx, c.adb).grantSelfAllPermissions()
                        media.start()
                        c.motion.start()
                        Toast.makeText(ctx, res.message, Toast.LENGTH_LONG).show()
                        busyAll = false
                    }
                },
                height = 48.dp,
            )
        }
    }
}

/** The wide now-playing strip used over the map. */
@Composable
fun MediaStrip(modifier: Modifier = Modifier) {
    val media = LocalContext.current.container.media
    val np by media.nowPlaying.collectAsState()
    val access by media.hasAccess.collectAsState()
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(C.Card2.copy(alpha = 0.95f)).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        if (!access) {
            MediaAccessPrompt(Modifier.weight(1f))
            return@Row
        }
        AlbumArt(np, 88.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(np?.title ?: "Nothing playing", 22, FontWeight.Medium)
            Txt(np?.artist ?: "", 17, color = C.Text2)
            TrackProgress(np)
        }
        TransportButtons()
    }
}

