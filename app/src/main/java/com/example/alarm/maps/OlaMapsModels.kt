package com.example.alarm.maps

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * §689 — DTOs for the OdioBook GEO gateway (NOT Ola's raw shapes).
 *
 * The app no longer talks to api.olamaps.io directly; it calls the OdioBook
 * backend at {server}/api/geo/*, which proxies Ola with the admin-managed REST
 * key (server-side only) and returns these STABLE normalised envelopes. The only
 * Ola credential on-device is the restricted *tile* key, delivered via
 * [GeoAppConfigDto.tileKey] and used solely to render map tiles.
 */

/**
 * Lightweight lat/lng pair used across the maps layer so the repository / DTOs do
 * not depend on MapLibre types (OlaMapView converts these to MapLibre LatLng).
 */
data class GeoPoint(val latitude: Double, val longitude: Double)

// ---------------------------------------------------------------------------
// Remote config  ->  GET /api/geo/app-config?app=solaris
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class GeoAppConfigDto(
    @Json(name = "app") val app: String? = null,
    @Json(name = "enabled") val enabled: Boolean = false,
    @Json(name = "maps_enabled") val mapsEnabled: Boolean = false,
    @Json(name = "weather_enabled") val weatherEnabled: Boolean = false,
    // Restricted, app-shipped Ola tile key — used ONLY to render map tiles on-device.
    @Json(name = "tile_key") val tileKey: String? = null,
    // Ola tiles base (e.g. https://api.olamaps.io) for building the style URL.
    @Json(name = "base_url") val baseUrl: String? = null,
    // Resolved feature flags (already AND-ed with provider availability + app gate).
    @Json(name = "features") val features: Map<String, Boolean> = emptyMap()
)

// ---------------------------------------------------------------------------
// Autocomplete  ->  GET /api/geo/autocomplete
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class GeoAutocompleteDto(
    @Json(name = "suggestions") val suggestions: List<GeoSuggestionDto> = emptyList(),
    @Json(name = "_disabled") val disabled: Boolean = false
)

@JsonClass(generateAdapter = true)
data class GeoSuggestionDto(
    @Json(name = "place_id") val placeId: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "subtitle") val subtitle: String? = null,
    @Json(name = "lat") val lat: Double? = null,
    @Json(name = "lon") val lon: Double? = null
)

// ---------------------------------------------------------------------------
// Place details  ->  GET /api/geo/place-details
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class GeoPlaceDetailsDto(
    @Json(name = "place_id") val placeId: String? = null,
    @Json(name = "lat") val lat: Double? = null,
    @Json(name = "lon") val lon: Double? = null,
    @Json(name = "address") val address: String? = null,
    @Json(name = "_disabled") val disabled: Boolean = false
)

// ---------------------------------------------------------------------------
// Reverse geocode  ->  GET /api/geo/reverse
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class GeoReverseDto(
    @Json(name = "address") val address: String? = null,
    @Json(name = "city") val city: String? = null,
    @Json(name = "pincode") val pincode: String? = null,
    @Json(name = "lat") val lat: Double? = null,
    @Json(name = "lon") val lon: Double? = null,
    @Json(name = "_disabled") val disabled: Boolean = false
)

// ---------------------------------------------------------------------------
// Directions  ->  GET /api/geo/directions
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class GeoDirectionsDto(
    // Google/Ola-encoded polyline (decode with OlaMapsRepository.decodePolyline).
    @Json(name = "polyline") val polyline: String? = null,
    @Json(name = "distance_m") val distanceMeters: Int? = null,
    @Json(name = "duration_s") val durationSeconds: Int? = null,
    @Json(name = "_disabled") val disabled: Boolean = false
)

/** Decoded route ready for the UI / map. */
data class OlaRouteResult(
    val points: List<GeoPoint>,
    val distanceMeters: Double?,
    val durationSeconds: Double?
)
