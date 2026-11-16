package com.sharkhome.launcher.ui.components

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.sharkhome.launcher.R
import com.sharkhome.launcher.container
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

private class SharkPaths {
    val body = p("M40 192 L36 124 Q36 116 44 116 L252 116 L262 64 Q266 52 280 51 L414 50 Q428 50 436 60 L470 100 L582 106 Q604 108 608 124 L612 170 Q612 192 594 194 L60 196 Q42 196 40 192 Z")
    val tubRail = p("M44 116 L252 116 L251 122 L40 122 Z")
    val belt = p("M262 101 L470 101 L582 107")
    val glass = p("M270 66 Q272 58 282 58 L410 57 Q420 57 426 64 L456 98 L268 98 Z")
    val panelLines = p("M262 104 V184 M361 104 V184 M462 102 L460 184")
    val mirror = p("M440 93 L461 97 L461 107 L444 105 Z")
    val skid = p("M588 178 L611 174 L610 190 L592 193 Z")
    val archRear = p("M96 196 A54 54 0 0 1 204 196 Z")
    val archFront = p("M431 196 A54 54 0 0 1 539 196 Z")
    val flareRear = p("M89 196 A61 61 0 0 1 211 196")
    val flareFront = p("M424 196 A61 61 0 0 1 546 196")
}

/**
 * Side view of the Shark 6 in the Tesla-style monochrome treatment (drawn in a 640×250 box).
 * Front of the ute faces right.
 */
@Composable
fun Shark6Art(modifier: Modifier = Modifier) {
    val paths = remember { SharkPaths() }
    Canvas(modifier) {
        val s = min(size.width / 640f, size.height / 250f)
        val dx = (size.width - 640f * s) / 2f
        val dy = (size.height - 250f * s) / 2f
        withTransform({
            translate(dx, dy)
            scale(s, s, Offset.Zero)
        }) {
            drawShark(paths)
        }
    }
}

private fun DrawScope.drawShark(sp: SharkPaths) {
    val dark = Color(0xFF1C1E21)
    // Floor spotlight and contact shadow
    drawOval(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), center = Offset(320f, 234f), radius = 330f),
        topLeft = Offset(-10f, 206f), size = Size(660f, 56f),
    )
    drawOval(Color.Black.copy(alpha = 0.6f), topLeft = Offset(52f, 233f), size = Size(540f, 14f))
    // Roof rails
    drawRoundRect(dark, Offset(286f, 44f), Size(126f, 4f), CornerRadius(2f))
    // Body
    drawPath(
        sp.body,
        Brush.verticalGradient(
            0f to Color(0xFF6B7076), 0.45f to Color(0xFF43474D), 1f to Color(0xFF232529),
            startY = 50f, endY = 196f,
        ),
    )
    drawPath(sp.tubRail, dark)
    drawPath(sp.belt, Color(0xFF9AA0A7).copy(alpha = 0.45f), style = Stroke(1.5f))
    drawPath(sp.glass, Brush.linearGradient(listOf(Color(0xFF2C3037), Color(0xFF111316)), start = Offset(268f, 57f), end = Offset(456f, 98f)))
    drawRect(Color(0xFF0F1012), Offset(353f, 57f), Size(7f, 42f))
    drawPath(sp.panelLines, Color(0xFF1E2023), style = Stroke(1.2f))
    drawRoundRect(Color(0xFF2A2D31), Offset(320f, 118f), Size(18f, 3f), CornerRadius(1.5f))
    drawRoundRect(Color(0xFF2A2D31), Offset(422f, 118f), Size(18f, 3f), CornerRadius(1.5f))
    drawPath(sp.mirror, dark)
    // Lights and bash plate
    drawLine(Color(0xFFE6EEF8), Offset(585f, 115f), Offset(607f, 119f), strokeWidth = 3f, cap = StrokeCap.Round)
    drawPath(sp.skid, Color(0xFF8C9096).copy(alpha = 0.55f))
    drawRoundRect(Color(0xFFB5363D), Offset(37f, 126f), Size(4f, 26f), CornerRadius(2f))
    // Side step, arches, flares
    drawRoundRect(Color(0xFF1A1C1F), Offset(205f, 189f), Size(226f, 7f), CornerRadius(3.5f))
    drawPath(sp.archRear, Color(0xFF0C0D0F))
    drawPath(sp.archFront, Color(0xFF0C0D0F))
    drawPath(sp.flareRear, dark, style = Stroke(14f))
    drawPath(sp.flareFront, dark, style = Stroke(14f))
    wheel(150f)
    wheel(485f)
}

