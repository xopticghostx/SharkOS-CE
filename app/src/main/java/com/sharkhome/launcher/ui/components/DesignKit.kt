package com.sharkhome.launcher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.sharkhome.launcher.ui.C
import com.sharkhome.launcher.ui.LocalAccent

// ---------------------------------------------------------------- icons (traced from the design canvas SVGs)

/** An icon from the design: SVG path data in a [viewBox]-sized square, drawn as a 1.75 stroke or as a fill. */
class Ic(val paths: List<String>, val stroked: Boolean = true, val viewBox: Float = 24f) {
    companion object {
        private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0z"
        private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
            "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

        val Bluetooth = Ic(listOf("m6.5 6.5 11 11L12 23V1l5.5 5.5-11 11"))
        val Wifi = Ic(listOf("M5 12.55a11 11 0 0 1 14.08 0", "M1.42 9a16 16 0 0 1 21.16 0", "M8.53 16.11a6 6 0 0 1 6.95 0", "M12 20h.01"))
        val Bell = Ic(listOf("M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9", "M13.73 21a2 2 0 0 1-3.46 0"))
        /** Phone projection (Android Auto / CarPlay): a navigation arrow. */
        val Projection = Ic(listOf("M12 3 4 21l8-4 8 4z"))
        val Back = Ic(listOf("M9 14 4 9l5-5", "M4 9h10a6 6 0 0 1 0 12h-3"))
        val Lock = Ic(listOf("M7 10V8a5 5 0 0 1 10 0v2h1a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1zm2 0h6V8a3 3 0 0 0-6 0z"), stroked = false)
        val Headlight = Ic(listOf("M13 6c4 0 7 2.7 7 6s-3 6-7 6c-1 0-1.5-.5-1.5-1.5v-9C11.5 6.5 12 6 13 6z", "M3 8h6M3 12h6M3 16h6"))
        val HighBeam = Ic(listOf("M13 6c4 0 7 2.7 7 6s-3 6-7 6c-1 0-1.5-.5-1.5-1.5v-9C11.5 6.5 12 6 13 6z", "M3 9h6M3 12h6M3 15h6", "M16 10h2"))
        val Search = Ic(listOf(circle(11f, 11f, 7f), "m20 20-4-4"))
        val Home = Ic(listOf("M12 3 2 11h3v10h5v-6h4v6h5V11h3z"), stroked = false)
        val Work = Ic(listOf("M9 4h6a1 1 0 0 1 1 1v2h4a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1h4V5a1 1 0 0 1 1-1zm1 3h4V6h-4z"), stroked = false)
        val ChevronLeft = Ic(listOf("m15 18-6-6 6-6"))
        val ChevronRight = Ic(listOf("m9 18 6-6-6-6"))
        val ChevronDown = Ic(listOf("m6 9 6 6 6-6"))
        val ChevronUp = Ic(listOf("m6 15 6-6 6 6"))
        val Fan = Ic(listOf(circle(12f, 12f, 2f), "M12 10c0-4 1-7 4-7s2 5-2 7M14 12c4 0 7 1 7 4s-5 2-7-2M12 14c0 4-1 7-4 7s-2-5 2-7M10 12c-4 0-7-1-7-4s5-2 7 2"))
        val AC = Ic(listOf("M12 2v20M4 6l16 12M20 6 4 18"))
        val MaxCool = Ic(listOf("M12 2v20M4 6l16 12M20 6 4 18", circle(12f, 12f, 3f)))
        val Recirc = Ic(listOf("M4 17h11a4 4 0 0 0 0-8H6", "m9 6-3 3 3 3"))
        val Window = Ic(listOf("M5 20V9l4-5h7l3 5v11z", "M5 13h14"))
        val Wiper = Ic(listOf("M3 17a13 13 0 0 1 18 0", "M12 17 7 7"))
        val Rain = Ic(listOf("M7 15a4 4 0 0 1 .5-8 5.5 5.5 0 0 1 10.5 1.5A3.5 3.5 0 0 1 17.5 15z", "M8 18l-1 3M12 18l-1 3M16 18l-1 3"))
        val Brightness = Ic(listOf(circle(12f, 12f, 4f), "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"))
        val FrontDemist = Ic(listOf("M3 17c4-8 14-8 18 0", "M8 7c1 1.3 1 2.7 0 4M12 5c1 1.3 1 2.7 0 4M16 7c1 1.3 1 2.7 0 4"))
        val RearDemist = Ic(listOf(rect(3f, 6f, 18f, 12f, 2f), "M7 10c1 1 1 3 0 4M12 10c1 1 1 3 0 4M17 10c1 1 1 3 0 4"))
        val Plus = Ic(listOf("M12 5v14M5 12h14"))
        val Minus = Ic(listOf("M5 12h14"))
        val OffRoad = Ic(listOf("m3 20 6-10 4 6 3-4 5 8z"))
        val Gauges = Ic(listOf("M4 16a8 8 0 1 1 16 0", "m12 16 4-5"))
        /** BYD Shark 6 dual-cab ute side profile (facing left). */
        val Vehicle = Ic(
            listOf(
                "M2.9 15.6H2.3Q1.8 15.6 1.8 15V11Q1.9 10 3 9.9L7.1 9.6L9.3 6.3Q9.6 5.9 10.2 5.9H14.1Q14.5 5.9 14.6 6.4V10.3H21.6Q22.2 10.3 22.2 11V15Q22.2 15.6 21.6 15.6H21.1L20.2 13.3Q17.6 12.4 15 13.3L14.1 15.6H9.9L8.9 13.3Q6.4 12.4 3.9 13.3Z",
                "M7.2 9.9H14.6M11.2 5.9V9.9M14.6 6.4L16.7 10.3",
                circle(6.4f, 16.6f, 1.7f),
                circle(17.6f, 16.6f, 1.7f),
            ),
        )
        val Apps = Ic(
            listOf(5f, 12f, 19f).flatMap { y -> listOf(5f, 12f, 19f).map { x -> circle(x, y, 2f) } },
            stroked = false,
        )
        val Music = Ic(listOf("M9 18V5l12-2v13", circle(6f, 18f, 3f), circle(18f, 16f, 3f)))
        val Cast = Ic(listOf("M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6", "M2 12a9 9 0 0 1 8 8M2 16a5 5 0 0 1 4 4"))
        val Sliders = Ic(listOf("M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"))
        val Prev = Ic(listOf("M6 5h2v14H6zM20 5v14L9 12z"), stroked = false)
        val Next = Ic(listOf("M16 5h2v14h-2zM4 5v14l11-7z"), stroked = false)
        val Play = Ic(listOf("M7 5v14l12-7z"), stroked = false)
        val Pause = Ic(listOf("M6 5h4v14H6zM14 5h4v14h-4z"), stroked = false)
        val Volume = Ic(listOf("M4 9h4l5-4v14l-5-4H4z", "M16.5 8.5a5 5 0 0 1 0 7"))
        val Face = Ic(listOf("M5 12h14M5 12l4-4M5 12l4 4"))
        val FaceFeet = Ic(listOf("M5 9h14M5 9l4-4M5 9l4 4M5 17h14M5 17l4-4M5 17l4 4"))
        val Feet = Ic(listOf("M5 17h14M5 17l4-4M5 17l4 4"))
        val Display = Ic(listOf(rect(3f, 4f, 18f, 13f, 2f), "M8 21h8M12 17v4"))
        val External = Ic(listOf("M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"))
        val HillDescent = Ic(listOf("m3 7 18 12", "M7 15v4M12 13v6M17 16v3"))
        val Diagnostics = Ic(listOf("M3 12h4l3-8 4 16 3-8h4"))
        val About = Ic(listOf(circle(12f, 12f, 9f), "M12 8h.01M11 12h1v5h1"))
        val Profile = Ic(listOf(circle(12f, 8f, 4f), "M4 21a8 8 0 0 1 16 0z"), stroked = false)
        val TurnRight = Ic(listOf("M6 20v-7a4 4 0 0 1 4-4h9", "m15 5 4 4-4 4"))
        val Star = Ic(listOf("m12 3 2.6 5.6 6.1.7-4.5 4.2 1.2 6L12 16.6 6.6 19.5l1.2-6L3.3 9.3l6.1-.7z"))
        val Sparkle = Ic(listOf("M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6"))
        val Map = Ic(listOf("m9 4-6 2v14l6-2 6 2 6-2V4l-6 2z", "M9 4v14M15 6v14"))
        val BatteryCharging = Ic(listOf(rect(2f, 6f, 17f, 12f, 2f), "M21 10v4", "m11 8-3 4h3l-1 4 4-5h-3z"))
        val SplitScreen = Ic(listOf(rect(3f, 4f, 18f, 16f, 2f), "M12 4v16"))
        val Download = Ic(listOf("M12 3v12", "m7 10 5 5 5-5", "M5 20h14"))
        /** Seat in profile with heat waves above it. */
        val SeatHeat = Ic(listOf("M7 21v-4h9l2 4", "M6 17 4.5 9.5a2 2 0 0 1 3.9-.9L10 14h6", "M12 3c1 1.2 1 2.3 0 3.5s-1 2.3 0 3.5M16 3c1 1.2 1 2.3 0 3.5s-1 2.3 0 3.5M20 3c1 1.2 1 2.3 0 3.5s-1 2.3 0 3.5"))
        /** Seat in profile with air flowing up through it. */
        val SeatVent = Ic(listOf("M7 21v-4h9l2 4", "M6 17 4.5 9.5a2 2 0 0 1 3.9-.9L10 14h6", "M13 10V3m0 0-2 2m2-2 2 2M18 10V3m0 0-2 2m2-2 2 2"))
        val Gear = Ic(listOf("M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z", "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"))
        val Shield = Ic(listOf("M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z", "m9 12 2 2 4-4"))

        // BYD OEM / Tesla minimalistic quick-setting icons (1.75px stroke)
        /** Ute rear cargo bed with light beams shining into tray. */
        val CargoLight = Ic(listOf("M2 16h8v-5H5L3 16zM10 13h11v3H2v3h20v-3", "M14 5v4M17.5 6l-1.5 3.5M21 7.5l-2 3"))
        /** Windshield trapezoid projection frame with HUD reticle. */
        val Hud = Ic(listOf("M5 6h14l3 12H2z", "M9 11.5h6M10 14.5h4", "M7 10v4M17 10v4"))
        /** Windshield HUD frame with snowflake projection. */
        val HudSnow = Ic(listOf("M5 5h14l3 14H2z", "M12 8.5v7M9 10.2l6 3.6M15 10.2l-6 3.6"))
        /** Wing mirror housing with blind-spot radar waves. */
        val BlindSpot = Ic(listOf("M3 10.5c0-2 2-3.5 5-3.5h4c1.5 0 2.5 1 2.5 2.5v5c0 1.5-1 2.5-2.5 2.5H6c-2 0-3-1.5-3-3.5z", "M17 9.5a4 4 0 0 1 0 5", "M19.8 7.5a7 7 0 0 1 0 9"))
        /** Minimalist smartphone on wireless charging base with lightning bolt. */
        val WirelessPad = Ic(listOf(rect(6f, 3f, 12f, 15f, 2f), "m12.5 7-2 3.5h3l-2 3.5", "M4 21h16"))
        /** Side mirror outline with inward fold arrow. */
        val MirrorFold = Ic(listOf("M3.5 9.5c0-2 2-3.5 5.5-3.5h5c1.5 0 2.5 1 2.5 2.5v6c0 1.5-1 2.5-2.5 2.5H6.5c-2 0-3-1.5-3-3.5z", "M21 11.5h-6m0 0 2.5-2.5M15 11.5l2.5 2.5"))
        /** Display screen with curved rotation arrows. */
        val ScreenRotate = Ic(listOf(rect(5f, 6f, 14f, 10f, 1.5f), "M16 3h4v4", "M8 21H4v-4", "M20 3l-3 3M4 21l3-3"))
        /** Minimalist 3-spoke steering wheel. */
        val SteeringWheel = Ic(listOf(circle(12f, 12f, 9f), circle(12f, 12f, 2.2f), "M3.5 10.5h6.3M14.2 10.5h6.3M12 14.2V21"))
        /** Heated steering wheel with rising heat waves. */
        val SteeringHeat = Ic(listOf(circle(12f, 13.5f, 7.5f), circle(12f, 13.5f, 2f), "M4.8 12.5h5.2M14 12.5h5.2M12 15.5V21", "M8 2.5c.8 1 .8 2 0 3M12 2c.8 1 .8 2 0 3M16 2.5c.8 1 .8 2 0 3"))
        /** Brake rotor disc with left/right caliper shoes. */
        val BrakeFeel = Ic(listOf(circle(12f, 12f, 6.2f), circle(12f, 12f, 2f), "M4.5 6.5a9.5 9.5 0 0 0 0 11", "M19.5 6.5a9.5 9.5 0 0 1 0 11"))
        /** Energy regeneration circular arrows around a bolt. */
        val Regen = Ic(listOf("M4.5 10A8 8 0 0 1 18 6.5", "M18 3.5v3h-3", "M19.5 14A8 8 0 0 1 6 17.5", "M6 20.5v-3h3", "m12.5 8.5-2 3.5h3l-2 3.5"))
        /** Battery with SOC hold shield/bar. */
        val SocHold = Ic(listOf(rect(2f, 6f, 17f, 12f, 2f), "M21 10v4", "M7.5 12h6M10.5 9v6"))
        /** Full-screen 4-corner expansion frame. */
        val FullScreen = Ic(listOf("M4 9V4h5", "M20 9V4h-5", "M4 15v5h5", "M20 15v5h-5"))
        /** Sidebar panel with collapse-left arrow. */
        val SidebarHide = Ic(listOf(rect(3f, 4f, 18f, 16f, 2f), "M9 4v16", "m16 9-3 3 3 3"))
        /** Minimalist dashcam / video camera with lens reticle. */
        val DashCam = Ic(listOf(rect(2.5f, 6f, 13f, 12f, 2f), "m15.5 10 6-3v10l-6-3z", circle(9f, 12f, 2.3f)))
    }
}

