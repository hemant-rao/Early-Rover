package com.example.alarm.tracking

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * §806 — Early Rover tracking REST endpoints (base {server}/, e.g.
 * https://odiobook.com/). Every call except register carries the bearer token,
 * added by an OkHttp interceptor in [TrackingRepository].
 */
interface TrackingApi {

    @POST("api/earlyrover/v1/register")
    suspend fun register(@Body body: RegisterReq): RegisterResp

    @GET("api/earlyrover/v1/me")
    suspend fun me(): MeResp

    @PATCH("api/earlyrover/v1/me")
    suspend fun patchMe(@Body body: MePatchReq): MeResp

    @GET("api/earlyrover/v1/connections")
    suspend fun connections(): ConnectionsResp

    @POST("api/earlyrover/v1/connections/request")
    suspend fun requestConnection(@Body body: RoverCodeReq): RequestResp

    @POST("api/earlyrover/v1/connections/{id}/respond")
    suspend fun respondConnection(@Path("id") id: Int, @Body body: RespondReq): OkResp

    @POST("api/earlyrover/v1/connections/{id}/pause")
    suspend fun pauseConnection(@Path("id") id: Int, @Body body: PauseReq): ConnectionResp

    // §820 — expect="pending" makes the delete conditional: a cancel racing the
    // peer's accept 409s (NOT_PENDING) instead of destroying the new connection.
    @DELETE("api/earlyrover/v1/connections/{id}")
    suspend fun deleteConnection(@Path("id") id: Int,
                                 @Query("expect") expect: String? = null): OkResp

    @GET("api/earlyrover/v1/circles")
    suspend fun circles(): CirclesResp

    @POST("api/earlyrover/v1/circles")
    suspend fun createCircle(@Body body: CircleReq): CircleResp

    @POST("api/earlyrover/v1/circles/join")
    suspend fun joinCircle(@Body body: JoinReq): CircleResp

    @POST("api/earlyrover/v1/circles/{id}/leave")
    suspend fun leaveCircle(@Path("id") id: Int): OkResp

    @POST("api/earlyrover/v1/circles/{id}/remove")
    suspend fun removeMember(@Path("id") id: Int, @Body body: RemoveMemberReq): OkResp

    @GET("api/earlyrover/v1/places")
    suspend fun places(): PlacesResp

    @POST("api/earlyrover/v1/places")
    suspend fun createPlace(@Body body: PlaceReq): PlaceResp

    @DELETE("api/earlyrover/v1/places/{id}")
    suspend fun deletePlace(@Path("id") id: Int): OkResp

    @POST("api/earlyrover/v1/location")
    suspend fun postLocation(@Body body: LocationReq): OkResp

    @GET("api/earlyrover/v1/sos")
    suspend fun sos(): SosListResp

    @POST("api/earlyrover/v1/sos")
    suspend fun raiseSos(@Body body: SosReq): SosResp

    @POST("api/earlyrover/v1/sos/{id}/resolve")
    suspend fun resolveSos(@Path("id") id: Int): SosResp

    @POST("api/earlyrover/v1/push-token")
    suspend fun pushToken(@Body body: PushTokenReq): OkResp
}
