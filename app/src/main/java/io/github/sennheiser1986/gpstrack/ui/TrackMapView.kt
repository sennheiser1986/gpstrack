package io.github.sennheiser1986.gpstrack.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sennheiser1986.gpstrack.data.LatLon
import io.github.sennheiser1986.gpstrack.map.OfflineMap
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * A point to drop on the map, drawn as a two-tone concentric-circle dot in [color]. The [label]
 * is shown only in the marker's tap info window and in the on-screen legend, never as free text
 * over the map.
 *
 * @property position where to place the dot.
 * @property label human name for this point.
 * @property color the base ARGB colour; the dot is a light disc of this hue around a solid core.
 */
data class MapMarker(
    val position: LatLon,
    val label: String,
    val color: Int,
) {
    companion object {
        /** A marker for this device's own position. */
        fun me(position: LatLon, label: String = "You") =
            MapMarker(position, label, MarkerPalette.ME)

        /** A marker for a watched peer; [colorIndex] is the peer's position among the peers. */
        fun peer(position: LatLon, label: String, colorIndex: Int) =
            MapMarker(position, label, MarkerPalette.peerColor(colorIndex))

        /** A marker for the first fix of a recorded track. */
        fun start(position: LatLon, label: String = "Start") =
            MapMarker(position, label, MarkerPalette.START)

        /** A marker for the last fix of a recorded track. */
        fun end(position: LatLon, label: String = "End") =
            MapMarker(position, label, MarkerPalette.END)
    }
}

/** The colours used for map dots and their legend swatches. */
object MarkerPalette {
    /** This device's own position. */
    val ME = 0xFF1976D2.toInt()

    /** The start of a recorded track. */
    val START = 0xFF2E7D32.toInt()

    /** The end of a recorded track. */
    val END = 0xFFC62828.toInt()

    /**
     * A hand-picked ring of peer colours, each dark and saturated enough to show the white core
     * of the map symbol, spread around the hue wheel, and kept clear of [ME] (blue), [START]
     * (green) and [END] (red). Peers beyond this many fall back to [generatedPeerColor].
     */
    private val PEER_HUES = intArrayOf(
        0xFF8E24AA.toInt(), // purple
        0xFFEF6C00.toInt(), // deep orange
        0xFF00838F.toInt(), // dark cyan
        0xFF6D4C41.toInt(), // brown
        0xFFAD1457.toInt(), // raspberry
        0xFF4527A0.toInt(), // deep indigo
        0xFF558B2F.toInt(), // olive green
        0xFF00695C.toInt(), // dark teal
        0xFFD84315.toInt(), // burnt orange
        0xFF283593.toInt(), // indigo
        0xFF9E9D24.toInt(), // dark chartreuse
        0xFF6A1B9A.toInt(), // violet
        0xFF37474F.toInt(), // slate
        0xFFC2185B.toInt(), // pink
        0xFF0277BD.toInt(), // ocean blue
        0xFF4E342E.toInt(), // dark brown
    )

    /** How many curated colours are available before generation takes over. */
    val PEER_COLOR_COUNT get() = PEER_HUES.size

    /**
     * The colour for the peer at [index] among the peers on screen: a curated colour for the
     * first [PEER_COLOR_COUNT], then deterministically generated hues that stay far apart.
     *
     * @param index the peer's zero-based position in the list of peers.
     * @return an opaque ARGB colour.
     */
    fun peerColor(index: Int): Int {
        val safeIndex = index.coerceAtLeast(0)
        return if (safeIndex < PEER_HUES.size) {
            PEER_HUES[safeIndex]
        } else {
            generatedPeerColor(safeIndex - PEER_HUES.size)
        }
    }

    /**
     * Generates a colour by stepping the hue wheel in golden-angle increments, so any number of
     * successive colours stay visually separated. Saturation and value are fixed low enough to
     * read on the light OSM base map.
     *
     * @param step how many golden-angle steps past the curated colours.
     * @return an opaque ARGB colour.
     */
    private fun generatedPeerColor(step: Int): Int {
        val hue = (step * 137.508f) % 360f
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.70f, 0.62f))
    }
}

