package com.example.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * §750 — thin Firebase Analytics (Google Analytics for Firebase) wrapper for the
 * Solar / "Early Rover" app.
 *
 * Firebase Analytics initialises from `app/google-services.json` at BUILD time
 * (the founder adds it from the Firebase console for package
 * `com.aistudio.sunalarm.pzhkrm2`; the GA4 measurement id is also recorded in the
 * OdioBook admin → Config → Branding → "Other Apps — Analytics & Ads" for
 * reference). Until that file is present the default FirebaseApp isn't
 * configured, so every call here NO-OPS gracefully instead of crashing.
 *
 * Usage: call [init] once from MainActivity.onCreate, then [screen]/[event].
 */
object SolarAnalytics {
    @Volatile private var fa: FirebaseAnalytics? = null

    /** Acquire the analytics instance. Safe to call before Firebase is configured. */
    fun init(context: Context) {
        if (fa != null) return
        fa = try {
            FirebaseAnalytics.getInstance(context.applicationContext)
        } catch (_: Throwable) {
            null // google-services.json not added yet → analytics stays disabled
        }
    }

    /** Log a screen view. Call when a navigation destination becomes visible. */
    fun screen(name: String) {
        log(FirebaseAnalytics.Event.SCREEN_VIEW, Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, name)
            putString(FirebaseAnalytics.Param.SCREEN_CLASS, name)
        })
    }

    /** Log a custom event with optional string params. */
    fun event(name: String, vararg params: Pair<String, String>) {
        log(name, if (params.isEmpty()) null else Bundle().apply {
            params.forEach { (k, v) -> putString(k, v) }
        })
    }

    private fun log(name: String, params: Bundle?) {
        try { fa?.logEvent(name, params) } catch (_: Throwable) { /* best-effort */ }
    }
}
