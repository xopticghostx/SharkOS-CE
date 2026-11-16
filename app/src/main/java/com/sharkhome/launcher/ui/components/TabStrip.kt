package com.sharkhome.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent

/** One tab on a page's [NavTabStrip]. [badge] shows a small dot (e.g. an update is waiting). */
class StripTab(val label: String, val icon: Ic, val badge: Boolean = false)

/**
 * The tab strip across the top of a page (Controls, Settings...). The page's sections live here, not in a second sidebar.
 */
@Composable
fun NavTabStrip(tabs: List<StripTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Row(
        modifier.fillMaxWidth().clip(Radius4).background(C.SurfacePanel).border(1.dp, C.Line, Radius4).padding(4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            Row(
                Modifier.height(44.dp).clip(Radius4).background(if (sel) C.SurfaceRaised else Color.Transparent)
                    .then(if (sel) Modifier.border(1.dp, C.Line, Radius4) else Modifier)
                    .clickable { onSelect(i) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DsIcon(t.icon, if (sel) accent else C.InkSecondary, 20.dp)
                T(t.label, 15, if (sel) FontWeight.SemiBold else FontWeight.Normal, if (sel) C.Ink else C.InkSecondary, maxLines = 1)
                if (t.badge) Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            }
        }
    }
}
