package com.sharkhome.launcher.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything the instrument-cluster widgets show, gathered from the car readings and the motion sensors. */
data class ClusterData(
    val speedKmh: Double? = null,
    val gear: String? = null,
    val powerKw: Double? = null,
    val batteryPct: Double? = null,
    val evRangeKm: Double? = null,
    val fuelPct: Double? = null,
    val fuelRangeKm: Double? = null,
    val totalRangeKm: Double? = null,
    val tyresPsi: List<Double?> = listOf(null, null, null, null),   // FL, FR, RL, RR
    val pitchDeg: Float? = null,
    val rollDeg: Float? = null,
    val tripKm: Double? = null,
    val tripMin: Double? = null,
    val odometerKm: Double? = null,
    val volt12: Double? = null,
    val oilPct: Double? = null,
    val outsideC: Double? = null,
) {
    companion object {
        fun from(r: Map<String, Reading>, gear: String?, motion: com.sharkhome.launcher.sensors.Motion?): ClusterData {
            fun n(k: String) = r[k]?.value
            return ClusterData(
                speedKmh = n("vehicle.speedKmh"), gear = gear, powerKw = n("vehicle.powerKw"),
                batteryPct = n("vehicle.batteryPct"), evRangeKm = n("vehicle.evRangeKm"),
                fuelPct = n("vehicle.fuelPct"), fuelRangeKm = n("vehicle.fuelRangeKm"), totalRangeKm = n("vehicle.rangeKm"),
                tyresPsi = listOf(n("tyre.fl"), n("tyre.fr"), n("tyre.rl"), n("tyre.rr")),
                pitchDeg = motion?.pitch.takeIf { motion?.tiltLearned == true }, rollDeg = motion?.roll.takeIf { motion?.tiltLearned == true },
                tripKm = n("trip.distance"), tripMin = n("trip.time"), odometerKm = n("vehicle.odometerKm"),
                volt12 = n("battery.voltage"), oilPct = n("health.oilLifetime"), outsideC = n("climate.outsideTemp"),
            )
        }
    }
}

/**
 * Draws the cluster widgets for the 1920x720 second screen. One canvas, no child views: each widget is a card drawn
 * in a fixed 460 x 620 design box that is scaled to fit, so any number of widgets (1 to 8) stays readable.
 */
class ClusterView(ctx: Context) : View(ctx) {
    var widgets: List<ClusterWidget> = emptyList()
        set(v) { field = v; invalidate() }
    var data: ClusterData = ClusterData()
        set(v) { field = v; invalidate() }

