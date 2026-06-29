package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * §777 / §780 — "The OdioBook Family" cross-promotion section.
 *
 * A single, self-contained Composable shared (copy-identical) across every
 * OdioBook-family Android app — Veda Drop, Early Rover, Dig Deep & Xello Mind.
 * It turns each app into a discovery surface for the whole family, the way big
 * product houses cross-promote their suite: every app quietly tells the user
 * about the others, so a download of one can lift them all, and every tap funnels
 * traffic back to odiobook.com.
 *
 * §780 — the sibling list is now DYNAMIC. It is fetched live from the OdioBook
 * backend directory (GET https://odiobook.com/api/showcase/apps — the same source
 * that powers odiobook.com/apps and the admin "Showcase → Apps" console) so adding,
 * renaming or re-tagging an app in admin propagates to every installed app on its
 * next open — no APK rebuild. Until then this APK had to be rebuilt for every
 * directory change (e.g. when Xello Mind joined in §777).
 *
 * Still deliberately dependency-free: the fetch uses only HttpURLConnection +
 * org.json on a background thread (exactly like com.example.ads.OdioBookAds), and
 * the UI is pure Compose + Material3 theme colours (so it inherits each app's look
 * automatically) + the bundled `odiobook_logo` drawable. No Retrofit, no Moshi, no
 * Coil, no new Gradle deps. A bundled fallback list renders instantly before the
 * live list loads and whenever the device is offline, so the section is never blank.
 *
 * @param currentAppTitle the host app's family title (e.g. "Early Rover") so its
 *        own card renders as "You're here" instead of a tappable link.
 */
private data class FamilyApp(
    val title: String,
    val tagline: String,
    val accent: Color,
    val url: String,
)

private const val ODIOBOOK_HOME = "https://odiobook.com"
private const val ODIOBOOK_APPS_HUB = "https://odiobook.com/apps"
// §787 — DailyFoodServe is a WEB member of the family with a known public URL, so
// (unlike the Android siblings whose Play links aren't known offline) its fallback
// row links straight to the site. The live directory returns the same store_url.
private const val DAILYFOODSERVE_URL = "https://dailyfoodserve.com/"

// OdioBook is the constant parent studio — always shown first, always points to
// the main site. Only the sibling apps below it are fetched live.
private val ODIOBOOK_ENTRY = FamilyApp(
    "OdioBook",
    "AI voice cloning, text-to-speech & studio",
    Color(0xFF6D5EF6),
    ODIOBOOK_HOME,
)

// Bundled fallback for the siblings — shown until the live directory loads and
// whenever the device is offline. Mirrors the public odiobook.com/apps order.
private val FALLBACK_SIBLINGS = listOf(
    FamilyApp("Veda Drop", "Women-only beauty & wellness booking", Color(0xFF00AAAD), ODIOBOOK_APPS_HUB),
    FamilyApp("Early Rover", "Smart alarm, weather & travel wake-up", Color(0xFFF5A623), ODIOBOOK_APPS_HUB),
    FamilyApp("Dig Deep", "Secure data shredder & recovery", Color(0xFF10B981), ODIOBOOK_APPS_HUB),
    FamilyApp("Xello Mind", "Active-memory trainer with speech feedback", Color(0xFF13B4A2), ODIOBOOK_APPS_HUB),
    FamilyApp("DailyFoodServe", "Fresh home-style meals, served daily", Color(0xFF055160), DAILYFOODSERVE_URL),
)

// Keep the established brand colours for the known apps; derive a stable, pleasant
// accent for any NEW app the backend returns (so the monogram tile has colour
// without the backend needing to store one).
private val KNOWN_ACCENTS = mapOf(
    "odiobook" to Color(0xFF6D5EF6),
    "veda drop" to Color(0xFF00AAAD),
    "early rover" to Color(0xFFF5A623),
    "dig deep" to Color(0xFF10B981),
    "xello mind" to Color(0xFF13B4A2),
    "dailyfoodserve" to Color(0xFF055160),
)
private val ACCENT_PALETTE = listOf(
    Color(0xFF6D5EF6), Color(0xFF00AAAD), Color(0xFFF5A623), Color(0xFF10B981),
    Color(0xFF13B4A2), Color(0xFFEF6C75), Color(0xFF7C8DFF), Color(0xFFE8A13A),
)

