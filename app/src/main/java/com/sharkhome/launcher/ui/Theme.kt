package com.sharkhome.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * Colour tokens from the "SharkOS-CE UI" design system (tokens.css), light and dark.
 * Names match the CSS variables: --surface, --surface-card, --ink, ...
 */
data class Palette(
    val dark: Boolean,
    val surface: Color,
    val surfaceCard: Color,
    val surfacePanel: Color,
    val surfaceRaised: Color,
    val field: Color,
    val line: Color,
    val lineStrong: Color,
    val controlOff: Color,
    /** Fill for off switch tracks, empty gauge arcs and slider tracks (was the line colour). */
    val track: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val inkSubtle: Color,
    val link: Color,
    val ok: Color = Color(0xFF25CB55),
    val alert: Color = Color(0xFFE82127),
    val warn: Color = Color(0xFFFEC322),
    val btnDark: Color = Color(0xFF414141),
    val btnLight: Color = Color(0xFFD0D1D2),
    val btnLightest: Color = Color(0xFFF0F0F0),
    val btnInk: Color = Color(0xFF414141),
    val mapGround: Color,
    val shadow: Color,
) {
    companion object {
        val Light = Palette(
            dark = false,
            surface = Color(0xFFFAFAFA),
            surfaceCard = Color(0xFFF6F6F6),
            surfacePanel = Color(0xFFF0F0F0),
            surfaceRaised = Color(0xFFFFFFFF),
            field = Color(0xFFE6E6E6),
            line = Color(0xFFD0D1D2),
            lineStrong = Color(0xFF8D8D8D),
            controlOff = Color(0xFFD0D2D0),
            track = Color(0xFFD0D1D2),
            ink = Color(0xFF171717),
            inkSecondary = Color(0xFF414141),
            inkMuted = Color(0xFF636363),
            inkSubtle = Color(0xFF767676),
            link = Color(0xFF005AFF),
            mapGround = Color(0xFFF7F7F7),
            shadow = Color(0x14000000),
        )
        /**
         * Dark, minimal, Tesla-like (owner's request 8 Nov; deliberately departs from the kit's grey #414141 cards):
         * near-black ground, slightly lifted cards, dark buttons with white labels, faint seams instead of outlines,
         * one accent colour for "on".
         */
        val Dark = Palette(
            dark = true,
            surface = Color(0xFF0B0B0C),
            surfaceCard = Color(0xFF17181A),
            surfacePanel = Color(0xFF17181A),
            surfaceRaised = Color(0xFF34353A),
            field = Color(0xFF232427),
            line = Color(0x14FFFFFF),
            lineStrong = Color(0x24FFFFFF),
            controlOff = Color(0xFF3A3B40),
            track = Color(0xFF2C2D31),
            ink = Color(0xFFFFFFFF),
            inkSecondary = Color(0xFFD9DADC),
            inkMuted = Color(0xFF8E9096),
            inkSubtle = Color(0xFF8A8C92),
            link = Color(0xFF6EA0FF),
            btnDark = Color(0xFF232427),
            btnLight = Color(0xFF232427),
            btnLightest = Color(0xFF232427),
            btnInk = Color(0xFFFFFFFF),
            mapGround = Color(0xFF141517),
            shadow = Color(0x00000000),
        )
        /**
         * "Shark": the dark theme in deep-sea blue (the Shark's own paint colour). Same structure as [Dark]: near-black
         * navy ground, slightly lifted navy cards, blue-tinted seams, bright blue links.
         */
        val Shark = Palette(
            dark = true,
            surface = Color(0xFF050C18),
            surfaceCard = Color(0xFF0C192C),
            surfacePanel = Color(0xFF0C192C),
            surfaceRaised = Color(0xFF1C3354),
            field = Color(0xFF112440),
            line = Color(0x1F9CC4FF),
            lineStrong = Color(0x339CC4FF),
            controlOff = Color(0xFF2B4163),
            track = Color(0xFF182B47),
            ink = Color(0xFFF2F7FF),
            inkSecondary = Color(0xFFD0DDF0),
            inkMuted = Color(0xFF8CA2C0),
            inkSubtle = Color(0xFF8499B6),
            link = Color(0xFF63AEFF),
            btnDark = Color(0xFF112440),
            btnLight = Color(0xFF112440),
            btnLightest = Color(0xFF112440),
            btnInk = Color(0xFFFFFFFF),
            mapGround = Color(0xFF09152A),
            shadow = Color(0x00000000),
        )
    }
}