    private val BG = Color.parseColor("#07090D")
    private val CARD_TOP = Color.parseColor("#161C25")
    private val CARD_BOTTOM = Color.parseColor("#0F141B")
    private val EDGE = Color.parseColor("#252E3B")
    private val INK = Color.parseColor("#F2F5F8")
    private val MUTED = Color.parseColor("#7D8998")
    private val TRACK = Color.parseColor("#232B37")
    private val BLUE = Color.parseColor("#4DA3FF")
    private val GREEN = Color.parseColor("#3DDC97")
    private val AMBER = Color.parseColor("#FFB547")
    private val RED = Color.parseColor("#FF5C5C")

    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, face: Typeface = regular,
                     align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f) {
        p.reset(); p.isAntiAlias = true
        p.color = color; p.textSize = size; p.typeface = face; p.textAlign = align; p.letterSpacing = spacing
        c.drawText(s, x, y, p)
    }

    private fun textWidth(s: String, size: Float, face: Typeface): Float {
        p.reset(); p.isAntiAlias = true; p.textSize = size; p.typeface = face
        return p.measureText(s)
    }

    /** A big number with a smaller unit right after it. */
    private fun hero(c: Canvas, value: String, unit: String, x: Float, baseline: Float, size: Float, color: Int = INK) {
        text(c, value, x, baseline, size, color, light)
        text(c, unit, x + textWidth(value, size, light) + 12f, baseline, size * 0.26f, MUTED, regular)
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, h: Float, frac: Float, color: Int) {
        p.reset(); p.isAntiAlias = true
        p.color = TRACK; rect.set(x, y, x + w, y + h); c.drawRoundRect(rect, h / 2, h / 2, p)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0f) { p.color = color; rect.set(x, y, x + w * f, y + h); c.drawRoundRect(rect, h / 2, h / 2, p) }
    }

    private fun row(c: Canvas, label: String, value: String, y: Float, valueColor: Int = INK) {
        text(c, label, 36f, y, 30f, MUTED)
        text(c, value, 424f, y, 34f, valueColor, medium, Paint.Align.RIGHT)
    }

    private fun f0(v: Double?, unit: String = "") = v?.let { "${it.roundToInt()}$unit" } ?: "--"
    private fun f1(v: Double?, unit: String = "") = v?.let { "%.1f%s".format(it, unit) } ?: "--"

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BG)
        val list = widgets
        val padX = 40f; val padY = 36f; val gap = 28f
        if (list.isEmpty()) {
            text(canvas, "SharkOS-CE", width / 2f, height / 2f, 64f, INK, light, Paint.Align.CENTER)
            text(canvas, "Pick cluster widgets in Settings › Test", width / 2f, height / 2f + 56f, 30f, MUTED, regular, Paint.Align.CENTER)
            return
        }
        val cardW = (width - 2 * padX - gap * (list.size - 1)) / list.size
        val cardH = height - 2 * padY
        val scale = minOf(cardW / DESIGN_W, cardH / DESIGN_H)
        list.forEachIndexed { i, w ->
            val x = padX + i * (cardW + gap)
            // card
            rect.set(x, padY, x + cardW, padY + cardH)
            p.reset(); p.isAntiAlias = true
            p.shader = LinearGradient(0f, padY, 0f, padY + cardH, CARD_TOP, CARD_BOTTOM, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, 32f, 32f, p)
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = EDGE
            canvas.drawRoundRect(rect, 32f, 32f, p)
            // content, centred in the design box
            canvas.save()
            canvas.translate(x + (cardW - DESIGN_W * scale) / 2f, padY + (cardH - DESIGN_H * scale) / 2f)
            canvas.scale(scale, scale)
            drawWidget(canvas, w)
            canvas.restore()
        }
    }

    private fun title(c: Canvas, s: String, color: Int = BLUE) = text(c, s, 36f, 70f, 26f, color, medium, spacing = 0.14f)

    private fun drawWidget(c: Canvas, w: ClusterWidget) {
        val d = data
        when (w) {
            ClusterWidget.SPEED -> {
                title(c, "SPEED")
                val s = d.speedKmh?.roundToInt()
                text(c, s?.toString() ?: "--", 230f, 330f, 230f, INK, light, Paint.Align.CENTER)
                text(c, "km/h", 230f, 392f, 40f, MUTED, regular, Paint.Align.CENTER)
                val gears = listOf("P", "R", "N", "D")
                gears.forEachIndexed { i, g ->
                    val cx = 80f + i * 100f
                    val on = d.gear == g
                    p.reset(); p.isAntiAlias = true
                    p.color = if (on) BLUE else TRACK
                    c.drawCircle(cx, 520f, 38f, p)
                    text(c, g, cx, 534f, 42f, if (on) Color.BLACK else MUTED, medium, Paint.Align.CENTER)
                }
            }
            ClusterWidget.POWER -> {
                title(c, "POWER")
                val kw = d.powerKw
                val regen = (kw ?: 0.0) < 0
                hero(c, if (kw == null) "--" else abs(kw).roundToInt().toString(), "kW", 36f, 270f, 150f, if (regen) GREEN else INK)
                text(c, if (kw == null) "" else if (regen) "Regenerating" else if (kw > 1) "Driving" else "Idle", 36f, 330f, 32f, if (regen) GREEN else MUTED, medium)
                // bar centred on zero: regen to the left, drive to the right
                val x = 36f; val wBar = 388f; val y = 430f; val h = 22f
                val min = -60.0; val max = 180.0
                bar(c, x, y, wBar, h, 0f, BLUE)
                val zero = x + wBar * (-min / (max - min)).toFloat()
                val vPos = x + wBar * (((kw ?: 0.0).coerceIn(min, max) - min) / (max - min)).toFloat()
                p.reset(); p.isAntiAlias = true; p.color = if (regen) GREEN else BLUE
                rect.set(minOf(zero, vPos), y, maxOf(zero, vPos), y + h); c.drawRect(rect, p)
                p.color = INK; c.drawRect(zero - 2f, y - 8f, zero + 2f, y + h + 8f, p)
                text(c, "Regen", x, y + 66f, 26f, MUTED)
                text(c, "180 kW", x + wBar, y + 66f, 26f, MUTED, regular, Paint.Align.RIGHT)
            }
            ClusterWidget.BATTERY -> {
                title(c, "BATTERY & RANGE")
                val soc = d.batteryPct
                hero(c, f0(soc), "%", 36f, 250f, 150f, if ((soc ?: 100.0) < 15) RED else INK)
                bar(c, 36f, 296f, 388f, 22f, ((soc ?: 0.0) / 100).toFloat(), if ((soc ?: 100.0) < 15) RED else GREEN)
                row(c, "Electric range", f0(d.evRangeKm, " km"), 400f)
                row(c, "Fuel", if (d.fuelPct == null) "--" else "${d.fuelPct.roundToInt()}%  ·  ${f0(d.fuelRangeKm, " km")}", 456f)
                row(c, "Total range", f0(d.totalRangeKm, " km"), 512f, BLUE)
            }
            ClusterWidget.TYRES -> {
                title(c, "TYRES  ·  PSI")
                // top-down truck outline
                p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = Color.parseColor("#3A4556")
                rect.set(165f, 170f, 295f, 520f); c.drawRoundRect(rect, 44f, 44f, p)
                p.style = Paint.Style.FILL; p.color = Color.parseColor("#2A3340")
                for ((wx, wy) in listOf(140f to 215f, 296f to 215f, 140f to 410f, 296f to 410f)) {
                    rect.set(wx, wy, wx + 24f, wy + 78f); c.drawRoundRect(rect, 8f, 8f, p)
                }
                val spots = listOf(
                    Triple(112f, 255f, Paint.Align.CENTER), Triple(348f, 255f, Paint.Align.CENTER),
                    Triple(112f, 455f, Paint.Align.CENTER), Triple(348f, 455f, Paint.Align.CENTER),
                )
                d.tyresPsi.forEachIndexed { i, v ->
                    val col = when { v == null -> MUTED; v < 28 -> RED; v < 32 -> AMBER; else -> INK }
                    val (sx, sy, al) = spots[i]
                    text(c, if (v == null) "--" else "%.0f".format(v), sx, sy, 58f, col, medium, al)
                }
                text(c, "FRONT", 230f, 150f, 22f, MUTED, regular, Paint.Align.CENTER, 0.12f)
            }
            ClusterWidget.TILT -> {
                title(c, "ATTITUDE")
                val cx = 230f; val cy = 300f; val r = 150f
                val pitch = d.pitchDeg; val roll = d.rollDeg
                c.save()
                path.reset(); path.addCircle(cx, cy, r, Path.Direction.CW); c.clipPath(path)
                c.rotate(-(roll ?: 0f), cx, cy)
                val horizon = cy + (pitch ?: 0f) * 5f
                p.reset(); p.color = Color.parseColor("#1D3B5E"); c.drawRect(cx - 400f, cy - 400f, cx + 400f, horizon, p)
                p.color = Color.parseColor("#4B3522"); c.drawRect(cx - 400f, horizon, cx + 400f, cy + 400f, p)
                p.color = INK; p.strokeWidth = 4f; c.drawLine(cx - 400f, horizon, cx + 400f, horizon, p)
                c.restore()
                p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = EDGE
                c.drawCircle(cx, cy, r, p)
                p.color = AMBER; p.strokeWidth = 7f
                c.drawLine(cx - 70f, cy, cx - 20f, cy, p); c.drawLine(cx + 20f, cy, cx + 70f, cy, p)
                p.style = Paint.Style.FILL; c.drawCircle(cx, cy, 6f, p)
                if (pitch == null) text(c, "Learning level…", cx, cy + r + 56f, 30f, MUTED, regular, Paint.Align.CENTER)
                else {
                    text(c, "Pitch %+.0f°".format(pitch), 110f, 530f, 36f, INK, medium, Paint.Align.CENTER)
                    text(c, "Roll %+.0f°".format(roll ?: 0f), 350f, 530f, 36f, INK, medium, Paint.Align.CENTER)
                }
            }
            ClusterWidget.TRIP -> {
                title(c, "TRIP")
                hero(c, f1(d.tripKm), "km", 36f, 250f, 150f)
                row(c, "Time", d.tripMin?.let { m -> if (m >= 60) "%d h %02d min".format((m / 60).toInt(), (m % 60).toInt()) else "${m.roundToInt()} min" } ?: "--", 400f)
                row(c, "Odometer", d.odometerKm?.let { "%,d km".format(it.roundToInt()) } ?: "--", 456f)
                row(c, "Outside", f0(d.outsideC, "°C"), 512f)
            }
            ClusterWidget.HEALTH -> {
                title(c, "12V & OIL")
                val v = d.volt12
                hero(c, f1(v), "V", 36f, 250f, 150f, if (v != null && v < 11.8) RED else INK)
                text(c, "12 V battery", 36f, 310f, 30f, MUTED)
                row(c, "Engine oil life", d.oilPct?.takeIf { it in 0.0..100.0 }?.let { f0(it, "%") } ?: "--", 430f)
                row(c, "Outside", f0(d.outsideC, "°C"), 486f)
            }
            ClusterWidget.CLOCK -> {
                title(c, "TIME")
                val now = Date()
                text(c, SimpleDateFormat("h:mm", Locale.getDefault()).format(now), 230f, 330f, 200f, INK, light, Paint.Align.CENTER)
                text(c, SimpleDateFormat("a", Locale.getDefault()).format(now).uppercase(), 230f, 392f, 40f, MUTED, regular, Paint.Align.CENTER)
                text(c, SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now), 230f, 500f, 36f, INK, regular, Paint.Align.CENTER)
            }
        }
    }

    companion object {
        private const val DESIGN_W = 460f
        private const val DESIGN_H = 620f
    }
}
