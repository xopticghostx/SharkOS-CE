package com.sharkhome.launcher.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.sharkhome.launcher.BuildConfig
import com.sharkhome.launcher.container
import com.sharkhome.launcher.ui.C

const val KOFI_URL = "https://ko-fi.com/xopticghostx"

/** Opens the Ko-fi page in the head unit's browser. */
fun openKofi(ctx: Context) {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { ctx.startActivity(i) }.isFailure) {
        Toast.makeText(ctx, "No browser found. Visit $KOFI_URL", Toast.LENGTH_LONG).show()
    }
}

/** The support card at the top of the About page. */
@Composable
fun SupportCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    DCard(modifier.fillMaxWidth(), spacing = 12.dp) {
        T("Enjoying SharkOS-CE?", 24, FontWeight.Medium)
        T(
            "It's a free project built in spare time for the Shark 6. If it's useful to you, you can buy the developer a coffee on Ko-fi. Thank you!",
            16,
            color = C.InkMuted,
            maxLines = 3,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DButton("Support on Ko-fi", { openKofi(ctx) }, style = BtnStyle.Primary, height = 50.dp, textSize = 16)
            T(KOFI_URL, 14, color = C.InkMuted)
        }
    }
}

/**
 * Shown once after each update (and on the first start after install): a thank-you with the Ko-fi button. The build
 * is remembered when it's shown, so it doesn't come back until the next version.
 */
@Composable
fun SupportPrompt() {
    val ctx = LocalContext.current
    val prefs = ctx.container.prefs
    var show by remember { mutableStateOf(prefs.supportShownBuild < BuildConfig.VERSION_CODE) }
    if (!show) return
    prefs.supportShownBuild = BuildConfig.VERSION_CODE

    Dialog(onDismissRequest = { show = false }) {
        DCard(Modifier.width(720.dp), spacing = 16.dp) {
            T("Thanks for updating", 28, FontWeight.Medium)
            T(
                "SharkOS-CE is free and built in spare time. If it makes your Shark 6 better, a coffee on Ko-fi keeps it going.",
                18,
                color = C.InkMuted,
                maxLines = 3,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DButton("Not now", { show = false }, modifier = Modifier.weight(1f), style = BtnStyle.Lightest, height = 52.dp, textSize = 16)
                DButton("Support on Ko-fi", { openKofi(ctx); show = false }, modifier = Modifier.weight(1f), style = BtnStyle.Primary, height = 52.dp, textSize = 16)
            }
        }
    }
}
