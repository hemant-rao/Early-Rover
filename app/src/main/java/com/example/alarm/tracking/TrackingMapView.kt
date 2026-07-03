package com.example.alarm.tracking

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.alarm.maps.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** One person to plot on the tracking map. */
data class PeerPoint(val id: Int, val point: GeoPoint, val color: String, val ghost: Boolean = false)

/** Camera focus request — [seq] bumps per tap so re-focusing the SAME person re-centers. */
data class MapFocus(val point: GeoPoint, val seq: Long, val peerId: Int? = null)

private const val SRC_PEERS = "er-peers-src"
private const val LYR_PEERS_HALO = "er-peers-halo"
private const val LYR_PEERS = "er-peers"
private const val SRC_ME = "er-me-src"
private const val LYR_ME_HALO = "er-me-halo"
private const val LYR_ME = "er-me"
private const val SRC_PLACES = "er-places-src"
private const val LYR_PLACES = "er-places"
private const val SRC_LINK = "er-link-src"
private const val LYR_LINK = "er-link"
private const val SRC_FOCUS = "er-focus-src"
private const val LYR_FOCUS = "er-focus-ring"

private const val COLOR_ME = "#009688"      // teal (self)
private const val COLOR_PLACE = "#F59E0B"    // amber (saved places)
private const val COLOR_LINK = "#3287FE"     // me → focused-peer distance line

/**
 * §806 — live group map (§817: key-less style URL + focus camera + distance line).
 * Renders every connection/circle member as a coloured dot (data-driven
 * ``circleColor`` from each feature's "color" property) plus the user's own
 * position and saved places. Names/distances are shown in the list below the map
 * (no on-map text → no glyph dependency).
 *
 * @param styleUrl resolved MapLibre style (see OlaMapsRepository.resolveStyleUrl) —
 *        works with the key-less §692 OpenFreeMap style AND legacy Ola keyed styles.
 * @param focus when set, the camera flies to [MapFocus.point]; a dashed line from
 *        "me" to that point (with a highlight ring) shows who you're looking at.
 */
@SuppressLint("MissingPermission")
@Composable
fun TrackingMapView(
    styleUrl: String,
    modifier: Modifier = Modifier,
    peers: List<PeerPoint> = emptyList(),
    me: GeoPoint? = null,
    places: List<GeoPoint> = emptyList(),
    focus: MapFocus? = null
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context)
    }

    DisposableEffect(lifecycleOwner) {
        mapView.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleRef = remember { mutableStateOf<Style?>(null) }
    val didFit = remember { mutableStateOf(false) }
    val lastFocusSeq = remember { mutableStateOf(-1L) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { _ ->
            mapView.getMapAsync { map ->
                mapRef.value = map
                map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    styleRef.value = style
                    initLayers(style)
                    applyData(map, style, peers, me, places, focus, didFit, lastFocusSeq)
                }
            }
            mapView
        },
        update = { _ ->
            val map = mapRef.value
            val style = styleRef.value
            if (map != null && style != null && style.isFullyLoaded) {
                applyData(map, style, peers, me, places, focus, didFit, lastFocusSeq)
            }
        }
    )
}