private fun accentFor(title: String): Color {
    val key = title.trim().lowercase()
    KNOWN_ACCENTS[key]?.let { return it }
    val size = ACCENT_PALETTE.size
    val idx = ((key.hashCode() % size) + size) % size   // always non-negative
    return ACCENT_PALETTE[idx]
}

/**
 * Live OdioBook family directory. Fetches the sibling list ONCE per process on a
 * background thread and exposes it as Compose snapshot state (so the rows refresh
 * the moment it loads). Mirrors com.example.ads.OdioBookAds — dependency-free,
 * fail-soft (any error leaves the bundled fallback in place).
 */
private object FamilyDirectory {
    private const val URL_STR = "$ODIOBOOK_HOME/api/showcase/apps"
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var loading = false

    // null = not loaded yet (caller shows the bundled fallback).
    val state = mutableStateOf<List<FamilyApp>?>(null)

    /** Kick off the fetch if it hasn't succeeded yet. Safe to call on every
     *  recomposition: it no-ops once loaded or while a fetch is in flight, but a
     *  FAILED attempt does NOT latch — reopening the section retries, so a device
     *  that was offline at first open still picks up the live list on a later open. */
    fun ensureLoaded() {
        if (state.value != null || loading) return
        loading = true
        io.execute {
            val result = runCatching { fetch() }.getOrNull()
            if (result != null) state.value = result
            loading = false
        }
    }

    private fun fetch(): List<FamilyApp>? {
        val conn = (URL(URL_STR).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val items = JSONObject(text).optJSONArray("items") ?: return null
            val out = ArrayList<FamilyApp>(items.length())
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val title = if (o.isNull("title")) "" else o.optString("title", "").trim()
                if (title.isEmpty()) continue
                val tagline = if (o.isNull("tagline")) "" else o.optString("tagline", "").trim()
                val store = if (o.isNull("store_url")) "" else o.optString("store_url", "").trim()
                out.add(
                    FamilyApp(
                        title = title,
                        tagline = tagline,
                        accent = accentFor(title),
                        url = if (store.isNotEmpty()) store else ODIOBOOK_APPS_HUB,
                    )
                )
            }
            // Empty payload (e.g. admin disabled the section) → keep the fallback.
            return if (out.isEmpty()) null else out
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}

@Composable
fun OdioBookFamilySection(
    currentAppTitle: String,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { FamilyDirectory.ensureLoaded() }

    val uriHandler = LocalUriHandler.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = onSurface.copy(alpha = 0.62f)
    val primary = MaterialTheme.colorScheme.primary

    // OdioBook (the parent) is always first; the siblings come live from the
    // backend, falling back to the bundled list before they load / when offline.
    val siblings = FamilyDirectory.state.value ?: FALLBACK_SIBLINGS
    val apps = listOf(ODIOBOOK_ENTRY) + siblings

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "OUR APPS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = primary,
        )
        Spacer(Modifier.height(10.dp))

        // OdioBook header — logo + family mission, tap to open the main site.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { uriHandler.openUri(ODIOBOOK_HOME) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.drawable.odiobook_logo),
                contentDescription = "OdioBook",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "The OdioBook Family",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "One studio, a family of apps that make everyday life easier.",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = muted,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        apps.forEach { app ->
            FamilyAppRow(
                app = app,
                isCurrent = app.title.equals(currentAppTitle, ignoreCase = true),
                onOpen = { uriHandler.openUri(app.url) },
                onSurface = onSurface,
                muted = muted,
                primary = primary,
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { uriHandler.openUri(ODIOBOOK_HOME) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Explore everything on OdioBook.com")
        }
    }
}

@Composable
private fun FamilyAppRow(
    app: FamilyApp,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onSurface: Color,
    muted: Color,
    primary: Color,
) {
    val shape = RoundedCornerShape(16.dp)
    var rowModifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .border(1.dp, onSurface.copy(alpha = 0.12f), shape)
    if (!isCurrent) {
        rowModifier = rowModifier.clickable { onOpen() }
    }

    Row(
        modifier = rowModifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Monogram tile in the app's accent colour.
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(app.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.title.take(1),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                )
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(primary.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "You're here",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = app.tagline,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = muted,
            )
        }
        if (!isCurrent) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "›",
                fontSize = 22.sp,
                color = muted,
            )
        }
    }
}