private fun DrawScope.wheel(cx: Float) {
    val c = Offset(cx, 196f)
    drawCircle(Color(0xFF0D0E10), 46f, c)
    drawCircle(Color(0xFF1A1B1E), 40f, c, style = Stroke(4f))
    drawCircle(Color(0xFF232529), 30f, c)
    drawCircle(Color(0xFF5A5E65), 30f, c, style = Stroke(1.5f))
    for (i in 0 until 6) {
        val a = Math.toRadians(90.0 + i * 60.0)
        drawLine(
            Color(0xFF5A5E65), c,
            Offset(cx + (28 * Math.cos(a)).toFloat(), 196f - (28 * Math.sin(a)).toFloat()),
            strokeWidth = 3f, cap = StrokeCap.Round,
        )
    }
    drawCircle(Color(0xFF8A8F96), 6f, c)
}

enum class CarView { SIDE, DRIVE, BRAKE, TOP, REAR }

/**
 * Paint colours BYD sells the Shark 6 in (names differ by market). The photos are Deep Sea Blue. For any other colour only
 * the paint pixels (blue, reasonably saturated) are recoloured, keeping the photo's own light and shade: [base] is the
 * colour, [lo] and [hi] scale it from the shadows to the highlights. Glass, trim, tyres and lamps are left untouched.
 */
class CarPaint(val id: String, val name: String, val market: String, val swatch: Color, val base: Color, val lo: Float, val hi: Float)

val CarPaints = listOf(
    CarPaint("deep_sea_blue", "Deep Sea Blue", "Australia", Color(0xFF2F4A6E), Color(0xFF2F4A6E), 1f, 1f),
    CarPaint("great_white", "Great White", "Australia", Color(0xFFF2F2F0), Color(0xFFF5F5F3), 0.80f, 1.03f),
    CarPaint("arctic_white", "Arctic White", "Philippines / Pakistan", Color(0xFFFFFFFF), Color(0xFFFFFFFF), 0.84f, 1.05f),
    CarPaint("mist_grey", "Mist Grey", "Australia", Color(0xFFB4B8BC), Color(0xFFB9BDC1), 0.62f, 1.14f),
    CarPaint("atlantis_grey", "Atlantis Grey", "Philippines / Pakistan", Color(0xFF6E7378), Color(0xFF72777C), 0.38f, 1.25f),
    CarPaint("tidal_black", "Tidal Black", "Australia", Color(0xFF1A1B1D), Color(0xFF1B1C1F), 0.45f, 1.9f),
    CarPaint("cosmos_black", "Cosmos Black", "Philippines / Pakistan", Color(0xFF0E0F10), Color(0xFF111214), 0.45f, 2.0f),
    CarPaint("outback_orange", "Outback Orange", "Australia", Color(0xFFD9692A), Color(0xFFDD6A26), 0.32f, 1.12f),
)

private val paintCache = HashMap<String, android.graphics.Bitmap>()

