package com.sharkhome.launcher.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharkhome.launcher.apps.AppEntry
import com.sharkhome.launcher.container
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.components.BtnStyle
import com.sharkhome.launcher.ui.components.DButton
import com.sharkhome.launcher.ui.components.DCard
import com.sharkhome.launcher.ui.components.LineSegmented
import com.sharkhome.launcher.ui.components.Radius4
import com.sharkhome.launcher.ui.components.T
import com.sharkhome.launcher.ui.components.prefsTick

@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val c = ctx.container
    val all by c.apps.apps.collectAsState()
    prefsTick()
    var query by remember { mutableStateOf("") }
    val dock = c.apps.dock(all, c.prefs.dock)
    val filtered = if (query.isBlank()) all else all.filter { it.label.contains(query.trim(), ignoreCase = true) }

    val onLong: (AppEntry) -> Unit = { app ->
        val pinned = c.apps.togglePin(app.pkg)
        Toast.makeText(ctx, if (pinned) "Pinned ${app.label} to the dock" else "Unpinned ${app.label}", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize().padding(end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            T("Apps", 32, FontWeight.Medium, lineHeight = 38)
            run {
                Row(
                    Modifier.weight(1f).widthIn(max = 520.dp).height(48.dp).clip(Radius4).background(C.SurfaceCard).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.Search, null, tint = C.InkSecondary, modifier = Modifier.size(20.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) T("Search installed apps…", 16, color = C.InkMuted)
                        BasicTextField(
                            query, { query = it }, singleLine = true,
                            textStyle = TextStyle(color = C.Ink, fontSize = 16.sp),
                            cursorBrush = SolidColor(C.Ink),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            DButton(
                text = "BYD App List ↗",
                onClick = { c.apps.openBydApps() },
                style = BtnStyle.Lightest,
                height = 48.dp,
                textSize = 15,
            )
        }

        run {
            DCard(Modifier.weight(1f).fillMaxWidth(), spacing = 20.dp) {
                if (query.isBlank() && dock.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        T("Pinned Favourite Apps", 16, FontWeight.SemiBold, C.InkSecondary, modifier = Modifier.weight(1f))
                        T("Long-press any app to pin or unpin from dock", 14, color = C.InkMuted)
                    }
                    dock.chunked(10).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { app -> GridApp(app, Modifier.weight(1f), { c.apps.launch(app) }, { onLong(app) }) }
                            repeat((10 - row.size).coerceAtLeast(0)) { Box(Modifier.weight(1f)) }
                        }
                    }
                    T("All Installed Apps", 16, FontWeight.SemiBold, C.InkSecondary)
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(10),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(filtered, key = { it.pkg + "/" + it.activity }) { app ->
                        GridApp(app, Modifier, { c.apps.launch(app) }, { onLong(app) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridApp(app: AppEntry, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier.clip(Radius4).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        app.icon?.let { Image(it, null, Modifier.size(72.dp)) } ?: Box(Modifier.size(72.dp).clip(Radius4).background(C.SurfacePanel))
        T(app.label, 14, align = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
