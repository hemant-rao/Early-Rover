package com.example.ui

import android.content.Context
import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * §777 / §780 / §825 — "The OdioBook Family" cross-promotion section.
 *
 * A single, self-contained Composable shared (copy-identical) across every
 * OdioBook-family Android app — Veda Drop, Early Rover, Traces Wiper & Xello Mind.
 * It turns each app into a discovery surface for the whole family, the way big
 * product houses cross-promote their suite: every app quietly tells the user about
 * the others, so a download of one can lift them all, and every tap funnels traffic
 * back to odiobook.com.
 *
 * §780 — the sibling list is DYNAMIC, fetched from the OdioBook backend directory
 * (GET https://odiobook.com/api/showcase/apps — the same source that powers
 * odiobook.com/apps and the admin "Showcase → Apps" console), so adding, renaming or
 * re-tagging an app in admin propagates to every installed app — no APK rebuild.
 *
 * §825 — now icon + title + tagline are ALL dynamic, and the section is cached so it
 * renders instantly and offline:
 *   * the directory JSON is persisted to SharedPreferences and the real icons to
 *     files (filesDir/family_icons) — subsequent opens paint from cache with no
 *     network, so it works offline after the first successful load;
 *   * the cache is refreshed at most WEEKLY, or immediately after an app update
 *     (versionCode change). It's informational, so it deliberately does NOT re-fetch
 *     on every open — once loaded it stays put until the weekly window elapses.
 *   * icons come from the backend as PNG (`icon_png_url`) because BitmapFactory can't
 *     decode SVG; an app with no raster icon yet falls back to a monogram tile.
 *
 * Still deliberately dependency-free: HttpURLConnection + org.json + BitmapFactory +
 * SharedPreferences + java.io on a background thread. No Retrofit, Moshi, Coil or any
 * new Gradle dep. A bundled fallback list renders instantly before the first load.
 *
 * @param currentAppTitle the host app's family title (e.g. "Early Rover") so its own
 *        card renders as "You're here" instead of a tappable link.
 */
private data class FamilyApp(
    val title: String,
    val tagline: String,
    val accent: Color,
    val url: String,
    val iconUrl: String? = null,      // §825 absolute PNG url from the backend
    val bundledRes: Int? = null,      // §825 bundled drawable (OdioBook parent only)
)

private const val ODIOBOOK_HOME = "https://odiobook.com"
private const val ODIOBOOK_APPS_HUB = "https://odiobook.com/apps"
// §787 — DailyFoodServe is a WEB member of the family with a known public URL, so
// (unlike the Android siblings whose Play links aren't known offline) its fallback
// row links straight to the site. The live directory returns the same store_url.
private const val DAILYFOODSERVE_URL = "https://dailyfoodserve.com/"

// OdioBook is the constant parent studio — always shown first, always points to the
// main site, always uses its bundled brand mark. Only the siblings are fetched live.
private val ODIOBOOK_ENTRY = FamilyApp(
    "OdioBook",
    "AI voice cloning, text-to-speech & studio",
    Color(0xFF6D5EF6),
    ODIOBOOK_HOME,
    bundledRes = R.drawable.odiobook_logo,
)