/** Recolours the blue paint in [src]. Pure per-pixel work, so call it off the main thread. */
private fun recolourPaint(src: android.graphics.Bitmap, p: CarPaint): android.graphics.Bitmap {
    val w = src.width; val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val hsv = FloatArray(3)
    val weight = FloatArray(px.size)
    val tint = FloatArray(px.size)   // any blue cast, to be neutralised where it is not paint
    val lum = FloatArray(px.size)
    val hist = IntArray(256)
    var count = 0
    // each photo has its own paint saturation, so measure it first: the median of clearly bright blue pixels
    val sh = IntArray(101); var sn = 0
    for (c in px) {
        if ((c ushr 24) < 200) continue
        android.graphics.Color.colorToHSV(c, hsv)
        if (hsv[0] in 190f..255f && hsv[2] > 0.3f && hsv[1] > 0.1f) { sh[(hsv[1] * 100f).toInt().coerceIn(0, 100)]++; sn++ }
    }
    if (sn == 0) return src
    var acc0 = 0; var med = 0.3f
    for (k in 0..100) { acc0 += sh[k]; if (acc0 >= sn / 2) { med = k / 100f; break } }
    val s0 = (med * 0.55f).coerceIn(0.10f, 0.30f)
    for (i in px.indices) {
        val c = px[i]
        if ((c ushr 24) < 200) continue
        android.graphics.Color.colorToHSV(c, hsv)
        // blue paint: hue about 195-250 degrees, clearly coloured, not black
        val hueOk = hsv[0] in 190f..255f
        val s = hsv[1]
        val v = hsv[2]
        if (hueOk) tint[i] = ((s - 0.08f) / 0.16f).coerceIn(0f, 1f)
        val m = if (!hueOk) 0f else (((s - s0) / 0.14f).coerceIn(0f, 1f) * ((v - 0.20f) / 0.12f).coerceIn(0f, 1f))
        if (m <= 0f) continue
        weight[i] = m
        val y = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f
        lum[i] = y
        if (m > 0.5f) { hist[(y * 255f).toInt().coerceIn(0, 255)]++; count++ }
    }
    if (count == 0) return src
    // soften the mask edge (3x3 box) so glass and trim borders do not look cut out
    val sm = FloatArray(weight.size)
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        if ((px[i] ushr 24) < 200) continue
        var t = 0f
        for (dy in -1..1) for (dx in -1..1) t += weight[i + dy * w + dx]
        sm[i] = if (weight[i] > 0f || t > 0f) minOf(weight[i], t / 9f * 1.6f) else 0f
    }
    System.arraycopy(sm, 0, weight, 0, weight.size)
    // brightness range of the paint (3rd to 97th percentile), so shade maps cleanly onto lo..hi
    fun pct(q: Float): Float { var acc = 0; val target = (count * q).toInt(); for (k in 0..255) { acc += hist[k]; if (acc >= target) return k / 255f }; return 1f }
    val y0 = pct(0.03f); val y1 = maxOf(pct(0.95f), y0 + 0.05f)
    val br = p.base.red; val bg = p.base.green; val bb = p.base.blue
    for (i in px.indices) {
        val m = weight[i]
        val c = px[i]
        if (m <= 0f && tint[i] <= 0f) continue
        if (m < 1f && tint[i] > 0f) {
            // leftover blue (shaded paint edges, glass): pull it towards grey of the same brightness
            val k = tint[i] * (1f - m)
            val yy = ((c shr 16) and 255) * 0.299f + ((c shr 8) and 255) * 0.587f + (c and 255) * 0.114f
            val nr0 = (((c shr 16) and 255) + (yy - ((c shr 16) and 255)) * k).toInt().coerceIn(0, 255)
            val ng0 = (((c shr 8) and 255) + (yy - ((c shr 8) and 255)) * k).toInt().coerceIn(0, 255)
            val nb0 = ((c and 255) + (yy - (c and 255)) * k).toInt().coerceIn(0, 255)
            px[i] = (c and 0xFF000000.toInt()) or (nr0 shl 16) or (ng0 shl 8) or nb0
        }
        if (m <= 0f) continue
        val t = ((lum[i] - y0) / (y1 - y0)).coerceIn(0f, 1.15f)
        val k = p.lo + (p.hi - p.lo) * t
        val r = (br * k).coerceIn(0f, 1f); val g = (bg * k).coerceIn(0f, 1f); val b = (bb * k).coerceIn(0f, 1f)
        val c2 = px[i]
        val or = ((c2 shr 16) and 255) / 255f; val og = ((c2 shr 8) and 255) / 255f; val ob = (c2 and 255) / 255f
        val nr = or + (r - or) * m; val ng = og + (g - og) * m; val nb = ob + (b - ob) * m
        px[i] = (c and 0xFF000000.toInt()) or ((nr * 255f + 0.5f).toInt() shl 16) or ((ng * 255f + 0.5f).toInt() shl 8) or (nb * 255f + 0.5f).toInt()
    }
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

