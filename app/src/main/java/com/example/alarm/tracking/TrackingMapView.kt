package com.example.alarm.tracking

import android.annotation.SuppressLint
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.example.alarm.maps.OlaMapsRepository
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** One person to plot on the tracking map. */
data class PeerPoint(val id: Int, val point: GeoPoint, val color: String, val ghost: Boolean = false)

private const val SRC_PEERS = "er-peers-src"
private const val LYR_PEERS_HALO = "er-peers-halo"
private const val LYR_PEERS = "er-peers"
private const val SRC_ME = "er-me-src"
private const val LYR_ME_HALO = "er-me-halo"
private const val LYR_ME = "er-me"
private const val SRC_PLACES = "er-places-src"
private const val LYR_PLACES = "er-places"

private const val COLOR_ME = "#009688"      // teal (self)
private const val COLOR_PLACE = "#F59E0B"    // amber (saved places)

/**
 * §806 — live group map. Renders every connection/circle member as a coloured dot
 * (data-driven ``circleColor`` from each feature's "color" property) plus the
 * user's own position and saved places. Built on MapLibre + the Ola vector tiles
 * exactly like [com.example.alarm.maps.OlaMapView]; names are shown in the list
 * sheet below the map (no on-map text → no glyph dependency).
 */
@SuppressLint("MissingPermission")
@Composable
fun TrackingMapView(
    tileKey: String,
    modifier: Modifier = Modifier,
    tileBaseUrl: String = OlaMapsRepository.DEFAULT_TILE_BASE,
    peers: List<PeerPoint> = emptyList(),
    me: GeoPoint? = null,
    places: List<GeoPoint> = emptyList(),
    isDark: Boolean = isSystemInDarkTheme()
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

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { _ ->
            mapView.getMapAsync { map ->
                mapRef.value = map
                map.setStyle(Style.Builder().fromUri(OlaMapsRepository.styleUrl(tileKey, isDark, tileBaseUrl))) { style ->
                    styleRef.value = style
                    initLayers(style)
                    applyData(map, style, peers, me, places, didFit)
                }
            }
            mapView
        },
        update = { _ ->
            val map = mapRef.value
            val style = styleRef.value
            if (map != null && style != null && style.isFullyLoaded) {
                applyData(map, style, peers, me, places, didFit)
            }
        }
    )
}

private fun initLayers(style: Style) {
    if (style.getSource(SRC_PEERS) != null) return
    style.addSource(GeoJsonSource(SRC_PEERS))
    style.addSource(GeoJsonSource(SRC_ME))
    style.addSource(GeoJsonSource(SRC_PLACES))

    style.addLayer(CircleLayer(LYR_PLACES, SRC_PLACES).withProperties(
        PropertyFactory.circleColor(COLOR_PLACE),
        PropertyFactory.circleRadius(7f),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleStrokeWidth(2f),
        PropertyFactory.circleOpacity(0.9f)
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
    didFit: androidx.compose.runtime.MutableState<Boolean>
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