/**
 * The current palette. Reading any colour here inside a composable subscribes to theme changes,
 * so switching light/dark recolours every screen. The older names (Bg, Card2, Text2, ...) are kept
 * so screens written before the design system still work; they map onto the tokens.
 */
object C {
    var palette by mutableStateOf(Palette.Light)

    val P: Palette get() = palette

    // Design-system names
    val Surface get() = palette.surface
    val SurfaceCard get() = palette.surfaceCard
    val SurfacePanel get() = palette.surfacePanel
    val SurfaceRaised get() = palette.surfaceRaised
    val Field get() = palette.field
    val LineStrong get() = palette.lineStrong
    val ControlOff get() = palette.controlOff
    val Ink get() = palette.ink
    val InkSecondary get() = palette.inkSecondary
    val InkMuted get() = palette.inkMuted
    val InkSubtle get() = palette.inkSubtle
    val Link get() = palette.link
    val Ok get() = palette.ok
    val Alert get() = palette.alert
    val Warn get() = palette.warn
    val BtnDark get() = palette.btnDark
    /** Label colour on an accent fill: white on dark accents, near-black on light ones (Orange/Teal/Green/Violet), whichever reads better. */
    val OnAccent: Color @Composable get() = if (LocalAccent.current.luminance() > 0.22f) Color(0xFF111111) else Color.White
    val Shadow get() = palette.shadow

    // Older names, mapped onto the tokens
    val Bg get() = palette.surface
    val Panel get() = palette.surfacePanel
    val Card get() = palette.surfaceCard
    val Card2 get() = palette.surfacePanel
    val Raised get() = palette.track
    val Pressed get() = palette.surfaceRaised
    val Seg get() = palette.surfacePanel
    val Bar get() = palette.surface
    val Line get() = palette.line
    val Text get() = palette.ink
    val Text2 get() = palette.inkMuted
    val Text3 get() = palette.inkSecondary
    val Green get() = palette.ok
    val Amber get() = palette.warn
    val Red get() = palette.alert
}

const val DEFAULT_ACCENT = 0xFF005AFFL

val LocalAccent = staticCompositionLocalOf { Color(DEFAULT_ACCENT) }

val AccentChoices = listOf(
    0xFF005AFFL to "Blue",
    0xFF2BB3A3L to "Teal",
    0xFFE8833AL to "Orange",
    0xFFE5484DL to "Red",
    0xFF34C759L to "Green",
    0xFFA78BFAL to "Violet",
)

@Composable
fun SharkTheme(accent: Color, dark: Boolean, shark: Boolean = false, content: @Composable () -> Unit) {
    val p = if (shark) Palette.Shark else if (dark) Palette.Dark else Palette.Light
    SideEffect { if (C.palette != p) C.palette = p }
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(
            colorScheme = if (dark) {
                darkColorScheme(primary = accent, background = p.surface, surface = p.surfaceCard, onSurface = p.ink, onBackground = p.ink, surfaceVariant = p.surfacePanel)
            } else {
                lightColorScheme(primary = accent, background = p.surface, surface = p.surfaceCard, onSurface = p.ink, onBackground = p.ink, surfaceVariant = p.surfacePanel)
            },
            content = content,
        )
    }
}

/**
 * Lays the UI out on a design canvas at least 1920 wide and [MIN_HEIGHT] tall, scaled to the real screen,
 * so sizes in the code match the design file whatever the head unit's density. BYD's own status bar and
 * dock leave the app a short, wide window, so when the window is shorter than that the canvas grows
 * wider instead (the flexible columns take the extra width) rather than squashing the cards.
 */
@Composable
fun DesignCanvas(content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(C.Bg)) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        // Scale by height only: in BYD's split screen (window narrower than 1920) text and buttons keep their full
        // size and the home screen reflows into fewer columns, instead of the whole UI shrinking.
        val fullWidth = wPx / hPx >= (1920f / MIN_HEIGHT) * 0.9f
        val scale = if (fullWidth) minOf(wPx / 1920f, hPx / MIN_HEIGHT) else hPx / MIN_HEIGHT
        CompositionLocalProvider(LocalDensity provides Density(scale, 1f)) {
            Box(Modifier.size((wPx / scale).dp, (hPx / scale).dp), content = content)
        }
    }
}

/** Smallest canvas height the home screen is laid out for (status bar 80 + cards + margin). */
private const val MIN_HEIGHT = 1060f
