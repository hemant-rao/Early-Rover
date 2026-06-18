package com.example.alarm.maps

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Ola Maps REST endpoints (base https://api.olamaps.io). Auth is the API key passed as the
 * `api_key` query parameter on every call (see https://maps.olakrutrim.com/docs/auth).
 */
interface OlaMapsApi {

    @GET("places/v1/autocomplete")
    suspend fun autocomplete(
        @Query("input") input: String,
        @Query("api_key") apiKey: String,
        // Bias suggestions around the user when we know where they are (lat,lng).
        @Query("location") location: String? = null
    ): OlaAutocompleteResponse

    @GET("places/v1/details")
    suspend fun placeDetails(
        @Query("place_id") placeId: String,
        @Query("api_key") apiKey: String
    ): OlaPlaceDetailsResponse

    @GET("places/v1/geocode")
    suspend fun geocode(
        @Query("address") address: String,
        @Query("api_key") apiKey: String
    ): OlaReverseGeocodeResponse

    @GET("places/v1/reverse-geocode")
    suspend fun reverseGeocode(
        @Query("latlng") latlng: String,
        @Query("api_key") apiKey: String
    ): OlaReverseGeocodeResponse

    // Ola's directions endpoint is a POST that takes its parameters in the query string.
    @POST("routing/v1/directions")
    suspend fun directions(
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("api_key") apiKey: String
    ): OlaDirectionsResponse
}
