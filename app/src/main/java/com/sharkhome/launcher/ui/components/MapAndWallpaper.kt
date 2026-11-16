package com.sharkhome.launcher.ui.components

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.sharkhome.launcher.container
import org.osmdroid.config.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

/** Lets the on-screen buttons zoom and re-centre the map. */
class MapHandle {
    var view: MapView? = null
    var location: MyLocationNewOverlay? = null

    fun zoomIn() {
        view?.controller?.zoomIn()
    }

    fun zoomOut() {
        view?.controller?.zoomOut()
    }

    fun recenter() {
        val loc = location ?: return
        loc.enableFollowLocation()
        loc.myLocation?.let { view?.controller?.animateTo(it) }
    }
}

/** Map look: STREET = CARTO Positron / Dark Matter (follows the theme), TOPO = OpenTopoMap (contours, tracks). */
enum class MapStyle { STREET, TOPO }

private val TOPO_SOURCE = XYTileSource(
    "OpenTopoMap", 0, 17, 256, ".png",
    arrayOf("a", "b", "c").map { "https://$it.tile.opentopomap.org/" }.toTypedArray(),
    TOPO_CREDIT,
)

private const val CARTO_CREDIT = "© OpenStreetMap contributors © CARTO"
private const val OSM_CREDIT = "© OpenStreetMap contributors"
private const val TOPO_CREDIT = "© OpenStreetMap contributors, SRTM · © OpenTopoMap (CC-BY-SA)"

/** Map following the car's GPS position. The licence credit sits in the bottom-left corner. */
@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    handle: MapHandle = remember { MapHandle() },
    zoom: Double = 15.0,
    style: MapStyle = MapStyle.STREET,
) {
    val ctx = LocalContext.current
    val dark = com.sharkhome.launcher.ui.C.P.dark
    // CARTO basemaps now need an API key (tiles come back watermarked "API KEY REQUIRED", checked 7 Nov), so the street
    // map stays on standard OpenStreetMap until a replacement is chosen.
    val source = if (style == MapStyle.TOPO) TOPO_SOURCE else org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        }
        MapView(ctx).apply {
            setTileSource(source)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(zoom)
            val me = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
            me.enableMyLocation()
            me.enableFollowLocation()
            overlays.add(me)
            handle.view = this
            handle.location = me
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose {
            map.onPause()
            handle.location?.disableMyLocation()
            map.onDetach()
        }
    }
    Box(modifier) {
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxSize(),
            update = {
                if (it.tileProvider.tileSource.name() != source.name()) it.setTileSource(source)
                // Street map: inverted in the dark theme, as before. Topo map is never inverted (it would be unreadable).
                it.overlayManager.tilesOverlay.setColorFilter(
                    if (dark && style == MapStyle.STREET) org.osmdroid.views.overlay.TilesOverlay.INVERT_COLORS else null,
                )
            },
        )
        T(
            if (style == MapStyle.TOPO) TOPO_CREDIT else OSM_CREDIT, 10, color = com.sharkhome.launcher.ui.C.InkMuted,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                .background(com.sharkhome.launcher.ui.C.SurfacePanel.copy(alpha = 0.7f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

