package com.example.alarm.maps

import android.util.Log
import com.example.alarm.location.CityInfo
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * §689 — Thin client for the OdioBook GEO gateway.
 *
 * The Ola Maps REST key NO LONGER lives in the app: every Places / geocode /
 * directions call is proxied through {server}/api/geo/, which injects the
 * admin-managed key server-side. The only thing configurable on-device is the
 * OdioBook [serverBaseUrl]; which features are on (and the restricted tile key
 * for rendering tiles) come from [appConfig].
 *
 * All calls are main-safe (Dispatchers.IO) and never throw: failures return
 * null/empty so callers can fall back to the existing OSM Nominatim path.
 */
object OlaMapsRepository {

    private const val TAG = "OlaMapsRepository"

    /** Default OdioBook production server (overridable in Settings → Advanced). */
    const val DEFAULT_SERVER = "https://odiobook.com"

    /** Default Ola tiles base (the gateway echoes this in app-config.base_url). */
    const val DEFAULT_TILE_BASE = "https://api.olamaps.io"

    /** This client's app id, so the admin can gate features per app. */
    private const val APP = "earlyrover"

    /** Configured OdioBook server. Setting a new value rebuilds the Retrofit api. */
    @Volatile
    var serverBaseUrl: String = DEFAULT_SERVER
        set(value) {
            val v = value.trim().ifBlank { DEFAULT_SERVER }
            if (v != field) {
                field = v
                synchronized(this) { _api = null }
            }
        }

    @Volatile private var _api: OlaMapsApi? = null
    @Volatile private var _apiBase: String = ""

    private fun normalizeBase(s: String): String {
        val t = s.trim().ifBlank { DEFAULT_SERVER }
        return if (t.endsWith("/")) t else "$t/"
    }

    private fun api(): OlaMapsApi {
        val base = normalizeBase(serverBaseUrl)
        val cached = _api
        if (cached != null && _apiBase == base) return cached
        return synchronized(this) {
            val again = _api
            if (again != null && _apiBase == base) {
                again
            } else {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                val moshi = Moshi.Builder().build()
                val built = Retrofit.Builder()
                    .baseUrl(base)
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                    .create(OlaMapsApi::class.java)
                _api = built
                _apiBase = base
                built
            }
        }
    }

    /**
     * Vector-tile style URL for MapLibre setStyle(). Built from the gateway-issued
     * restricted [tileKey] + tiles [tileBase] (NOT the secret REST key). Tiles,
     * sprites and glyphs referenced inside the style still render on-device.
     */
    fun styleUrl(tileKey: String, dark: Boolean, tileBase: String = DEFAULT_TILE_BASE): String {
        val base = tileBase.trim().ifBlank { DEFAULT_TILE_BASE }.trimEnd('/')
        val style = if (dark) "default-dark-standard" else "default-light-standard"
        return "$base/tiles/vector/v1/styles/$style/style.json?api_key=$tileKey"
    }

    /** §692 — key-less MapLibre style (OpenFreeMap). Fallback when app-config is unreachable. */
    const val DEFAULT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    /**
     * Resolve the style URL a MapLibre view should render, across both backend
     * generations: prefer the key-less `tile_style_url` (§692, the ONLY thing prod
     * serves now), fall back to the legacy Ola tile_key style if an old server
     * still hands one out, else the public default so the map ALWAYS renders.
     * (Pre-fix the Rover map gated on tile_key — blank since §692 — so it showed
     * "Map unavailable" forever.)
     */
    fun resolveStyleUrl(config: com.example.alarm.maps.GeoAppConfigDto?, dark: Boolean): String {
        val styleUrl = config?.tileStyleUrl?.trim().orEmpty()
        if (styleUrl.isNotBlank()) return styleUrl
        val key = config?.tileKey?.trim().orEmpty()
        if (key.isNotBlank()) return styleUrl(key, dark, config?.baseUrl?.ifBlank { null } ?: DEFAULT_TILE_BASE)
        return DEFAULT_STYLE_URL
    }

    /** Fetch the remote feature/config for this app. Null on any failure. */
    suspend fun appConfig(): GeoAppConfigDto? = withContext(Dispatchers.IO) {
        try {
            api().appConfig(APP)
        } catch (e: Exception) {
            Log.w(TAG, "app-config failed: ${e.message}")
            null
        }
    }

    /**
     * Places autocomplete. Returns results mapped to [CityInfo] so the existing
     * Travel search dropdown renders them unchanged. Predictions without inline
     * coordinates are resolved via a place-details lookup.
     */
    suspend fun searchPlaces(query: String, bias: GeoPoint? = null): List<CityInfo> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val resp = api().autocomplete(
                    input = q, app = APP, lat = bias?.latitude, lon = bias?.longitude
                )
                resp.suggestions.mapNotNull { s ->
                    val name = s.title ?: return@mapNotNull null
                    val secondary = s.subtitle ?: ""
                    val lat = s.lat
                    val lon = s.lon
                    if (lat != null && lon != null) {
                        CityInfo(name = name, country = secondary, latitude = lat, longitude = lon, timezoneOffset = 0.0)
                    } else {
                        // No inline geometry -> resolve via details (best effort).
                        val pid = s.placeId ?: return@mapNotNull null
                        val pt = resolvePlace(pid) ?: return@mapNotNull null
                        CityInfo(name = name, country = secondary, latitude = pt.latitude, longitude = pt.longitude, timezoneOffset = 0.0)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "autocomplete failed: ${e.message}")
                emptyList()
            }
        }
    }

    /** Resolve a place_id to coordinates. */
    suspend fun resolvePlace(placeId: String): GeoPoint? {
        if (placeId.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val d = api().placeDetails(placeId, APP)
                val lat = d.lat; val lon = d.lon
                if (lat != null && lon != null) GeoPoint(lat, lon) else null
            } catch (e: Exception) {
                Log.w(TAG, "place-details failed: ${e.message}")
                null
            }
        }
    }

    /** Reverse geocode a coordinate to a human-readable address. */
    suspend fun reverseGeocode(point: GeoPoint): String? {
        return withContext(Dispatchers.IO) {
            try {
                api().reverse(point.latitude, point.longitude, APP).address?.ifBlank { null }
            } catch (e: Exception) {
                Log.w(TAG, "reverse failed: ${e.message}")
                null
            }
        }
    }

    /** Fetch a drivable route between two points, decoded for the map. */
    suspend fun route(from: GeoPoint, to: GeoPoint): OlaRouteResult? {
        return withContext(Dispatchers.IO) {
            try {
                val resp = api().directions(
                    fromLat = from.latitude, fromLon = from.longitude,
                    toLat = to.latitude, toLon = to.longitude, app = APP
                )
                val poly = resp.polyline
                if (poly.isNullOrBlank()) return@withContext null
                val pts = decodePolyline(poly)
                if (pts.isEmpty()) return@withContext null
                OlaRouteResult(
                    points = pts,
                    distanceMeters = resp.distanceMeters?.toDouble(),
                    durationSeconds = resp.durationSeconds?.toDouble()
                )
            } catch (e: Exception) {
                Log.w(TAG, "directions failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Decode a Google/Ola encoded polyline string into geographic points.
     * (Standard precision-5 algorithm.)
     */
    fun decodePolyline(encoded: String): List<GeoPoint> {
        val poly = ArrayList<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(GeoPoint(lat / 1E5, lng / 1E5))
        }
        return poly
    }
}
