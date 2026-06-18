package com.example.alarm.maps

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Lightweight lat/lng pair used across the Ola Maps layer so the repository / DTOs do
 * not depend on MapLibre types (OlaMapView converts these to MapLibre LatLng).
 */
data class GeoPoint(val latitude: Double, val longitude: Double)

// ---------------------------------------------------------------------------
// Places Autocomplete  ->  GET /places/v1/autocomplete
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class OlaAutocompleteResponse(
    @Json(name = "predictions") val predictions: List<OlaPrediction> = emptyList(),
    @Json(name = "status") val status: String? = null
)

@JsonClass(generateAdapter = true)
data class OlaPrediction(
    @Json(name = "description") val description: String? = null,
    @Json(name = "place_id") val placeId: String? = null,
    @Json(name = "structured_formatting") val structuredFormatting: OlaStructuredFormatting? = null,
    @Json(name = "geometry") val geometry: OlaGeometry? = null
)

@JsonClass(generateAdapter = true)
data class OlaStructuredFormatting(
    @Json(name = "main_text") val mainText: String? = null,
    @Json(name = "secondary_text") val secondaryText: String? = null
)

@JsonClass(generateAdapter = true)
data class OlaGeometry(
    @Json(name = "location") val location: OlaLatLng? = null
)

@JsonClass(generateAdapter = true)
data class OlaLatLng(
    @Json(name = "lat") val lat: Double? = null,
    @Json(name = "lng") val lng: Double? = null
)

// ---------------------------------------------------------------------------
// Place Details  ->  GET /places/v1/details
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class OlaPlaceDetailsResponse(
    @Json(name = "result") val result: OlaPlaceDetailsResult? = null,
    @Json(name = "status") val status: String? = null
)

@JsonClass(generateAdapter = true)
data class OlaPlaceDetailsResult(
    @Json(name = "name") val name: String? = null,
    @Json(name = "formatted_address") val formattedAddress: String? = null,
    @Json(name = "geometry") val geometry: OlaGeometry? = null
)

// ---------------------------------------------------------------------------
// Reverse Geocode  ->  GET /places/v1/reverse-geocode
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class OlaReverseGeocodeResponse(
    @Json(name = "results") val results: List<OlaReverseResult> = emptyList(),
    @Json(name = "status") val status: String? = null
)

@JsonClass(generateAdapter = true)
data class OlaReverseResult(
    @Json(name = "formatted_address") val formattedAddress: String? = null,
    @Json(name = "geometry") val geometry: OlaGeometry? = null
)

// ---------------------------------------------------------------------------
// Directions (route)  ->  POST /routing/v1/directions
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class OlaDirectionsResponse(
    @Json(name = "routes") val routes: List<OlaRoute> = emptyList(),
    @Json(name = "status") val status: String? = null
)

@JsonClass(generateAdapter = true)
data class OlaRoute(
    // Ola returns the full route geometry as an encoded polyline string.
    @Json(name = "overview_polyline") val overviewPolyline: String? = null,
    @Json(name = "legs") val legs: List<OlaLeg> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OlaLeg(
    // Ola reports these in meters / seconds plus human-readable variants.
    @Json(name = "distance") val distanceMeters: Double? = null,
    @Json(name = "duration") val durationSeconds: Double? = null,
    @Json(name = "readable_distance") val readableDistance: String? = null,
    @Json(name = "readable_duration") val readableDuration: String? = null
)

/** Decoded route ready for the UI / map. */
data class OlaRouteResult(
    val points: List<GeoPoint>,
    val distanceMeters: Double?,
    val durationSeconds: Double?
)