private fun initLayers(style: Style) {
    if (style.getSource(SRC_PEERS) != null) return
    style.addSource(GeoJsonSource(SRC_PEERS))
    style.addSource(GeoJsonSource(SRC_ME))
    style.addSource(GeoJsonSource(SRC_PLACES))
    style.addSource(GeoJsonSource(SRC_LINK))
    style.addSource(GeoJsonSource(SRC_FOCUS))

    style.addLayer(CircleLayer(LYR_PLACES, SRC_PLACES).withProperties(
        PropertyFactory.circleColor(COLOR_PLACE),
        PropertyFactory.circleRadius(7f),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleStrokeWidth(2f),
        PropertyFactory.circleOpacity(0.9f)
    ))
    // Me → focused-peer dashed link (under the dots).
    style.addLayer(LineLayer(LYR_LINK, SRC_LINK).withProperties(
        PropertyFactory.lineColor(COLOR_LINK),
        PropertyFactory.lineWidth(2.5f),
        PropertyFactory.lineOpacity(0.75f),
        PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
    ))
    // Focus highlight ring (bigger halo behind the focused peer).
    style.addLayer(CircleLayer(LYR_FOCUS, SRC_FOCUS).withProperties(
        PropertyFactory.circleColor(COLOR_LINK),
        PropertyFactory.circleRadius(24f),
        PropertyFactory.circleOpacity(0.18f),
        PropertyFactory.circleStrokeColor(COLOR_LINK),
        PropertyFactory.circleStrokeWidth(1.5f),
        PropertyFactory.circleStrokeOpacity(0.6f)
    ))
    // Peer halo + dot (colour driven per-feature).
    style.addLayer(CircleLayer(LYR_PEERS_HALO, SRC_PEERS).withProperties(
        PropertyFactory.circleColor(Expression.get("color")),
        PropertyFactory.circleRadius(16f),
        PropertyFactory.circleOpacity(0.18f)
    ))
    style.addLayer(CircleLayer(LYR_PEERS, SRC_PEERS).withProperties(
        PropertyFactory.circleColor(Expression.get("color")),
        PropertyFactory.circleRadius(7f),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleStrokeWidth(2f)
    ))
    // Self.
    style.addLayer(CircleLayer(LYR_ME_HALO, SRC_ME).withProperties(
        PropertyFactory.circleColor(COLOR_ME),
        PropertyFactory.circleRadius(18f),
        PropertyFactory.circleOpacity(0.16f)
    ))
    style.addLayer(CircleLayer(LYR_ME, SRC_ME).withProperties(
        PropertyFactory.circleColor(COLOR_ME),
        PropertyFactory.circleRadius(8f),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleStrokeWidth(3f)
    ))
}

private fun applyData(
    map: MapLibreMap,
    style: Style,
    peers: List<PeerPoint>,
    me: GeoPoint?,
    places: List<GeoPoint>,
    focus: MapFocus?,
    didFit: androidx.compose.runtime.MutableState<Boolean>,
    lastFocusSeq: androidx.compose.runtime.MutableState<Long>
) {
    val peerFeatures = peers.map { p ->
        Feature.fromGeometry(Point.fromLngLat(p.point.longitude, p.point.latitude)).apply {
            addStringProperty("color", if (p.ghost) "#9CA3AF" else p.color)
        }
    }
    (style.getSource(SRC_PEERS) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(peerFeatures))
    (style.getSource(SRC_ME) as? GeoJsonSource)?.setGeoJson(pointFc(me))
    (style.getSource(SRC_PLACES) as? GeoJsonSource)?.setGeoJson(
        FeatureCollection.fromFeatures(places.map {
            Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))
        }))

    // Focus ring follows the focused peer's LIVE position (falls back to the tapped
    // point); the link line ties it to "me" so the between-us distance reads visually.
    val focusedLive = focus?.peerId?.let { id -> peers.firstOrNull { it.id == id }?.point } ?: focus?.point
    (style.getSource(SRC_FOCUS) as? GeoJsonSource)?.setGeoJson(pointFc(focusedLive))
    val link = if (focusedLive != null && me != null) {
        FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(listOf(
            Point.fromLngLat(me.longitude, me.latitude),
            Point.fromLngLat(focusedLive.longitude, focusedLive.latitude)
        ))))
    } else FeatureCollection.fromFeatures(emptyArray<Feature>())
    (style.getSource(SRC_LINK) as? GeoJsonSource)?.setGeoJson(link)

    // A new focus tap → fly the camera there (once per seq, so panning isn't hijacked).
    if (focus != null && focus.seq != lastFocusSeq.value) {
        lastFocusSeq.value = focus.seq
        didFit.value = true   // an explicit focus supersedes the initial auto-fit
        val target = focusedLive ?: focus.point
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(
            LatLng(target.latitude, target.longitude), 15.5), 900)
        return
    }

    if (!didFit.value) {
        val all = (peers.map { it.point } + listOfNotNull(me))
        when {
            all.size >= 2 -> {
                val b = LatLngBounds.Builder().apply {
                    all.forEach { include(LatLng(it.latitude, it.longitude)) }
                }.build()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 140))
                didFit.value = true
            }
            all.size == 1 -> {
                val p = all.first()
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), 14.0))
                didFit.value = true
            }
        }
    }
}

private fun pointFc(p: GeoPoint?): FeatureCollection =
    if (p == null) FeatureCollection.fromFeatures(emptyArray<Feature>())
    else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)))