/** The drawable [resId] painted in the chosen colour (the original blue photo while the colour is being made, or for blue). */
@Composable
fun paintedCar(resId: Int, paintId: String?): androidx.compose.ui.graphics.painter.Painter {
    val ctx = LocalContext.current
    val p = CarPaints.firstOrNull { it.id == paintId }
    val original = painterResource(resId)
    if (p == null || p.id == "deep_sea_blue") return original
    val key = resId.toString() + "/" + p.id
    val bmp by androidx.compose.runtime.produceState<ImageBitmap?>(paintCache[key]?.asImageBitmap(), key) {
        if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val opts = android.graphics.BitmapFactory.Options().apply { inScaled = false; inMutable = false }
            val src = android.graphics.BitmapFactory.decodeResource(ctx.resources, resId, opts)
            val out = recolourPaint(src, p)
            paintCache[key] = out
            out.asImageBitmap()
        }
    }
    return bmp?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it) } ?: original
}

/**
 * High-detail rear view of the BYD Shark 6 dual-cab ute (drawn in a 640×300 box).
 * Accurately models the full-width LED light bar, C-shaped vertical tail lamps,
 * embossed BYD tailgate, bumper corner steps, mudflaps, sports bar, and 3rd brake light.
 * Supports active lights (tail light bar), braking (neon bright red), and turn indicators (amber blink).
 */
@Composable
fun Shark6RearArt(
    modifier: Modifier = Modifier,
    tailLightsOn: Boolean = false,
    braking: Boolean = false,
    leftIndicator: Boolean = false,
    rightIndicator: Boolean = false,
    blinkAlpha: Float = 1f,
) {
    Canvas(modifier) {
        val s = min(size.width / 640f, size.height / 300f)
        val dx = (size.width - 640f * s) / 2f
        val dy = (size.height - 300f * s) / 2f
        withTransform({
            translate(dx, dy)
            scale(s, s, Offset.Zero)
        }) {
            drawRearShark(tailLightsOn, braking, leftIndicator, rightIndicator, blinkAlpha)
        }
    }
}

