package com.example.alarm.maps

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * §689 — OdioBook GEO gateway REST endpoints (base {server}/, e.g.
 * https://odiobook.com/). The app passes NO API key — the OdioBook backend
 * injects the admin-managed Ola REST key server-side. ``app`` identifies this
 * client ("earlyrover") so the admin can toggle features per app.
 */
interface OlaMapsApi {

    @GET("api/geo/app-config")
    suspend fun appConfig(
        @Query("app") app: String
    ): GeoAppConfigDto

    @GET("api/geo/autocomplete")
    suspend fun autocomplete(
        @Query("q") input: String,
        @Query("app") app: String,
        // Bias suggestions around the user when we know where they are.
        @Query("lat") lat: Double? = null,
        @Query("lon") lon: Double? = null
    ): GeoAutocompleteDto

    @GET("api/geo/place-details")
    suspend fun placeDetails(
        @Query("place_id") placeId: String,
        @Query("app") app: String
    ): GeoPlaceDetailsDto

    @GET("api/geo/reverse")
    suspend fun reverse(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("app") app: String
    ): GeoReverseDto

    @GET("api/geo/directions")
    suspend fun directions(
        @Query("from_lat") fromLat: Double,
        @Query("from_lon") fromLon: Double,
        @Query("to_lat") toLat: Double,
        @Query("to_lon") toLon: Double,
        @Query("app") app: String
    ): GeoDirectionsDto
}
