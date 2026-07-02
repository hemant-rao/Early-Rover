package com.example.alarm.tracking

import android.content.Context
import android.util.Log
import com.example.alarm.maps.OlaMapsRepository
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * §806 — Early Rover tracking client. Kotlin ``object`` singleton (matches
 * [OlaMapsRepository]); no DI. Reuses the SAME OdioBook base URL as the geo
 * gateway ([OlaMapsRepository.serverBaseUrl]) and attaches the bearer token from
 * [TrackingPrefs] via an interceptor. All calls are main-safe (Dispatchers.IO)
 * and rethrow — the ViewModel decides how to surface failures.
 */
object TrackingRepository {

    private const val TAG = "TrackingRepository"

    @Volatile private var _api: TrackingApi? = null
    @Volatile private var _apiBase: String = ""
    val moshi: Moshi = Moshi.Builder().build()

    fun init(context: Context) {
        TrackingPrefs.init(context)
        TrackingPrefs.load()
    }

    private fun normalizeBase(s: String): String {
        val t = s.trim().ifBlank { OlaMapsRepository.DEFAULT_SERVER }
        return if (t.endsWith("/")) t else "$t/"
    }

    private fun api(): TrackingApi {
        val base = normalizeBase(OlaMapsRepository.serverBaseUrl)
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
                    .addInterceptor { chain ->
                        val token = TrackingPrefs.token
                        val req = if (!token.isNullOrBlank()) {
                            chain.request().newBuilder()
                                .addHeader("Authorization", "Bearer $token").build()
                        } else chain.request()
                        chain.proceed(req)
                    }
                    .build()
                val built = Retrofit.Builder()
                    .baseUrl(base)
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                    .create(TrackingApi::class.java)
                _api = built
                _apiBase = base
                built
            }
        }
    }

    /** Registers on first use (persisting the token) and returns our profile. */
    suspend fun ensureRegistered(displayName: String?): UserDto = withContext(Dispatchers.IO) {
        if (TrackingPrefs.isRegistered) {
            try {
                return@withContext api().me().user
            } catch (e: Exception) {
                Log.w(TAG, "me() failed, will re-register: ${e.message}")
            }
        }
        val resp = api().register(
            RegisterReq(displayName = displayName, deviceSecret = TrackingPrefs.deviceSecret())
        )
        TrackingPrefs.save(resp.token, resp.user.id, resp.user.roverId, resp.user.displayName)
        // Rebuild the client so the interceptor picks up the freshly-saved token.
        synchronized(this@TrackingRepository) { _api = null }
        resp.user
    }

    suspend fun patchMe(body: MePatchReq): UserDto = io { api().patchMe(body).user }
    suspend fun connections(): ConnectionsResp = io { api().connections() }
    suspend fun requestConnection(code: String): RequestResp = io { api().requestConnection(RoverCodeReq(code)) }
    suspend fun respond(id: Int, accept: Boolean): OkResp = io { api().respondConnection(id, RespondReq(accept)) }
    suspend fun pause(id: Int, on: Boolean): ConnectionResp = io { api().pauseConnection(id, PauseReq(on)) }
    suspend fun removeConnection(id: Int): OkResp = io { api().deleteConnection(id) }

    suspend fun circles(): CirclesResp = io { api().circles() }
    suspend fun createCircle(name: String, color: String?): CircleResp = io { api().createCircle(CircleReq(name, color)) }
    suspend fun joinCircle(code: String): CircleResp = io { api().joinCircle(JoinReq(code)) }
    suspend fun leaveCircle(id: Int): OkResp = io { api().leaveCircle(id) }
    suspend fun removeMember(id: Int, userId: Int): OkResp = io { api().removeMember(id, RemoveMemberReq(userId)) }

    suspend fun places(): PlacesResp = io { api().places() }
    suspend fun createPlace(name: String, lat: Double, lon: Double, radiusM: Int): PlaceResp =
        io { api().createPlace(PlaceReq(name, lat, lon, radiusM)) }
    suspend fun deletePlace(id: Int): OkResp = io { api().deletePlace(id) }

    suspend fun postLocation(body: LocationReq): OkResp = io { api().postLocation(body) }

    suspend fun sos(): SosListResp = io { api().sos() }
    suspend fun raiseSos(body: SosReq): SosResp = io { api().raiseSos(body) }
    suspend fun resolveSos(id: Int): SosResp = io { api().resolveSos(id) }

    suspend fun pushToken(token: String): OkResp = io { api().pushToken(PushTokenReq(token)) }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    /** ws(s):// URL for the live relay, derived from the configured base. */
    fun socketUrl(): String {
        val base = OlaMapsRepository.serverBaseUrl.trim().trimEnd('/')
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$ws/ws/earlyrover"
    }
}
