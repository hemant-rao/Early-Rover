package com.example.alarm.tracking

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * §806 — Early Rover identity storage (no login). Holds the server-issued bearer
 * token, our user id + Rover ID, and a stable device secret (used for deferred
 * cross-device re-claim). Plain SharedPreferences, matching the app's existing
 * ``sun_alarm_*_prefs`` pattern (no DataStore, no DI).
 */
object TrackingPrefs {

    private const val PREFS = "early_rover_prefs"
    private const val K_TOKEN = "er_token"
    private const val K_USER_ID = "er_user_id"
    private const val K_ROVER_ID = "er_rover_id"
    private const val K_NAME = "er_display_name"
    private const val K_DEVICE_SECRET = "er_device_secret"

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun sp(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Cached in-memory so the OkHttp auth interceptor reads it without a disk hit.
    @Volatile var token: String? = null
        private set

    val isRegistered: Boolean get() = !token.isNullOrBlank()

    fun load() {
        token = sp()?.getString(K_TOKEN, null)
    }

    fun save(token: String, userId: Int, roverId: String, displayName: String?) {
        this.token = token
        sp()?.edit()
            ?.putString(K_TOKEN, token)
            ?.putInt(K_USER_ID, userId)
            ?.putString(K_ROVER_ID, roverId)
            ?.putString(K_NAME, displayName)
            ?.apply()
    }

    fun userId(): Int = sp()?.getInt(K_USER_ID, 0) ?: 0
    fun roverId(): String = sp()?.getString(K_ROVER_ID, "") ?: ""
    fun displayName(): String? = sp()?.getString(K_NAME, null)

    fun setDisplayName(name: String?) {
        sp()?.edit()?.putString(K_NAME, name)?.apply()
    }

    /** A stable per-install secret; generated once. */
    fun deviceSecret(): String {
        val p = sp() ?: return ""
        var s = p.getString(K_DEVICE_SECRET, null)
        if (s.isNullOrBlank()) {
            s = UUID.randomUUID().toString() + UUID.randomUUID().toString()
            p.edit().putString(K_DEVICE_SECRET, s).apply()
        }
        return s
    }
}