private fun DrawScope.drawRearShark(
    tailLightsOn: Boolean,
    braking: Boolean,
    leftIndicator: Boolean,
    rightIndicator: Boolean,
    blinkAlpha: Float,
) {
    val dark = Color(0xFF141518)
    // Floor contact shadow & soft underbody glow
    drawOval(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent), center = Offset(320f, 275f), radius = 340f),
        topLeft = Offset(10f, 250f), size = Size(620f, 45f),
    )
    drawOval(Color.Black.copy(alpha = 0.65f), topLeft = Offset(80f, 268f), size = Size(480f, 16f))

    // Rear tires (wide rugged all-terrain ute tires)
    drawRoundRect(Color(0xFF16181B), Offset(100f, 172f), Size(76f, 104f), CornerRadius(16f))
    drawRoundRect(Color(0xFF26292E), Offset(112f, 180f), Size(52f, 92f), CornerRadius(10f), style = Stroke(3f))
    drawRoundRect(Color(0xFF16181B), Offset(464f, 172f), Size(76f, 104f), CornerRadius(16f))
    drawRoundRect(Color(0xFF26292E), Offset(476f, 180f), Size(52f, 92f), CornerRadius(10f), style = Stroke(3f))

    // Rear mudflaps
    drawRoundRect(Color(0xFF101113), Offset(98f, 210f), Size(26f, 64f), CornerRadius(4f))
    drawRoundRect(Color(0xFF101113), Offset(516f, 210f), Size(26f, 64f), CornerRadius(4f))

    // Rear axle differential / bash plate
    drawRoundRect(Color(0xFF1A1C1F), Offset(270f, 240f), Size(100f, 30f), CornerRadius(8f))
    drawRoundRect(Color(0xFF383B41), Offset(286f, 245f), Size(68f, 18f), CornerRadius(4f))

    // Roof & Cabin Glass
    drawRoundRect(Color(0xFF1A1C1E), Offset(195f, 32f), Size(50f, 6f), CornerRadius(3f))
    drawRoundRect(Color(0xFF1A1C1E), Offset(395f, 32f), Size(50f, 6f), CornerRadius(3f))
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF5A5E66), Color(0xFF2E3137)), startY = 36f, endY = 100f),
        Offset(168f, 36f), Size(304f, 66f), CornerRadius(16f),
    )
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF1B1D21), Color(0xFF090A0C)), startY = 46f, endY = 96f),
        Offset(184f, 46f), Size(272f, 48f), CornerRadius(8f),
    )

    // Sports bar side pillars
    drawLine(Color(0xFF222428), Offset(164f, 98f), Offset(180f, 48f), strokeWidth = 14f, cap = StrokeCap.Round)
    drawLine(Color(0xFF222428), Offset(476f, 98f), Offset(460f, 48f), strokeWidth = 14f, cap = StrokeCap.Round)

    // 3rd High Mount Brake Light (center top of cabin)
    val thirdBrakeColor = if (braking) Color(0xFFFF2424) else Color(0xFF451010)
    drawRoundRect(thirdBrakeColor, Offset(288f, 40f), Size(64f, 6f), CornerRadius(3f))
    if (braking) {
        drawOval(
            Brush.radialGradient(listOf(Color(0xFFFF2424).copy(alpha = 0.55f), Color.Transparent), center = Offset(320f, 43f), radius = 28f),
            topLeft = Offset(264f, 22f), size = Size(112f, 42f),
        )
    }

    // Rear bumper (rugged dual-tone with corner steps)
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF383C42), Color(0xFF1A1C1F)), startY = 196f, endY = 246f),
        Offset(108f, 196f), Size(424f, 50f), CornerRadius(10f),
    )
    drawRoundRect(dark, Offset(118f, 202f), Size(36f, 38f), CornerRadius(6f))
    drawRoundRect(dark, Offset(486f, 202f), Size(36f, 38f), CornerRadius(6f))
    // License plate recess
    drawRoundRect(Color(0xFF0E0F12), Offset(260f, 206f), Size(120f, 30f), CornerRadius(4f))
    drawRoundRect(Color(0xFF282B30), Offset(264f, 210f), Size(112f, 22f), CornerRadius(3f))
    // Parking sensors
    listOf(170f, 230f, 410f, 470f).forEach { x -> drawCircle(Color(0xFF121316), 4f, Offset(x, 220f)) }

    // Tailgate Body Panel
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF6B7077), Color(0xFF43474E), Color(0xFF26282C)), startY = 96f, endY = 200f),
        Offset(116f, 96f), Size(408f, 104f), CornerRadius(10f),
    )
    // Embossed center "BYD" badge
    drawRoundRect(Color(0xFF2C2F34), Offset(282f, 145f), Size(76f, 24f), CornerRadius(5f))
    // "BYD" text lines
    drawLine(Color(0xFFB5BAC1), Offset(296f, 150f), Offset(296f, 164f), strokeWidth = 3f)
    drawLine(Color(0xFFB5BAC1), Offset(296f, 150f), Offset(306f, 153f), strokeWidth = 3f)
    drawLine(Color(0xFFB5BAC1), Offset(296f, 157f), Offset(306f, 157f), strokeWidth = 3f)
    drawLine(Color(0xFFB5BAC1), Offset(296f, 164f), Offset(306f, 161f), strokeWidth = 3f)
    drawLine(Color(0xFFB5BAC1), Offset(314f, 150f), Offset(320f, 157f), strokeWidth = 3f)
    drawLine(Color(0xFFB5BAC1), Offset(326f, 150f), Offset(320f, 157f), strokeWidth = 3f)
    drawLine(Color(0xFFB5BAC1), Offset(320f, 157f), Offset(320f, 164f), strokeWidth = 3f)
    drawLine(Color(0xFFB5BAC1), Offset(334f, 150f), Offset(334f, 164f), strokeWidth = 3f)
    drawLine(Color(0xFFB5BAC1), Offset(334f, 150f), Offset(344f, 157f), strokeWidth = 3f)
    drawLine(Color(0xFFB5BAC1), Offset(344f, 157f), Offset(334f, 164f), strokeWidth = 3f)

    // Tailgate lower handle & latch line
    drawLine(Color(0xFF1E2023), Offset(124f, 186f), Offset(516f, 186f), strokeWidth = 1.8f)

    // Tail lamp housings (base unlit)
    drawRoundRect(Color(0xFF260606), Offset(120f, 108f), Size(24f, 78f), CornerRadius(6f))
    drawRoundRect(Color(0xFF260606), Offset(496f, 108f), Size(24f, 78f), CornerRadius(6f))
    drawRoundRect(Color(0xFF260606), Offset(132f, 112f), Size(376f, 12f), CornerRadius(4f))

    // Full-width BYD Continuous LED Light Bar & Vertical Tail Lamps
    val illuminated = tailLightsOn || braking
    if (illuminated) {
        val barColor = if (braking) Color(0xFFFF2424) else Color(0xFFE50914)
        // Red bloom behind light bar
        val bloomAlpha = if (braking) 0.55f else 0.25f
        drawRoundRect(
            Brush.verticalGradient(listOf(barColor.copy(alpha = bloomAlpha), Color.Transparent)),
            Offset(112f, 96f), Size(416f, 42f), CornerRadius(10f),
        )
        // Horizontal light bar
        drawRoundRect(barColor, Offset(132f, 113f), Size(376f, 10f), CornerRadius(4f))
        // Vertical C-shaped corner lamps
        drawRoundRect(barColor, Offset(122f, 110f), Size(20f, 74f), CornerRadius(5f))
        drawRoundRect(barColor, Offset(498f, 110f), Size(20f, 74f), CornerRadius(5f))
    } else {
        // Subtle ruby dark core when unlit
        drawRoundRect(Color(0xFF5A1010), Offset(132f, 114f), Size(376f, 8f), CornerRadius(3f))
        drawRoundRect(Color(0xFF5A1010), Offset(123f, 112f), Size(18f, 70f), CornerRadius(4f))
        drawRoundRect(Color(0xFF5A1010), Offset(499f, 112f), Size(18f, 70f), CornerRadius(4f))
    }

    // Dynamic Turn Indicators (Amber Flash)
    if (leftIndicator) {
        val amber = Color(0xFFFFA500).copy(alpha = blinkAlpha)
        drawRoundRect(amber, Offset(124f, 128f), Size(16f, 50f), CornerRadius(4f))
        drawCircle(
            Brush.radialGradient(listOf(amber.copy(alpha = 0.7f * blinkAlpha), Color.Transparent), center = Offset(132f, 153f), radius = 45f),
            45f, Offset(132f, 153f),
        )
    }
    if (rightIndicator) {
        val amber = Color(0xFFFFA500).copy(alpha = blinkAlpha)
        drawRoundRect(amber, Offset(500f, 128f), Size(16f, 50f), CornerRadius(4f))
        drawCircle(
            Brush.radialGradient(listOf(amber.copy(alpha = 0.7f * blinkAlpha), Color.Transparent), center = Offset(508f, 153f), radius = 45f),
            45f, Offset(508f, 153f),
        )
    }
}