@Composable
fun DsIcon(icon: Ic, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val paths = remember(icon) { icon.paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / icon.viewBox
        scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            for (p in paths) {
                if (icon.stroked) drawPath(p, tint, style = Stroke(width = 1.75f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                else drawPath(p, tint)
            }
        }
    }
}

/** Battery glyph from the status bar, filled to [fraction]. */
@Composable
fun BatteryGlyph(fraction: Float, tint: Color) {
    Canvas(Modifier.size(30.dp, 16.dp)) {
        val u = size.width / 30f
        drawRoundRect(tint, androidx.compose.ui.geometry.Offset(1f * u, 1f * u), androidx.compose.ui.geometry.Size(25f * u, 14f * u),
            androidx.compose.ui.geometry.CornerRadius(3f * u), style = Stroke(1.5f * u))
        drawRoundRect(tint, androidx.compose.ui.geometry.Offset(3.5f * u, 3.5f * u), androidx.compose.ui.geometry.Size(20f * u * fraction.coerceIn(0f, 1f), 9f * u),
            androidx.compose.ui.geometry.CornerRadius(1.5f * u))
        drawRoundRect(tint, androidx.compose.ui.geometry.Offset(27f * u, 5f * u), androidx.compose.ui.geometry.Size(2f * u, 6f * u),
            androidx.compose.ui.geometry.CornerRadius(1f * u))
    }
}

// ---------------------------------------------------------------- text

val Radius4 = RoundedCornerShape(4.dp)

/** Text with the design's type settings (size in px on the 1920 canvas, optional letter spacing in em). */
@Composable
fun T(
    text: String,
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    color: Color = C.Ink,
    modifier: Modifier = Modifier,
    spacing: Float = 0f,
    lineHeight: Int? = null,
    maxLines: Int = 1,
    align: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        letterSpacing = spacing.em,
        lineHeight = (lineHeight ?: (size * 1.2f).toInt()).sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
    )
}

