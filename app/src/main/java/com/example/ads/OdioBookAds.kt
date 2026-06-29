package com.example.ads

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * §778 — OdioBook config-driven AdMob ads.
 *
 * ONE self-contained file (no Retrofit/Moshi/Coil — only play-services-ads), kept
 * COPY-IDENTICAL across Early Rover / Dig Deep / Xello Mind, exactly like the §777
 * OdioBookFamilySection. It fetches THIS app's ads slice from the OdioBook backend
 * (GET https://odiobook.com/api/apps/ads?app=<slug>) on a background thread and
 * exposes it as Compose snapshot state so the banner appears the moment it loads.
 *
 * Rules (founder, §778):
 *  • Ads show ONLY when the admin master switch is on AND this app is enabled.
 *    No id / off  → nothing renders. prod & dev behave identically (no env code).
 *  • "Just add the id in admin and ads start": in LIVE mode a configured ad-unit id
 *    is used; an empty one means that format isn't requested. TEST mode serves
 *    Google's official sample units so wiring can be verified safely.
 *  • Full-screen (interstitial) ads are paced (every-N-actions / min-interval /
 *    per-session) and capped at `max_per_day` (default 5). After the daily cap the
 *    app stops showing interstitials until the next LOCAL day; banners may continue.
 *    The daily count is persisted in SharedPreferences (keyed by date) so it
 *    survives restarts.
 *
 * Wiring per app (3 lines):
 *   1) MainActivity.onCreate:  OdioBookAds.init(applicationContext, "earlyrover")
 *   2) home screen Column:     OdioBookAds.Banner()
 *   3) a natural action:       OdioBookAds.maybeShowInterstitial(activity)
 */
object OdioBookAds {
    private const val BASE_URL = "https://odiobook.com"
    // Google's official sample units — safe to ship; only ever serve TEST ads.
    private const val TEST_BANNER = "ca-app-pub-3940256099942544/6300978111"
    private const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    private const val PREFS = "odiobook_ads"

    private val io = Executors.newSingleThreadExecutor()

    // Compose snapshot state: writing from a background thread is safe and triggers
    // recomposition of any Banner() reading it.
    private val configState = mutableStateOf<AdsSlice?>(null)

    @Volatile private var fetchStarted = false
    @Volatile private var mobileAdsInit = false

    data class AdsSlice(
        val enabled: Boolean,
        val testMode: Boolean,
        val bannerUnit: String,
        val interstitialUnit: String,
        val showBanner: Boolean,
        val showInterstitial: Boolean,
        val everyNActions: Int,
        val minIntervalSec: Int,
        val maxPerSession: Int,
        val maxPerDay: Int,
    )

    // Interstitial pacing — per-process counters; the per-day count is persisted.
    private var actionCount = 0
    private var shownThisSession = 0
    private var lastShownAtMs = 0L
    @Volatile private var loaded: InterstitialAd? = null
    @Volatile private var loading = false

    /** One-time MobileAds init + background config fetch. Safe to call repeatedly. */
    fun init(context: Context, appSlug: String) {
        val app = context.applicationContext
        if (!mobileAdsInit) {
            mobileAdsInit = true
            runCatching { MobileAds.initialize(app) {} }
        }
        if (fetchStarted) return
        fetchStarted = true
        io.execute { runCatching { configState.value = fetch(appSlug) } }
    }

    private fun fetch(appSlug: String): AdsSlice? {
        val url = URL("$BASE_URL/api/apps/ads?app=$appSlug")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            if (!o.optBoolean("enabled", false)) {
                return AdsSlice(false, true, "", "", false, false, 0, 0, 0, 0)
            }
            val units = o.optJSONObject("ad_units") ?: JSONObject()
            val freq = o.optJSONObject("frequency") ?: JSONObject()
            return AdsSlice(
                enabled = true,
                testMode = o.optBoolean("test_mode", true),
                bannerUnit = units.optString("banner", ""),
                interstitialUnit = units.optString("interstitial", ""),
                showBanner = o.optBoolean("show_banner", true),
                showInterstitial = o.optBoolean("show_interstitial", true),
                everyNActions = freq.optInt("interstitial_every_n_actions", 3),
                minIntervalSec = freq.optInt("min_interval_sec", 60),
                maxPerSession = freq.optInt("max_per_session", 5),
                maxPerDay = freq.optInt("max_per_day", 5),
            )
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun bannerUnit(c: AdsSlice): String =
        if (c.bannerUnit.isNotEmpty() && !c.testMode) c.bannerUnit else TEST_BANNER

    private fun interstitialUnit(c: AdsSlice): String =
        if (c.interstitialUnit.isNotEmpty() && !c.testMode) c.interstitialUnit else TEST_INTERSTITIAL

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun dayCount(context: Context): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.getString("day", "") == today()) p.getInt("count", 0) else 0
    }

    private fun bumpDayCount(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = if (p.getString("day", "") == today()) p.getInt("count", 0) + 1 else 1
        p.edit().putString("day", today()).putInt("count", n).apply()
    }

    /** A bottom banner for the app's home screen. Renders nothing until ads load and
     *  the admin has banners on. Drop into any Column. */
    @Composable
    fun Banner(modifier: Modifier = Modifier) {
        val cfg = configState.value ?: return
        if (!cfg.enabled || !cfg.showBanner) return
        val unit = bannerUnit(cfg)
        AndroidView(
            modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
            factory = { ctx ->
                AdView(ctx).apply {
                    setAdSize(AdSize.BANNER)
                    adUnitId = unit
                    runCatching { loadAd(AdRequest.Builder().build()) }
                }
            },
        )
    }

    /** Call at a natural break point (alarm dismissed, shred finished, session ended).
     *  Shows a full-screen ad only when enabled + paced + under the per-session AND
     *  per-day caps. No-op otherwise. */
    fun maybeShowInterstitial(activity: Activity) {
        val cfg = configState.value ?: return
        if (!cfg.enabled || !cfg.showInterstitial) return
        actionCount += 1
        if (cfg.maxPerSession in 1..shownThisSession) return
        if (cfg.maxPerDay in 1..dayCount(activity)) return                 // §778 daily cap
        if (cfg.everyNActions > 0 && actionCount % cfg.everyNActions != 0) { preload(activity, cfg); return }
        val now = System.currentTimeMillis()
        if (cfg.minIntervalSec > 0 && now - lastShownAtMs < cfg.minIntervalSec * 1000L) return
        val ready = loaded
        if (ready != null) {
            loaded = null
            shownThisSession += 1
            lastShownAtMs = now
            bumpDayCount(activity)
            runCatching { ready.show(activity) }
            preload(activity, cfg)
        } else {
            preload(activity, cfg)                                          // warm up for next time
        }
    }

    private fun preload(context: Context, cfg: AdsSlice) {
        if (loading || loaded != null) return
        if (cfg.maxPerDay in 1..dayCount(context)) return
        loading = true
        val unit = interstitialUnit(cfg)
        runCatching {
            InterstitialAd.load(
                context, unit, AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) { loaded = ad; loading = false }
                    override fun onAdFailedToLoad(error: LoadAdError) { loaded = null; loading = false }
                },
            )
        }.onFailure { loading = false }
    }
}