/**
 * (Older note, superseded by the block below: the art is now 1358×504; positions here are in 866-wide units.)
 * The photos show the ute's LEFT side with the FRONT FACING LEFT; the padded versions add 40 px on the right only,
 * so these positions are the same in both.
 */
/**
 * Lamp positions on the side photos (shark_side_drive.png / shark_side_brake.png, 1358 × 504 px), given in
 * units of 1/1.568 px so the glow sizes in [drawHeadlights] keep their original scale.
 */
private object Photo {
    const val W = 866f
    const val H = 321.4f
    // Headlight cluster at the front (left end of the photo)
    const val headX = 12.8f
    const val headTop = 130.7f
    const val headBottom = 177.3f
    val headGlow = Offset(26.8f, 162.6f)
    // Front indicator corner (top of the headlight cluster)
    val frontIndicator = Offset(24.2f, 135.2f)
    // Side repeater on the bottom edge of the mirror
    val mirrorRepeater = Offset(271f, 94.4f)
    // Tail lamp at the back (right end of the car)
    const val tailLeft = 803.5f
    const val tailTop = 108.4f
    const val tailRight = 823.3f
    const val tailBottom = 170.3f
    val rearIndicator = Offset(813.7f, 159.4f)
}

/** Keeps [active] true for [holdMs] after it was last seen, so a value that flashes with the bulb still reads as "indicating". */
@Composable
private fun latched(active: Boolean, holdMs: Long = 1500L): Boolean {
    val lastSeen = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val now = System.currentTimeMillis()
    if (active) lastSeen.longValue = now
    val on = active || now - lastSeen.longValue < holdMs
    // Re-check once the hold runs out so the light switches off without waiting for another car reading.
    if (on && !active) {
        androidx.compose.runtime.LaunchedEffect(lastSeen.longValue) {
            kotlinx.coroutines.delay(holdMs)
            lastSeen.longValue = lastSeen.longValue - 1
        }
    }
    return on
}

