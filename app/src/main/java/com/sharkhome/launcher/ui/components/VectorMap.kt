package com.sharkhome.launcher.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sharkhome.launcher.container
import com.sharkhome.launcher.ui.C
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import androidx.compose.ui.graphics.toArgb
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

// Stadia Maps Alidade Smooth (light / dark) when a key is set in local.properties, else OpenFreeMap (no key needed).
private val STADIA_KEY = com.sharkhome.launcher.BuildConfig.STADIA_KEY
private val USE_STADIA = STADIA_KEY.isNotBlank()
private val STYLE_LIGHT =
    if (USE_STADIA) "https://tiles.stadiamaps.com/styles/alidade_smooth.json?api_key=$STADIA_KEY"
    else "https://tiles.openfreemap.org/styles/positron"
private val STYLE_DARK =
    if (USE_STADIA) "https://tiles.stadiamaps.com/styles/alidade_smooth_dark.json?api_key=$STADIA_KEY"
    else "https://tiles.openfreemap.org/styles/dark"
private val CREDIT =
    if (USE_STADIA) "© Stadia Maps © OpenMapTiles © OpenStreetMap"
    else "OpenFreeMap © OpenMapTiles · Data from OpenStreetMap"

/**
 * Vector map (MapLibre + OpenFreeMap, no account or key): clean Positron style in the light theme, Dark style at night,
 * following the car's GPS position. Sharp at any size because it's drawn from vector data, not stretched images.
 */
@Composable
fun VectorMap(modifier: Modifier = Modifier, zoom: Double = 14.0) {
    val ctx = LocalContext.current
    val styleUrl = if (C.P.dark) STYLE_DARK else STYLE_LIGHT
    val accent = com.sharkhome.launcher.ui.LocalAccent.current.toArgb()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val mapView = remember {
        MapLibre.getInstance(ctx)
        // TextureView: unlike the default SurfaceView it follows the app's canvas scaling and clipping.
        MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx).textureMode(true)).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false // shown as text below instead (the card covers the map with a tap target)
            m.cameraPosition = CameraPosition.Builder().zoom(zoom).build()
            map = m
        }
    }

    // Load (or swap, when the theme changes) the style, then turn on the position arrow.
    LaunchedEffect(map, styleUrl) {
        val m = map ?: return@LaunchedEffect
        if (loadedStyle == styleUrl) return@LaunchedEffect
        m.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
            loadedStyle = styleUrl
            enableLocation(ctx, m, style, zoom, accent)
            ctx.container.motion.fix.value?.let { feed(m, it) }
        }
    }

    // The arrow and camera follow the app's own GPS listener (MotionRepository). MapLibre's default location engine
    // left one GPS listener behind every time the app came back to the front (5 stuck after two round trips, seen on
    // the car 20 Nov 2026), and its "better fix" filter can drop fixes; GitHub #8 reported the map not following.
    LaunchedEffect(map) {
        val m = map ?: return@LaunchedEffect
        ctx.container.motion.fix.collect { loc -> if (loc != null) feed(m, loc) }
    }

    Box(modifier) {
        AndroidView({ mapView }, Modifier.fillMaxSize())
        T(
            CREDIT, 10, color = C.InkMuted,
            modifier = Modifier.align(Alignment.BottomStart)
                .background(C.SurfacePanel.copy(alpha = 0.7f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@SuppressLint("MissingPermission")
private fun enableLocation(ctx: Context, map: MapLibreMap, style: Style, zoom: Double, accent: Int) {
    val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return
    // A plain accent-coloured arrow: no white disc, shadow or accuracy halo, and drawn smaller than MapLibre's default.
    val options = LocationComponentOptions.builder(ctx)
        .gpsDrawable(com.sharkhome.launcher.R.drawable.map_arrow)
        .foregroundTintColor(accent)
        .backgroundTintColor(android.graphics.Color.TRANSPARENT)
        .elevation(0f)
        .accuracyAlpha(0f)
        .maxZoomIconScale(0.9f)
        .minZoomIconScale(0.6f)
        .build()
    runCatching {
        val lc = map.locationComponent
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(ctx, style)
                .useDefaultLocationEngine(false)
                .locationComponentOptions(options)
                .build(),
        )
        lc.isLocationComponentEnabled = true
        lc.cameraMode = CameraMode.TRACKING
        lc.renderMode = RenderMode.GPS
        lc.zoomWhileTracking(zoom)
    }.onFailure { Log.e("SharkMap", "location component failed", it) }
}

private var lastMapLog = 0L

/** Moves the position arrow (and the tracking camera) to [loc], once the location component is switched on. */
private fun feed(map: MapLibreMap, loc: android.location.Location) {
    runCatching {
        val lc = map.locationComponent
        if (!lc.isLocationComponentActivated || !lc.isLocationComponentEnabled) return
        lc.forceLocationUpdate(loc)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastMapLog > 10_000L) {
            lastMapLog = now
            // Rounded to about 1 km so the log never holds an exact position.
            Log.e("SharkMap", "fix fed: %.2f,%.2f speed=%.0f km/h bearing=%.0f".format(loc.latitude, loc.longitude, loc.speed * 3.6f, loc.bearing))
        }
    }.onFailure { Log.e("SharkMap", "feed failed", it) }
}
