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
 * Thin wrapper over [OlaMapsApi]. The API key is read at call time (the caller passes the
 * value coming from AlarmViewModel.olaMapsApiKey) so the key stays fully dynamic / admin
 * configurable. All calls are main-safe (run on Dispatchers.IO) and never throw: failures
 * return null/empty so callers can fall back to the existing OSM Nominatim path.
 */
object OlaMapsRepository {

    private const val TAG = "OlaMapsRepository"
    const val BASE_URL = "https://api.olamaps.io/"
    private const val TILES_STYLE_LIGHT = "https://api.olamaps.io/tiles/vector/v1/styles/default-light-standard/style.json"
    private const val TILES_STYLE_DARK = "https://api.olamaps.io/tiles/vector/v1/styles/default-dark-standard/style.json"

    private val api: OlaMapsApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val moshi = Moshi.Builder().build()
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OlaMapsApi::class.java)
    }

    /** Vector-tile style URL with the api_key appended, for MapLibre setStyle(). */
    fun styleUrl(apiKey: String, dark: Boolean): String {
        val base = if (dark) TILES_STYLE_DARK else TILES_STYLE_LIGHT
        return "$base?api_key=$apiKey"
    }

    /**
     * Places autocomplete. Returns results mapped to [CityInfo] so the existing Travel
     * search dropdown can render them unchanged. Predictions without coordinates are
     * resolved via a place-details lookup.
     */
    suspend fun searchPlaces(query: String, apiKey: String, bias: GeoPoint? = null): List<CityInfo> {
        val q = query.trim()
        if (q.length < 2 || apiKey.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val biasStr = bias?.let { "${it.latitude},${it.longitude}" }
                val resp = api.autocomplete(input = q, apiKey = apiKey, location = biasStr)
                resp.predictions.mapNotNull { p ->
                    val name = p.structuredFormatting?.mainText
                        ?: p.description
                        ?: return@mapNotNull null
                    val secondary = p.structuredFormatting?.secondaryText ?: ""
                    val loc = p.geometry?.location
                    val lat = loc?.lat
                    val lng = loc?.lng
                    if (lat != null && lng != null) {
                        CityInfo(name = name, country = secondary, latitude = lat, longitude = lng, timezoneOffset = 0.0)
                    } else {
                        // No inline geometry -> resolve via details (best effort).
                        val pid = p.placeId ?: return@mapNotNull null
                        val pt = resolvePlace(pid, apiKey) ?: return@mapNotNull null
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
    suspend fun resolvePlace(placeId: String, apiKey: String): GeoPoint? {
        if (apiKey.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val loc = api.placeDetails(placeId, apiKey).result?.geometry?.location
                val lat = loc?.lat; val lng = loc?.lng
                if (lat != null && lng != null) GeoPoint(lat, lng) else null
            } catch (e: Exception) {
                Log.w(TAG, "placeDetails failed: ${e.message}")
                null
            }
        }
    }

    /** Reverse geocode a coordinate to a human-readable address. */
    suspend fun reverseGeocode(point: GeoPoint, apiKey: String): String? {
        if (apiKey.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                api.reverseGeocode("${point.latitude},${point.longitude}", apiKey)
                    .results.firstOrNull()?.formattedAddress
            } catch (e: Exception) {
                Log.w(TAG, "reverseGeocode failed: ${e.message}")
                null
            }
        }
    }

    /** Fetch a drivable route between two points, decoded for the map. */
    suspend fun route(from: GeoPoint, to: GeoPoint, apiKey: String): OlaRouteResult? {
        if (apiKey.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val resp = api.directions(
                    origin = "${from.latitude},${from.longitude}",
                    destination = "${to.latitude},${to.longitude}",
                    apiKey = apiKey
                )
                val r = resp.routes.firstOrNull() ?: return@withContext null
                val pts = r.overviewPolyline?.let { decodePolyline(it) } ?: emptyList()
                if (pts.isEmpty()) return@withContext null
                val leg = r.legs.firstOrNull()
                OlaRouteResult(points = pts, distanceMeters = leg?.distanceMeters, durationSeconds = leg?.durationSeconds)
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