/**
 * The Shark 6 visual (side view, rear view when driving, or top view).
 * Headlights shine forward from the front lamps (tail lamps glow with them), brake lights come on with the brake
 * pedal, and indicators blink amber on the mirror repeater and the front and rear lamps.
 * A custom photo is shown without light overlays, because its orientation and lamp positions aren't known.
 */
@Composable
fun CarImage(modifier: Modifier = Modifier, view: CarView = CarView.SIDE) {
    prefsTick()
    val paintId = LocalContext.current.container.prefs.carColor
    val car = rememberCar()
    // The car's brake-lamp state (1 = lit, 3 = off), so the picture matches the real brake lights.
    val braking = car.on("light.brakeLamp")
    val lowBeam = car.on("light.lowBeam")
    val highBeam = car.on("light.highBeam")
    val headlights = lowBeam || highBeam
    // 1 = off, 2 = left, 4 = right, 6 = hazards (bit flags); fall back to the bits if a label is missing.
    val turn = car.n("light.turnSignal")?.toInt() ?: 0
    val leftTurn = latched(turn > 1 && turn and 2 != 0)
    val rightTurn = latched(turn > 1 && turn and 4 != 0)

    val infiniteTransition = rememberInfiniteTransition(label = "turnIndicators")
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(380, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "blinkAlpha",
    )

    if (view == CarView.REAR) {
        // Rear photo (background removed). The brake version is the same size with the lamps lit,
        // so it swaps in without the car moving; tail lamps and indicators are drawn on top.
        Box(modifier, contentAlignment = Alignment.Center) {
            Image(
                paintedCar(if (braking) R.drawable.shark_rear_brake else R.drawable.shark_rear, paintId),
                if (braking) "BYD Shark 6, rear view, brake lights on" else "BYD Shark 6, rear view",
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            )
            Canvas(Modifier.fillMaxSize()) {
                val s = min(size.width / RearPhoto.W, size.height / RearPhoto.H)
                val dx = (size.width - RearPhoto.W * s) / 2f
                val dy = (size.height - RearPhoto.H * s) / 2f
                withTransform({
                    translate(dx, dy)
                    scale(s, s, Offset.Zero)
                }) {
                    if (headlights && !braking) drawRearTailLamps()
                    val amber = Color(0xFFFFA500)
                    // Seen from behind, the car's left is on the viewer's left.
                    if (leftTurn) indicatorGlow(RearPhoto.leftIndicator, amber, blinkAlpha, 30f)
                    if (rightTurn) indicatorGlow(RearPhoto.rightIndicator, amber, blinkAlpha, 30f)
                }
            }
        }
        return
    }

    val uri = if (view == CarView.SIDE) LocalContext.current.container.prefs.carImageUri else null
    val bmp = rememberUriBitmap(uri, 1400)

    Box(modifier, contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp, "Your Shark 6", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            return@Box
        }
        if (view == CarView.TOP) {
            Image(paintedCar(R.drawable.shark_top, paintId), "BYD Shark 6, top view", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            return@Box
        }
        // Both side photos have the same size and car position, so the overlays line up and the car doesn't jump
        // when the brake image swaps in.
        Image(
            paintedCar(if (braking) R.drawable.shark_side_brake else R.drawable.shark_side_drive, paintId),
            "BYD Shark 6, side view", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
        )
        Canvas(Modifier.fillMaxSize()) {
            val s = min(size.width / Photo.W, size.height / Photo.H)
            val dx = (size.width - Photo.W * s) / 2f
            val dy = (size.height - Photo.H * s) / 2f
            withTransform({
                translate(dx, dy)
                scale(s, s, Offset.Zero)
            }) {
                if (headlights) drawHeadlights(highBeam, tailsLit = !braking)
                val amber = Color(0xFFFFA500)
                // The photo shows the ute's left side: the left indicator lights the mirror repeater and both lamps;
                // the right indicator shows on the front and rear corners only (the rest is on the far side).
                if (leftTurn) {
                    indicatorGlow(Photo.mirrorRepeater, amber, blinkAlpha, 22f)
                    indicatorGlow(Photo.frontIndicator, amber, blinkAlpha, 26f)
                    indicatorGlow(Photo.rearIndicator, amber, blinkAlpha, 26f)
                }
                if (rightTurn) {
                    indicatorGlow(Photo.frontIndicator, amber, blinkAlpha * 0.7f, 20f)
                    indicatorGlow(Photo.rearIndicator, amber, blinkAlpha * 0.7f, 20f)
                }
            }
        }
    }
}