// Bundled fallback for the siblings — shown until the live directory loads and
// whenever the device is offline with no cache yet. Mirrors odiobook.com/apps order.
private val FALLBACK_SIBLINGS = listOf(
    FamilyApp("Veda Drop", "Women-only beauty & wellness booking", Color(0xFF00AAAD), ODIOBOOK_APPS_HUB),
    FamilyApp("Early Rover", "Smart alarm, weather & travel wake-up", Color(0xFFF5A623), ODIOBOOK_APPS_HUB),
    FamilyApp("Traces Wiper", "Secure file shredder & trace cleaner", Color(0xFF10B981), ODIOBOOK_APPS_HUB),
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
    "traces wiper" to Color(0xFF10B981),
    "dig deep" to Color(0xFF10B981),   // legacy title alias (pre-§812 backend rows)
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
 * Live OdioBook family directory with a persistent, weekly-refreshed cache (§825).
 *
 * Rendering path (all I/O on a background executor — never the UI thread):
 *   1. `ensureLoaded` bootstraps once from the cached JSON so the rows paint from the
 *      last-good list (and offline), then decodes any cached icon files.
 *   2. It refreshes over the network only when the cache is missing, older than a week,
 *      or the app has been updated since the last fetch — otherwise it does nothing.
 *
 * Fail-soft throughout: any error leaves the last-good cache (or the bundled fallback)
 * in place. Mirrors the dependency-free style of com.example.ads.OdioBookAds.
 */
private object FamilyDirectory {
    private const val URL_STR = "$ODIOBOOK_HOME/api/showcase/apps"
    private const val PREFS = "odiobook_family"
    private const val K_JSON = "dir_json"
    private const val K_FETCHED = "fetched_at"
    private const val K_VERSION = "app_version"
    private const val ICON_SUBDIR = "family_icons"
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000   // weekly refresh window

    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var loading = false
    @Volatile private var bootstrapped = false

    // null = nothing cached yet (caller shows the bundled fallback).
    val state = mutableStateOf<List<FamilyApp>?>(null)
    // iconUrl -> decoded bitmap, filled from the disk cache / after downloads.
    val icons = mutableStateOf<Map<String, ImageBitmap>>(emptyMap())

    /** Load from cache, then refresh at most weekly. Safe to call on every screen
     *  entry: ALL disk + binder I/O (SharedPreferences, packageInfo, network) runs on
     *  the background executor, and the network refresh no-ops unless the cache is
     *  stale. Nothing here touches the UI thread except the observable state writes. */
    fun ensureLoaded(context: Context) {
        val app = context.applicationContext
        if (loading) return
        loading = true
        io.execute {
            try {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

                // One-time bootstrap from the on-disk cache (kept off the UI thread so
                // first access never janks); paints the last-good list + icons.
                if (!bootstrapped) {
                    bootstrapped = true
                    prefs.getString(K_JSON, null)?.let { cached ->
                        runCatching { parse(cached) }.getOrNull()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { state.value = it }
                    }
                    loadCachedIcons(app)
                }

                // Refresh over the network at most weekly, or right after an app update.
                val fetchedAt = prefs.getLong(K_FETCHED, 0L)
                val lastVersion = prefs.getInt(K_VERSION, -1)
                val version = appVersion(app)
                val stale = state.value == null ||
                    (System.currentTimeMillis() - fetchedAt) > TTL_MS ||
                    version != lastVersion
                if (stale) {
                    val json = fetchJson()
                    val apps = json?.let { runCatching { parse(it) }.getOrNull() }
                    if (json != null && apps != null && apps.isNotEmpty()) {
                        prefs.edit()
                            .putString(K_JSON, json)
                            .putLong(K_FETCHED, System.currentTimeMillis())
                            .putInt(K_VERSION, version)
                            .apply()
                        state.value = apps
                        // Re-download icons on the weekly refresh (force overwrite) so a
                        // changed image at a stable URL is picked up too.
                        downloadIcons(app, apps, force = true)
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    private fun parse(text: String): List<FamilyApp> {
        val items = JSONObject(text).optJSONArray("items") ?: return emptyList()
        val out = ArrayList<FamilyApp>(items.length())
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val title = if (o.isNull("title")) "" else o.optString("title", "").trim()
            if (title.isEmpty()) continue
            val tagline = if (o.isNull("tagline")) "" else o.optString("tagline", "").trim()
            val store = if (o.isNull("store_url")) "" else o.optString("store_url", "").trim()
            val iconRel = if (o.isNull("icon_png_url")) "" else o.optString("icon_png_url", "").trim()
            out.add(
                FamilyApp(
                    title = title,
                    tagline = tagline,
                    accent = accentFor(title),
                    url = if (store.isNotEmpty()) store else ODIOBOOK_APPS_HUB,
                    iconUrl = absolutize(iconRel),
                )
            )
        }
        return out
    }

    private fun absolutize(u: String): String? {
        if (u.isEmpty()) return null
        return if (u.startsWith("http://") || u.startsWith("https://")) u
        else ODIOBOOK_HOME + (if (u.startsWith("/")) u else "/$u")
    }

    private fun fetchJson(): String? {
        val conn = (URL(URL_STR).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // --- icon disk cache -------------------------------------------------- //

    private fun iconDir(ctx: Context): File =
        File(ctx.filesDir, ICON_SUBDIR).apply { mkdirs() }

    private fun iconFile(ctx: Context, url: String): File = File(iconDir(ctx), keyFor(url) + ".png")

    private fun keyFor(url: String): String = try {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
        buildString { for (b in digest) append("%02x".format(b.toInt() and 0xFF)) }
    } catch (e: Exception) {
        Integer.toHexString(url.hashCode())
    }

    private fun decode(f: File): ImageBitmap? = try {
        BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }

    /** Decode whatever icons are already cached on disk for the current directory. */
    private fun loadCachedIcons(ctx: Context) {
        val apps = state.value ?: return
        val map = HashMap(icons.value)
        var changed = false
        for (app in apps) {
            val url = app.iconUrl ?: continue
            if (map.containsKey(url)) continue
            val f = iconFile(ctx, url)
            if (f.exists()) decode(f)?.let { map[url] = it; changed = true }
        }
        if (changed) icons.value = map
    }

    private fun downloadIcons(ctx: Context, apps: List<FamilyApp>, force: Boolean) {
        val map = HashMap(icons.value)
        var changed = false
        for (app in apps) {
            val url = app.iconUrl ?: continue
            val f = iconFile(ctx, url)
            if (force || !f.exists()) {
                if (downloadTo(url, f)) decode(f)?.let { map[url] = it; changed = true }
            } else if (!map.containsKey(url)) {
                decode(f)?.let { map[url] = it; changed = true }
            }
        }
        if (changed) icons.value = map
    }

    /** Download to a temp file then atomically rename, so a failed/partial fetch can
     *  never leave a corrupt icon in the cache. */
    private fun downloadTo(url: String, dest: File): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        return try {
            if (conn.responseCode != 200) return false
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            if (tmp.length() <= 0L) {
                tmp.delete()
                return false
            }
            if (dest.exists()) dest.delete()
            val ok = tmp.renameTo(dest)
            if (!ok) tmp.delete()
            ok
        } catch (e: Exception) {
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun appVersion(ctx: Context): Int = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (e: Exception) {
        -1
    }
}

@Composable
fun OdioBookFamilySection(
    currentAppTitle: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { FamilyDirectory.ensureLoaded(context) }

    val uriHandler = LocalUriHandler.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = onSurface.copy(alpha = 0.62f)
    val primary = MaterialTheme.colorScheme.primary

    // OdioBook (the parent) is always first; the siblings come live from the backend,
    // falling back to the bundled list before they load / when offline with no cache.
    val siblings = FamilyDirectory.state.value ?: FALLBACK_SIBLINGS
    val apps = listOf(ODIOBOOK_ENTRY) + siblings
    val iconMap = FamilyDirectory.icons.value

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
                icon = app.iconUrl?.let { iconMap[it] },
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
    icon: ImageBitmap?,
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
        val tileShape = RoundedCornerShape(12.dp)
        val bundled = app.bundledRes
        when {
            // Real icon fetched live from OdioBook (cached PNG).
            icon != null -> Image(
                bitmap = icon,
                contentDescription = app.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(46.dp)
                    .clip(tileShape),
            )
            // Bundled brand mark (OdioBook parent).
            bundled != null -> Image(
                painter = painterResource(id = bundled),
                contentDescription = app.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(46.dp)
                    .clip(tileShape),
            )
            // Fallback monogram tile in the app's accent colour (pre-load / offline).
            else -> Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(tileShape)
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
