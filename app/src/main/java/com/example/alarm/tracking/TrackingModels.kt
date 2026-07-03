package com.example.alarm.tracking

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * §806 — DTOs for the OdioBook Early Rover tracking API (base
 * {server}/api/earlyrover/v1/). Moshi @JsonClass adapters; unknown JSON keys are
 * ignored, so the app tolerates the backend adding fields. Mirrors the backend
 * serializers in app/earlyrover/service.py.
 */

// ── identity ─────────────────────────────────────────────────────────────────
@JsonClass(generateAdapter = true)
data class UserDto(
    val id: Int = 0,
    @Json(name = "rover_id") val roverId: String = "",
    @Json(name = "rover_code") val roverCode: String? = null,
    @Json(name = "display_name") val displayName: String? = null,
    val color: String? = null,
    @Json(name = "avatar_url") val avatarUrl: String? = null,
    val paused: Boolean = false,
    val ghost: Boolean = false,
    @Json(name = "share_precise") val sharePrecise: Boolean = true,
    @Json(name = "last_seen_at") val lastSeenAt: String? = null
)

@JsonClass(generateAdapter = true)
data class PublicUser(
    val id: Int = 0,
    @Json(name = "rover_id") val roverId: String? = null,
    @Json(name = "display_name") val displayName: String? = null,
    val color: String? = null,
    @Json(name = "avatar_url") val avatarUrl: String? = null,
    @Json(name = "last_seen_at") val lastSeenAt: String? = null
)

@JsonClass(generateAdapter = true)
data class RegisterResp(val token: String = "", val user: UserDto = UserDto())

@JsonClass(generateAdapter = true)
data class MeResp(val user: UserDto = UserDto())

// ── location ─────────────────────────────────────────────────────────────────
@JsonClass(generateAdapter = true)
data class LocationDto(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val accuracy: Double? = null,
    val heading: Double? = null,
    val speed: Double? = null,
    val battery: Int? = null,
    @Json(name = "is_charging") val isCharging: Boolean? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    val ghost: Boolean? = null
)

// ── connections ────────────────────────────────────────────────────────────
@JsonClass(generateAdapter = true)
data class ConnectionDto(
    val id: Int = 0,
    @Json(name = "peer_id") val peerId: Int = 0,
    val status: String = "pending",
    val incoming: Boolean = false,
    val outgoing: Boolean = false,
    @Json(name = "paused_by_me") val pausedByMe: Boolean = false,
    @Json(name = "paused_by_peer") val pausedByPeer: Boolean = false,
    val peer: PublicUser? = null,
    val location: LocationDto? = null,
    @Json(name = "distance_m") val distanceM: Double? = null,
    @Json(name = "bearing_deg") val bearingDeg: Double? = null
)

@JsonClass(generateAdapter = true)
data class ConnectionsResp(
    val connections: List<ConnectionDto> = emptyList(),
    @Json(name = "me_location") val meLocation: LocationDto? = null
)

@JsonClass(generateAdapter = true)
data class RequestResp(
    val connection: ConnectionDto = ConnectionDto(),
    @Json(name = "auto_accepted") val autoAccepted: Boolean = false,
    val peer: PublicUser? = null
)

@JsonClass(generateAdapter = true)
data class ConnectionResp(val connection: ConnectionDto = ConnectionDto())

// ── circles ──────────────────────────────────────────────────────────────────
@JsonClass(generateAdapter = true)
data class CircleMember(
    val id: Int = 0,
    @Json(name = "display_name") val displayName: String? = null,
    @Json(name = "rover_id") val roverId: String? = null,
    val color: String? = null,
    val role: String = "member",
    // §810 — privacy-gated last-known location (null when the member hides from me).
    // Lets a whole family circle land on the map without pairwise connections.
    val location: LocationDto? = null
)

@JsonClass(generateAdapter = true)
data class CircleDto(
    val id: Int = 0,
    val name: String = "",
    @Json(name = "invite_code") val inviteCode: String = "",
    val color: String? = null,
    @Json(name = "is_owner") val isOwner: Boolean = false,
    @Json(name = "member_count") val memberCount: Int = 0,
    val members: List<CircleMember> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CirclesResp(val circles: List<CircleDto> = emptyList())

@JsonClass(generateAdapter = true)
data class CircleResp(val circle: CircleDto = CircleDto())

// ── places ─────────────────────────────────────────────────────────────────
@JsonClass(generateAdapter = true)
data class PlaceDto(
    val id: Int = 0,
    val name: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    @Json(name = "radius_m") val radiusM: Int = 150,
    val inside: Boolean = false
)

@JsonClass(generateAdapter = true)
data class PlacesResp(val places: List<PlaceDto> = emptyList())

@JsonClass(generateAdapter = true)
data class PlaceResp(val place: PlaceDto = PlaceDto())

// ── SOS ──────────────────────────────────────────────────────────────────────
@JsonClass(generateAdapter = true)
data class SosDto(
    val id: Int = 0,
    val kind: String = "sos",
    val lat: Double? = null,
    val lon: Double? = null,
    val message: String? = null,
    val active: Boolean = true,
    val user: PublicUser? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class SosListResp(val events: List<SosDto> = emptyList())

@JsonClass(generateAdapter = true)
data class SosResp(val sos: SosDto = SosDto())

// ── request bodies ────────────────────────────────────────────────────────
@JsonClass(generateAdapter = true)
data class RegisterReq(
    @Json(name = "display_name") val displayName: String? = null,
    val color: String? = null,
    @Json(name = "device_secret") val deviceSecret: String? = null
)

@JsonClass(generateAdapter = true)
data class MePatchReq(
    @Json(name = "display_name") val displayName: String? = null,
    val color: String? = null,
    @Json(name = "avatar_url") val avatarUrl: String? = null,
    val paused: Boolean? = null,
    val ghost: Boolean? = null,
    @Json(name = "share_precise") val sharePrecise: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class RoverCodeReq(@Json(name = "rover_code") val roverCode: String)

@JsonClass(generateAdapter = true)
data class RespondReq(val accept: Boolean)

@JsonClass(generateAdapter = true)
data class PauseReq(val on: Boolean)

@JsonClass(generateAdapter = true)
data class CircleReq(val name: String, val color: String? = null)

@JsonClass(generateAdapter = true)
data class JoinReq(@Json(name = "invite_code") val inviteCode: String)

@JsonClass(generateAdapter = true)
data class RemoveMemberReq(@Json(name = "user_id") val userId: Int)

@JsonClass(generateAdapter = true)
data class PlaceReq(
    val name: String,
    val lat: Double,
    val lon: Double,
    @Json(name = "radius_m") val radiusM: Int = 150
)

@JsonClass(generateAdapter = true)
data class LocationReq(
    val lat: Double,
    val lon: Double,
    val accuracy: Double? = null,
    val heading: Double? = null,
    val speed: Double? = null,
    val battery: Int? = null,
    @Json(name = "is_charging") val isCharging: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class SosReq(
    val kind: String = "sos",
    val lat: Double? = null,
    val lon: Double? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class PushTokenReq(
    @Json(name = "fcm_token") val fcmToken: String,
    val platform: String = "android"
)

@JsonClass(generateAdapter = true)
data class OkResp(val ok: Boolean = true)