/** Lamp positions on shark_rear.png / shark_rear_brake.png (908 × 838 px), in units of 1/2.04 px. */
private object RearPhoto {
    const val W = 445f
    const val H = 410.7f
    val leftLamp = Rect(15.7f, 147f, 51f, 231.3f)
    val rightLamp = Rect(392.6f, 146.5f, 428.8f, 231.3f)
    val bar = Rect(55.9f, 145.6f, 388.6f, 155.9f)
    val leftIndicator = Offset(33.3f, 205f)
    val rightIndicator = Offset(410.7f, 205f)
}

/** Tail lamps glowing (dimmer than the brake lights) when the headlights are on. */
private fun DrawScope.drawRearTailLamps() {
    val red = Color(0xFFE50914)
    for (r in listOf(RearPhoto.leftLamp, RearPhoto.rightLamp, RearPhoto.bar)) {
        drawRoundRect(red.copy(alpha = 0.45f), r.topLeft, r.size, CornerRadius(3f))
    }
    for (r in listOf(RearPhoto.leftLamp, RearPhoto.rightLamp)) {
        drawCircle(Brush.radialGradient(listOf(red.copy(alpha = 0.3f), Color.Transparent), center = r.center, radius = 40f), 40f, r.center)
    }
}

/** Headlight glow and beam out of the front lamps (front faces left), plus the tail lamps lit red. */
private fun DrawScope.drawHeadlights(highBeam: Boolean, tailsLit: Boolean) {
    val beamColor = if (highBeam) Color(0xFFD8ECFF) else Color(0xFFF2F7FF)
    val length = if (highBeam) 230f else 140f
    val x0 = Photo.headX
    // Cone of light from the lamp face forward and slightly down (low beam) or level (high beam).
    val drop = if (highBeam) 0f else 14f
    val beam = Path().apply {
        moveTo(x0, Photo.headTop + 18f)
        lineTo(x0 - length, Photo.headTop + 4f + drop)
        lineTo(x0 - length, Photo.headBottom + 26f + drop)
        lineTo(x0, Photo.headBottom - 2f)
        close()
    }
    drawPath(
        beam,
        Brush.horizontalGradient(
            listOf(beamColor.copy(alpha = if (highBeam) 0.55f else 0.38f), Color.Transparent),
            startX = x0, endX = x0 - length,
        ),
    )
    // Bright lamp face
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), beamColor.copy(alpha = 0.35f), Color.Transparent), center = Photo.headGlow, radius = 26f),
        26f, Photo.headGlow,
    )
    if (tailsLit) {
        // Tail lamps on with the headlights (dimmer than the brake light).
        val red = Color(0xFFE50914)
        drawRoundRect(
            red.copy(alpha = 0.55f),
            Offset(Photo.tailLeft + 2f, Photo.tailTop + 2f),
            Size(Photo.tailRight - Photo.tailLeft - 4f, Photo.tailBottom - Photo.tailTop - 4f),
            CornerRadius(4f),
        )
        val c = Offset((Photo.tailLeft + Photo.tailRight) / 2f + 6f, (Photo.tailTop + Photo.tailBottom) / 2f)
        drawCircle(Brush.radialGradient(listOf(red.copy(alpha = 0.35f), Color.Transparent), center = c, radius = 34f), 34f, c)
    }
}

private fun DrawScope.indicatorGlow(at: Offset, amber: Color, alpha: Float, radius: Float) {
    drawCircle(Brush.radialGradient(listOf(amber.copy(alpha = 0.85f * alpha), Color.Transparent), center = at, radius = radius), radius, at)
    drawCircle(amber.copy(alpha = alpha), radius * 0.22f, at)
}

@Composable
fun rememberUriBitmap(uri: String?, maxPx: Int = 1920): ImageBitmap? {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) { runCatching { decode(ctx, Uri.parse(uri), maxPx) }.getOrNull() }
    }
    return bmp
}

private fun decode(ctx: Context, uri: Uri, maxPx: Int): ImageBitmap {
    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
    return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val scale = max(w, h).toFloat() / maxPx
        if (scale > 1f) decoder.setTargetSize((w / scale).toInt(), (h / scale).toInt())
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }.asImageBitmap()
}