/** Uppercase section label: PRESETS, FAVOURITE APPS, GO TO. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    T(text.uppercase(), 14, color = C.InkMuted, spacing = 0.08f, modifier = modifier)
}

// ---------------------------------------------------------------- containers

/** Home-screen card: surface-card fill, 4 radius, soft shadow. */
@Composable
fun DCard(
    modifier: Modifier = Modifier,
    padding: Dp = 24.dp,
    spacing: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Dark theme: flat cards (no shadow), separated by spacing only.
    val m = (if (C.P.dark) modifier else modifier.shadow(6.dp, Radius4, clip = false, ambientColor = C.Shadow, spotColor = C.Shadow))
        .clip(Radius4).background(C.SurfaceCard)
        .border(1.dp, C.Line, Radius4)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(padding)
    Column(m, verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

/** A 1px divider in the line colour. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = C.Line) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun VHairline(modifier: Modifier = Modifier, color: Color = C.Line) {
    Box(modifier.width(1.dp).fillMaxHeight().background(color))
}

// ---------------------------------------------------------------- controls

enum class BtnStyle { Primary, Dark, Light, Lightest }

@Composable
fun DButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.Lightest,
    height: Dp = 56.dp,
    minWidth: Dp = 0.dp,
    selected: Boolean = false,
    textSize: Int = 18,
) {
    val accent = LocalAccent.current
    val (bg, fg) = when (style) {
        BtnStyle.Primary -> accent to C.OnAccent
        // In the dark theme the kit's dark button would vanish into the panel, so it is lifted instead.
        BtnStyle.Dark -> (if (C.P.dark) C.SurfaceRaised else C.BtnDark) to Color.White
        BtnStyle.Light -> C.P.btnLight to C.P.btnInk
        BtnStyle.Lightest -> if (selected) accent to C.OnAccent else C.P.btnLightest to C.P.btnInk
    }
    var m = modifier.height(height).clip(Radius4).background(bg)
    if (style == BtnStyle.Lightest && !selected && !C.P.dark) m = m.border(1.dp, C.Line, Radius4)
    Box(
        m.widthIn(min = minWidth).clickable(onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text, textSize, if (selected || style == BtnStyle.Primary) FontWeight.Medium else FontWeight.Normal, fg, align = TextAlign.Center)
    }
}

/** Square chevron button (44 × 44, lightest). */
@Composable
fun ChevronButton(icon: Ic, desc: String, onClick: () -> Unit, size: Dp = 56.dp, iconSize: Dp = 26.dp) {
    Box(
        Modifier.size(size).clip(Radius4).background(C.P.btnLightest).let { if (C.P.dark) it else it.border(1.dp, C.Line, Radius4) }.clickable(onClickLabel = desc, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { DsIcon(icon, C.P.btnInk, iconSize) }
}

/** Segmented control with a hairline frame; the selected segment is lifted (surface-raised). */
@Composable
fun LineSegmented(
    options: List<String>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    textSize: Int = 17,
) {
    Row(modifier.height(height).clip(Radius4).background(if (C.P.dark) C.P.btnLightest else C.SurfacePanel).border(1.dp, C.Line, Radius4)) {
        options.forEachIndexed { i, o ->
            if (i > 0) VHairline()
            val sel = i == selected
            // Selected = accent fill, white label (a pale grey selection was hard to see in the dark theme).
            Box(
                Modifier.weight(1f).fillMaxHeight().background(if (sel) LocalAccent.current else Color.Transparent).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { T(o, textSize, if (sel) FontWeight.Medium else FontWeight.Normal, if (sel) Color.White else C.InkSecondary) }
        }
    }
}

/** A control tile. [enabled] = false draws an empty filler cell (keeps a short last row the same tile width). */
data class TileSpec(val label: String, val icon: Ic, val on: Boolean, val enabled: Boolean = true, val onClick: () -> Unit)

/** A row of joined control tiles (A/C, Recirculate, ...). On tiles show their icon and label in the link colour. */
@Composable
fun TileRow(tiles: List<TileSpec>, modifier: Modifier = Modifier, height: Dp = 96.dp) {
    val accent = LocalAccent.current
    // height = Dp.Unspecified: take the height the caller's modifier gives (tiles that stretch to fill a widget).
    val sized = if (height == Dp.Unspecified) modifier else modifier.height(height)
    Row(sized.clip(Radius4).background(if (C.P.dark) C.P.btnLightest else C.SurfacePanel).border(1.dp, C.Line, Radius4)) {
        tiles.forEachIndexed { i, t ->
            if (i > 0) VHairline()
            if (!t.enabled) {
                Box(Modifier.weight(1f).fillMaxHeight())
                return@forEachIndexed
            }
            // Dark theme: "on" is the accent fill with a white label, so it reads at a glance.
            val onFill = if (C.P.dark) accent else C.SurfaceRaised
            val col = if (t.on) (if (C.P.dark) C.OnAccent else C.Link) else C.InkSecondary
            Column(
                Modifier.weight(1f).fillMaxHeight().background(if (t.on) onFill else Color.Transparent)
                    .then(if (t.on && !C.P.dark) Modifier.border(2.dp, accent) else Modifier).clickable(onClick = t.onClick),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                DsIcon(t.icon, col, 28.dp)
                T(t.label, 15, if (t.on) FontWeight.Medium else FontWeight.Normal, col)
            }
        }
    }
}

/** Pill switch (40 × 20): accent track when on. */
@Composable
fun PillSwitch(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Box(
        modifier.size(40.dp, 20.dp).clip(CircleShape).background(if (on) accent else C.ControlOff).clickable { onChange(!on) }.padding(2.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White)) }
}

/** Thin progress bar: line track, ink fill. */
@Composable
fun ThinBar(fraction: Float?, modifier: Modifier = Modifier, color: Color = C.Ink, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(C.Raised)) {
        if (fraction != null) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(color))
        }
    }
}

/** Small label over a value: "Avg fuel / 6.8 L/100 km". */
@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier, valueSize: Int = 18, labelSize: Int = 14) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        T(label, labelSize, color = C.InkMuted)
        T(value, valueSize, FontWeight.Medium)
    }
}

/** Fan level bar: the filled part is lifted, with the fan glyph at the left. Tap anywhere to set the level. */
@Composable
fun FanBar(level: Int, max: Int, onSet: (Int) -> Unit, modifier: Modifier = Modifier, height: Dp = 56.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(Radius4).background(if (C.P.dark) C.P.btnLightest else C.SurfacePanel).border(1.dp, C.Line, Radius4)) {
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            for (i in 1..max) {
                Box(Modifier.weight(1f).fillMaxHeight().background(if (i <= level) C.SurfaceRaised else Color.Transparent).clickable { onSet(i) })
            }
        }
        DsIcon(Ic.Fan, C.InkSecondary, 20.dp, Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
    }
}