/**
 * A one-shot request to pan the map to [target]. It is passed as a fresh instance each time so
 * that tapping the same legend row twice still moves the camera back.
 *
 * @property target where to centre the map.
 */
class MapFocusRequest(val target: LatLon)

/** Default zoom used when the map is centred on a single point. */
private const val SINGLE_POINT_ZOOM = 16.0

/** Where the map sits before it has any geometry or a known position: central Brussels. */
private val DEFAULT_CENTER = GeoPoint(50.8503, 4.3517)
private const val DEFAULT_ZOOM = 11.0

/**
 * An osmdroid map showing an optional path and a set of markers, with the OpenStreetMap tile
 * source. The camera fits the supplied geometry once (the first time there is any), then leaves
 * it to the reader — who can pan freely and use the recentre button to jump back.
 *
 * @param modifier layout modifier for the map.
 * @param path ordered coordinates drawn as a single polyline; empty to draw none.
 * @param markers points to drop on the map, each as a coloured concentric dot.
 * @param fallbackCenter where to centre when [path] and [markers] are both empty.
 * @param recenterTarget when non-null, a small button is shown that animates the map to this
 *   point (normally the reader's own current position).
 * @param focusRequest when it changes to a non-null value, the camera pans to that point at the
 *   current zoom; used to jump to a marker when its legend row is tapped.
 * @param offlineReady when true, the map renders from the downloaded offline vector map instead
 *   of the online OpenStreetMap tiles.
 */
@Composable
fun TrackMapView(
    modifier: Modifier = Modifier,
    path: List<LatLon> = emptyList(),
    markers: List<MapMarker> = emptyList(),
    fallbackCenter: LatLon? = null,
    recenterTarget: LatLon? = null,
    focusRequest: MapFocusRequest? = null,
    offlineReady: Boolean = false,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            // Start on land at a regional zoom so an un-positioned map never shows blank ocean.
            controller.setZoom(DEFAULT_ZOOM)
            controller.setCenter(DEFAULT_CENTER)
        }
    }
    // Remembers the geometry the camera was last fitted to, so panning is not undone on every
    // recomposition and the camera only re-fits when the points actually change.
    val lastFitKey = remember { arrayOfNulls<String>(1) }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    // Pick the tile source: the offline vector map when it is present, otherwise online OSM.
    LaunchedEffect(offlineReady) {
        val offline = if (offlineReady) OfflineMap.tileProvider(context) else null
        if (offline != null) {
            mapView.tileProvider = offline
            mapView.setTileSource(offline.tileSource)
        } else {
            mapView.setTileSource(TileSourceFactory.MAPNIK)
        }
        mapView.invalidate()
    }

    // Pan to a tapped legend row. A new MapFocusRequest instance each tap re-triggers this.
    LaunchedEffect(focusRequest) {
        focusRequest?.let {
            mapView.controller.animateTo(GeoPoint(it.target.latitude, it.target.longitude))
        }
    }

    Box(modifier) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { mapView },
        update = { view ->
            val pathPoints = path.map { GeoPoint(it.latitude, it.longitude) }
            val markerPoints = markers.map { GeoPoint(it.position.latitude, it.position.longitude) }

            view.overlays.clear()
            if (pathPoints.size >= 2) {
                view.overlays.add(
                    Polyline(view).apply {
                        outlinePaint.color = Color.rgb(0x1E, 0x7A, 0x46)
                        outlinePaint.strokeWidth = 10f
                        setPoints(pathPoints)
                    },
                )
            }
            markers.forEach { marker ->
                view.overlays.add(
                    Marker(view).apply {
                        position = GeoPoint(marker.position.latitude, marker.position.longitude)
                        title = marker.label
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = BitmapDrawable(
                            view.resources,
                            concentricMarkerBitmap(view.context, marker.color),
                        )
                    },
                )
            }

            // One-shot fit: frame whatever geometry exists the first time, then stop moving the
            // camera on the reader.
            if (lastFitKey[0] == null) {
                val cameraPoints = pathPoints + markerPoints
                if (cameraPoints.isNotEmpty() || fallbackCenter != null) {
                    view.fitCameraWhenLaidOut(cameraPoints, fallbackCenter) { lastFitKey[0] = "done" }
                }
            }
            view.invalidate()
        },
      )

      if (recenterTarget != null) {
          SmallFloatingActionButton(
              onClick = {
                  mapView.controller.animateTo(
                      GeoPoint(recenterTarget.latitude, recenterTarget.longitude),
                      SINGLE_POINT_ZOOM,
                      800L,
                  )
              },
              modifier = Modifier
                  .align(Alignment.BottomEnd)
                  .padding(12.dp),
          ) {
              Icon(Icons.Filled.MyLocation, contentDescription = "Centre on my location")
          }
      }
    }
}

