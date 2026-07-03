package com.example.alarm.maps

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
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val SRC_ROUTE = "ola-route-src"
private const val LYR_ROUTE = "ola-route-lyr"
private const val SRC_FROM = "ola-from-src"
private const val LYR_FROM = "ola-from-lyr"
private const val SRC_TO = "ola-to-src"
private const val LYR_TO = "ola-to-lyr"
private const val SRC_CURRENT = "ola-current-src"
private const val LYR_CURRENT_HALO = "ola-current-halo-lyr"
private const val LYR_CURRENT = "ola-current-lyr"

// Marker colors (kept here so the map reads consistently with the Sleek theme accents).
private const val COLOR_FROM = "#10B981"   // green  (start)
private const val COLOR_TO = "#6366F1"     // primary (destination)
private const val COLOR_CURRENT = "#3B82F6" // blue   (live position)
private const val COLOR_ROUTE = "#6366F1"

/**
 * One global OkHttp client for MapLibre that appends the restricted Ola TILE key
 * to every api.olamaps.io request (tiles / sprites / glyphs referenced inside the
 * style.json do not carry the key themselves). §689: this is the app-shipped tile
 * key from the OdioBook geo gateway, NOT the secret REST key. Installed once.
 */
@Volatile private var httpConfigured = false

private fun ensureOlaHttp(tileKey: String) {
    if (httpConfigured) return
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        val url = req.url
        if (url.host.contains("olamaps.io") && url.queryParameter("api_key") == null) {
            val newUrl = url.newBuilder().addQueryParameter("api_key", tileKey).build()
            chain.proceed(req.newBuilder().url(newUrl).build())
        } else {
            chain.proceed(req)
        }
    }.build()
    HttpRequestUtil.setOkHttpClient(client)
    httpConfigured = true
}

/**
 * Interactive Ola map (MapLibre engine + Ola vector tiles) used in the Travel tab.
 * Shows the live current location, the FROM/TO custom markers, and the route polyline
 * simultaneously. Renders on a translucent texture surface so the app background blends
 * through (the "transparency" requirement).
 *
 * @param tileKey Restricted Ola tile key from the geo gateway (app-config). Must be non-blank.
 * @param tileBaseUrl Ola tiles base (app-config base_url), e.g. https://api.olamaps.io.
 */
@SuppressLint("MissingPermission")
@Composable
fun OlaMapView(
    tileKey: String,
    modifier: Modifier = Modifier,
    tileBaseUrl: String = OlaMapsRepository.DEFAULT_TILE_BASE,
    current: GeoPoint? = null,
    from: GeoPoint? = null,
    to: GeoPoint? = null,
    route: List<GeoPoint>? = null,
    isDark: Boolean = isSystemInDarkTheme(),
    followCurrent: Boolean = false,
    // §817 — key-less MapLibre style URL (§692 tile_style_url). When set it wins
    // over the legacy Ola tileKey style; pass OlaMapsRepository.resolveStyleUrl(...).
    styleUrl: String? = null
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val mapView = remember {
        if (tileKey.isNotBlank()) ensureOlaHttp(tileKey)
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions()
            .textureMode(true)               // texture surface -> allows transparency
            .translucentTextureSurface(true)
        MapView(context, options).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    // Forward Compose lifecycle to the MapView (MapLibre requires this).
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

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { _ ->
            mapView.getMapAsync { map ->
                mapRef.value = map
                val uri = styleUrl?.ifBlank { null }
                    ?: OlaMapsRepository.styleUrl(tileKey, isDark, tileBaseUrl)
                map.setStyle(Style.Builder().fromUri(uri)) { style ->
                    styleRef.value = style
                    initLayers(style)
                    applyData(map, style, current, from, to, route, followCurrent, firstFit = true)
                }
            }
            mapView
        },
        update = { _ ->
            val map = mapRef.value
            val style = styleRef.value
            if (map != null && style != null && style.isFullyLoaded) {
                applyData(map, style, current, from, to, route, followCurrent, firstFit = false)
            }
        }
    )
}

private fun initLayers(style: Style) {
    if (style.getSource(SRC_ROUTE) != null) return // already initialised

    style.addSource(GeoJsonSource(SRC_ROUTE))
    style.addSource(GeoJsonSource(SRC_FROM))
    style.addSource(GeoJsonSource(SRC_TO))
    style.addSource(GeoJsonSource(SRC_CURRENT))

    style.addLayer(
        LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(COLOR_ROUTE),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
        )
    )
    style.addLayer(
        CircleLayer(LYR_FROM, SRC_FROM).withProperties(
            PropertyFactory.circleColor(COLOR_FROM),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f)
        )
    )
    style.addLayer(
        CircleLayer(LYR_TO, SRC_TO).withProperties(
            PropertyFactory.circleColor(COLOR_TO),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f)
        )
    )
    style.addLayer(
        CircleLayer(LYR_CURRENT_HALO, SRC_CURRENT).withProperties(
            PropertyFactory.circleColor(COLOR_CURRENT),
            PropertyFactory.circleRadius(16f),
            PropertyFactory.circleOpacity(0.18f)
        )
    )
    style.addLayer(
        CircleLayer(LYR_CURRENT, SRC_CURRENT).withProperties(
            PropertyFactory.circleColor(COLOR_CURRENT),
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f)
        )
    )
}

private fun applyData(
    map: MapLibreMap,
    style: Style,
    current: GeoPoint?,
    from: GeoPoint?,
    to: GeoPoint?,
    route: List<GeoPoint>?,
    followCurrent: Boolean,
    firstFit: Boolean
) {
    (style.getSource(SRC_FROM) as? GeoJsonSource)?.setGeoJson(pointFeature(from))
    (style.getSource(SRC_TO) as? GeoJsonSource)?.setGeoJson(pointFeature(to))
    (style.getSource(SRC_CURRENT) as? GeoJsonSource)?.setGeoJson(pointFeature(current))
    (style.getSource(SRC_ROUTE) as? GeoJsonSource)?.setGeoJson(lineFeature(route))

    val all = listOfNotNull(current, from, to) + (route ?: emptyList())
    when {
        followCurrent && current != null -> {
            map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(current.latitude, current.longitude)))
        }
        firstFit && all.size >= 2 -> {
            val bounds = LatLngBounds.Builder().apply {
                all.forEach { include(LatLng(it.latitude, it.longitude)) }
            }.build()
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        }
        firstFit && all.size == 1 -> {
            val p = all.first()
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), 13.0))
        }
    }
}

private fun pointFeature(p: GeoPoint?): FeatureCollection =
    if (p == null) FeatureCollection.fromFeatures(emptyArray<Feature>())
    else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)))

private fun lineFeature(pts: List<GeoPoint>?): FeatureCollection {
    if (pts.isNullOrEmpty()) return FeatureCollection.fromFeatures(emptyArray<Feature>())
    val line = LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) })
    return FeatureCollection.fromFeature(Feature.fromGeometry(line))
}
