package com.example.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * §750 — smart, NON-INTRUSIVE AdMob banner for the Solar / "Early Rover" app.
 *
 * Philosophy mirrors OdioBook's "don't annoy the user" ads:
 *   • exactly ONE small banner, pinned to the bottom of the dashboard list, so it
 *     scrolls past with the content and never overlaps the clock, sun cards, or
 *     controls;
 *   • NO interstitials, NO pop-ups, NO full-screen takeovers;
 *   • fails silently — if AdMob isn't configured the slot simply renders nothing.
 *
 * The ids here are Google's official TEST ids (safe to ship — they only ever
 * serve test ads). The founder swaps them for the real ones recorded in the
 * OdioBook admin (Config → Branding → "Other Apps — Analytics & Ads"):
 *   • the AdMob *app* id lives in AndroidManifest.xml
 *     (com.google.android.gms.ads.APPLICATION_ID);
 *   • the *banner unit* id is [BANNER_UNIT_ID] below.
 */
object SolarAds {
    /** Google's official TEST banner unit id. Replace with the real
     *  `solarAdmobBannerId` before a production release. */
    const val BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
}

@Composable
fun SolarBannerAd(modifier: Modifier = Modifier) {
    // Never inflate a real AdView in Compose previews / screenshot tests.
    if (LocalInspectionMode.current) return

    val context = LocalContext.current
    // Build the AdView once and load a single request. Wrapped so a missing /
    // misconfigured AdMob setup (e.g. no APPLICATION_ID meta-data) can never
    // crash the host screen — the banner just doesn't appear.
    val adView = remember {
        runCatching {
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = SolarAds.BANNER_UNIT_ID
                loadAd(AdRequest.Builder().build())
            }
        }.getOrNull()
    }

    if (adView != null) {
        AndroidView(modifier = modifier.fillMaxWidth(), factory = { adView })
    }
}