/**
 * Blends a colour towards white.
 *
 * @param color the opaque base colour.
 * @param fraction 0 keeps the colour, 1 returns white.
 * @return the lightened opaque colour.
 */
internal fun blendWithWhite(color: Int, fraction: Float): Int {
    fun mix(channel: Int) = (channel + (255 - channel) * fraction).toInt().coerceIn(0, 255)
    return Color.rgb(mix(Color.red(color)), mix(Color.green(color)), mix(Color.blue(color)))
}

/**
 * Draws the shared map symbol: a large light disc of [baseColor], a white separator ring, and a
 * small solid core of [baseColor] — two concentric circles in shades of one colour, pointing at
 * an exact spot.
 *
 * @param context used for the display density.
 * @param baseColor the opaque base colour.
 * @return a square bitmap with the dot centred in it.
 */
internal fun concentricMarkerBitmap(context: Context, baseColor: Int): Bitmap {
    val size = (24 * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centre = size / 2f
    val outerRadius = centre - 1f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = blendWithWhite(baseColor, 0.62f)
    canvas.drawCircle(centre, centre, outerRadius, paint)
    paint.color = Color.WHITE
    canvas.drawCircle(centre, centre, outerRadius * 0.5f, paint)
    paint.color = baseColor
    canvas.drawCircle(centre, centre, outerRadius * 0.36f, paint)

    return bitmap
}

/**
 * Moves the camera to show [points], deferring until the map has real dimensions. Calling
 * osmdroid's `zoomToBoundingBox` on a zero-sized map spins forever, so this waits for the first
 * layout when necessary.
 *
 * @param points the coordinates to frame; may be empty.
 * @param fallbackCenter where to centre when [points] is empty.
 * @param onFitted invoked once the camera has been moved.
 */
private fun MapView.fitCameraWhenLaidOut(
    points: List<GeoPoint>,
    fallbackCenter: LatLon?,
    onFitted: () -> Unit,
) {
    val moveCamera = {
        runCatching {
            val spanLat = (points.maxOfOrNull { it.latitude } ?: 0.0) -
                (points.minOfOrNull { it.latitude } ?: 0.0)
            val spanLon = (points.maxOfOrNull { it.longitude } ?: 0.0) -
                (points.minOfOrNull { it.longitude } ?: 0.0)
            when {
                points.isEmpty() -> fallbackCenter?.let {
                    controller.setCenter(GeoPoint(it.latitude, it.longitude))
                }
                // One point, or several so close together that a bounding box would be
                // degenerate (which sends osmdroid's zoomToBoundingBox to world zoom).
                points.size == 1 || (spanLat < 1e-3 && spanLon < 1e-3) -> {
                    controller.setZoom(SINGLE_POINT_ZOOM)
                    controller.setCenter(points.first())
                }
                else -> zoomToBoundingBox(
                    BoundingBox.fromGeoPointsSafe(points).increaseByScale(1.3f),
                    false,
                    64,
                )
            }
        }
        onFitted()
    }
    if (width > 0 && height > 0) {
        moveCamera()
    } else {
        addOnFirstLayoutListener { _, _, _, _, _ -> moveCamera() }
    }
}
