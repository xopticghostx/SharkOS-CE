package com.sharkhome.launcher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent
import java.util.Locale

// The design system uses a 4 px radius everywhere.
val Shape10 = RoundedCornerShape(4.dp)
val Shape12 = RoundedCornerShape(4.dp)

@Composable
fun Txt(
    text: String,
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    color: Color = C.Text,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
    )
}

/** A card: charcoal fill, hairline edge, 12 corner. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = 24.dp,
    color: Color = C.Card,
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.clip(Shape12).background(color).border(1.dp, C.Line, Shape12).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** A big square-ish button; when [on] it is lifted (surface-raised) and its label turns the link colour. */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    on: Boolean = false,
    onColor: Color = C.SurfaceRaised,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Dark theme: "on" is the accent fill (white label), off is a dark button.
    val onFill = if (C.P.dark) LocalAccent.current else C.SurfaceRaised
    val offFill = if (C.P.dark) C.P.btnLightest else C.SurfacePanel
    Column(
        modifier.clip(Shape10).background(if (on) onFill else offFill).border(if (on && !C.P.dark) 2.dp else 1.dp, if (on && !C.P.dark) LocalAccent.current else C.Line, Shape10)
            .clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        content = content,
    )
}

@Composable
fun ToggleTile(label: String, icon: ImageVector?, on: Boolean, modifier: Modifier = Modifier, onColor: Color = C.SurfaceRaised, onClick: () -> Unit) {
    Tile(modifier, on = on, onColor = onColor, onClick = onClick) {
        val col = if (on) (if (C.P.dark) C.OnAccent else C.Link) else C.InkSecondary
        if (icon != null) Icon(icon, null, tint = col, modifier = Modifier.size(32.dp))
        Txt(label, 18, if (on) FontWeight.Medium else FontWeight.Normal, col)
    }
}

@Composable
fun Segmented(
    options: List<String>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    activeColor: (Int) -> Color = { C.Pressed },
    activeText: (Int) -> Color = { C.Text },
) {
    Row(modifier.clip(Shape10).background(if (C.P.dark) C.P.btnLightest else C.SurfacePanel).border(1.dp, C.Line, Shape10)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            if (i > 0) Box(Modifier.width(1.dp).height(height).background(C.Line))
            Box(
                Modifier.weight(1f).height(height)
                    .background(if (sel) activeColor(i) else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Txt(o, 18, if (sel) FontWeight.Medium else FontWeight.Normal, if (sel) activeText(i) else C.Text3)
            }
        }
    }
}

@Composable
fun Meter(fraction: Float?, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(C.Raised)) {
        if (fraction != null) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(color))
        }
    }
}

@Composable
fun IconBox(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    tint: Color = C.Text,
    size: Dp = 72.dp,
    iconSize: Dp = 30.dp,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(size).clip(Shape10).background(if (selected) C.P.btnLightest else Color.Transparent)
            .let { if (selected) it.border(1.dp, C.Line, Shape10) else it }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, height: Dp = 56.dp) {
    Box(
        modifier.height(height).clip(Shape10).background(if (primary) LocalAccent.current else C.P.btnLightest)
            .let { if (primary || C.P.dark) it else it.border(1.dp, C.Line, Shape10) }
            .clickable(onClick = onClick).padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, 18, if (primary) FontWeight.Medium else FontWeight.Normal, if (primary) C.OnAccent else C.P.btnInk)
    }
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = C.Text) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Txt(label, 16, color = C.Text2)
        Txt(value, 16, FontWeight.Medium, valueColor)
    }
}

/** A vertical slider (used for the equaliser bands). [value] is 0..1. */
@Composable
fun VSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, color: Color = LocalAccent.current) {
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier.width(36.dp).fillMaxHeight()
            .pointerInput(Unit) { detectTapGestures { o -> change((1f - o.y / size.height).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectVerticalDragGestures { c, _ -> change((1f - c.position.y / size.height).coerceIn(0f, 1f)) }
            },
    ) {
        val knobR = 12.dp.toPx()
        val top = knobR
        val h = size.height - 2 * knobR
        val cx = size.width / 2
        val trackW = 6.dp.toPx()
        drawRoundRect(C.Raised, Offset(cx - trackW / 2, top), Size(trackW, h), CornerRadius(trackW / 2))
        val mid = top + h / 2
        val y = top + h * (1f - value)
        drawLine(C.InkMuted, Offset(cx - 9.dp.toPx(), mid), Offset(cx + 9.dp.toPx(), mid), 2.dp.toPx())
        drawRect(color, Offset(cx - trackW / 2, minOf(y, mid)), Size(trackW, kotlin.math.abs(mid - y)))
        drawCircle(C.Text, knobR, Offset(cx, y))
    }
}

fun Double?.fmt(decimals: Int = 0, unit: String = ""): String =
    this?.let {
        val s = String.format(Locale.getDefault(), "%.${decimals}f", it)
        // "-0" / "-0.0" reads oddly on a gauge: drop the sign when the rounded value is zero.
        (if (s.trimStart('-').all { ch -> ch == '0' || ch == '.' || ch == ',' }) s.trimStart('-') else s) + unit
    } ?: "--"

fun Float?.fmt(decimals: Int = 0, unit: String = ""): String = this?.toDouble().fmt(decimals, unit)
