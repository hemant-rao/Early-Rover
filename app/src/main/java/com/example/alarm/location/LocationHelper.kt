package com.example.alarm.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.location.Location
import com.example.alarm.maps.OlaMapsRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

data class CityInfo(
    val name: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val timezoneOffset: Double
)

class LocationHelper(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("sun_alarm_location_prefs", Context.MODE_PRIVATE)

    // In-flight FusedLocation cancellation token, promoted to a field so an external
    // caller can abort a pending current-location request.
    @Volatile
    private var locationCts: CancellationTokenSource? = null

    // Shared cancellation flag captured by the deeper fallback paths (single-update
    // listener, IP-geolocation thread, reverse-geocoding thread) which are not tied to
    // the FusedLocation CancellationTokenSource. They check this before persisting or
    // posting onSuccess so a cancelled request does not write a stale location.
    private val cancelled = AtomicBoolean(false)

    // Single-update listener + its timeout handler, lifted to fields so cancellation can
    // stop them directly. Confined to the main thread (registered/cleared there).
    private var singleUpdateListener: android.location.LocationListener? = null
    private var singleUpdateTimeoutHandler: android.os.Handler? = null

    fun cancelLocationRequest() {
        cancelled.set(true)
        locationCts?.cancel()
        locationCts = null
        try {
            val listener = singleUpdateListener
            if (listener != null) {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                locationManager?.removeUpdates(listener)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        singleUpdateListener = null
        singleUpdateTimeoutHandler?.removeCallbacksAndMessages(null)
        singleUpdateTimeoutHandler = null
    }

    companion object {
        val WORLD_CITIES = listOf(
            CityInfo("New York", "United States", 40.7128, -74.0060, -5.0),
            CityInfo("Los Angeles", "United States", 34.0522, -118.2437, -8.0),
            CityInfo("Chicago", "United States", 41.8781, -87.6298, -6.0),
            CityInfo("London", "United Kingdom", 51.5074, -0.1278, 0.0),
            CityInfo("Paris", "France", 48.8566, 2.3522, 1.0),
            CityInfo("Berlin", "Germany", 52.5200, 13.4050, 1.0),
            CityInfo("Tokyo", "Japan", 35.6762, 139.6503, 9.0),
            CityInfo("Sydney", "Australia", -33.8688, 151.2093, 10.0),
            CityInfo("Mumbai", "India", 19.0760, 72.8777, 5.5),
            CityInfo("Delhi", "India", 28.6139, 77.2090, 5.5),
            CityInfo("Bengaluru", "India", 12.9716, 77.5946, 5.5),
            CityInfo("Reykjavík", "Iceland", 64.1466, -21.9426, 0.0),
            CityInfo("Dubai", "United Arab Emirates", 25.2048, 55.2708, 4.0),
            CityInfo("Singapore", "Singapore", 1.3521, 103.8198, 8.0),
            CityInfo("Cairo", "Egypt", 30.0444, 31.2357, 2.0),
            CityInfo("Moscow", "Russia", 55.7558, 37.6173, 3.0),
            CityInfo("Rio de Janeiro", "Brazil", -22.9068, -43.1729, -3.0),
            CityInfo("Cape Town", "South Africa", -33.9249, 18.4241, 2.0)
        )

        fun estimateTimezoneOffsetOffline(lat: Double, lng: Double): Double {
            // Check if the coordinate lies within India or Sri Lanka (UTC+5.5)
            if (lat in 5.0..38.0 && lng in 67.0..99.0) {
                return 5.5
            }
            // Check if the coordinate lies within Nepal (UTC+5.75)
            if (lat in 26.0..31.0 && lng in 80.0..89.0) {
                return 5.75
            }
            // Check if the coordinate lies within Myanmar (UTC+6.5)
            if (lat in 9.0..29.0 && lng in 92.0..102.0) {
                return 6.5
            }
            // Check if Afghanistan (UTC+4.5)
            if (lat in 29.0..39.0 && lng in 60.0..75.0) {
                return 4.5
            }
            // Check if Iran (UTC+3.5)
            if (lat in 25.0..40.0 && lng in 44.0..64.0) {
                return 3.5
            }
            // Check if Newfoundland (UTC-3.5)
            if (lat in 46.0..61.0 && lng in -60.0..-52.0) {
                return -3.5
            }
            // Check if South/Central Australia (Adelaide / Darwin -> UTC+9.5)
            if (lat in -44.0..-10.0 && lng in 129.0..141.0) {
                return 9.5
            }
            
            // Otherwise, check if device timezone DST/non-DST standard offset is close to standard longitude estimate.
            try {
                val deviceTz = java.util.TimeZone.getDefault()
                val deviceOffsetHours = deviceTz.getOffset(System.currentTimeMillis()) / (1000.0 * 60.0 * 60.0)
                val crudeEstimate = Math.max(-12.0, Math.min(14.0, Math.round(lng / 15.0).toDouble()))
                if (Math.abs(deviceOffsetHours - crudeEstimate) <= 1.5) {
                    return deviceOffsetHours
                }
            } catch (e: Exception) {
                // ignore
            }

            // Default crude estimate
            return Math.max(-12.0, Math.min(14.0, Math.round(lng / 15.0).toDouble()))
        }

        /**
         * Sanitizes a detected/parsed offset against coordinates and country information to prevent 
         * common integer-rounding errors (e.g. India being detected as +5.0 instead of +5.5).
         */
        fun sanitizeOffset(lat: Double, lng: Double, detectedOffset: Double, country: String? = null): Double {
            val c = country?.trim()?.uppercase() ?: ""

            // --- Most specific first: country/city-string-confirmed fractional zones. ---
            // These are matched on the NAME only (never raw coordinates), because each one's geographic
            // box overlaps a neighbour on a DIFFERENT offset — Nepal sits inside India's box (and over
            // real Indian territory like Lucknow); Myanmar/Thailand, Afghanistan/Pakistan, Iran/Iraq and
            // Newfoundland/Atlantic-Canada likewise straddle a boundary. Trusting a confirmed country name
            // is the one reliable signal that can't clobber a correctly-detected neighbour. Checked BEFORE
            // the India coordinate snap below so e.g. "Kathmandu, NP" resolves to +5:45, not India's +5:30.
            // Nepal (+5.75)
            if (c.contains("NEPAL") || c.contains(", NP") || c == "NP" || c.contains("KATHMANDU")) return 5.75
            // Myanmar (+6.5)
            if (c.contains("MYANMAR") || c.contains("BURMA") || c == "MM" || c.contains(", MM") || c.contains("YANGON")) return 6.5
            // Afghanistan (+4.5)
            if (c.contains("AFGHANISTAN") || c == "AF" || c.contains(", AF") || c.contains("KABUL")) return 4.5
            // Iran (+3.5)
            if (c.contains("IRAN") || c == "IR" || c.contains(", IR") || c.contains("TEHRAN")) return 3.5
            // Newfoundland (-3.5)
            if (c.contains("NEWFOUNDLAND") || c.contains("ST JOHN'S") || c.contains("ST. JOHN'S")) return -3.5

            // India's coordinate box unavoidably borders countries on a DIFFERENT offset: Pakistan and
            // the Central-Asian republics (+5:00 / +6:00) to the west, and Bangladesh/Bhutan (+6:00),
            // Myanmar (+6:30), Thailand/Laos/Cambodia/Vietnam (+7:00) and China (+8:00) to the east. When
            // the detected country names one of those, NEVER snap to India's +5.5 — this is the guard that
            // keeps e.g. Lahore, Dhaka, Bangkok or Chiang Mai from being wrongly pulled to +5:30.
            val cIsNonIndiaNeighbour =
                c.contains("PAKISTAN") || c == "PK" || c.contains(", PK") ||
                c.contains("KARACHI") || c.contains("LAHORE") || c.contains("ISLAMABAD") ||
                c.contains("TAJIK") || c == "TJ" || c.contains("TURKMEN") || c == "TM" ||
                c.contains("UZBEK") || c == "UZ" || c.contains("MALDIVES") || c == "MV" ||
                c.contains("BANGLADESH") || c == "BD" || c.contains("DHAKA") ||
                c.contains("BHUTAN") || c == "BT" || c.contains("THIMPHU") ||
                c.contains("THAILAND") || c == "TH" || c.contains("BANGKOK") || c.contains("CHIANG") ||
                c.contains("LAOS") || c == "LA" || c.contains("CAMBODIA") || c == "KH" ||
                c.contains("VIETNAM") || c == "VN" || c.contains("CHINA") || c == "CN"
            // For India/Sri Lanka, if the offset is suspiciously +5.0 or +6.0, force +5.5.
            val isIndiaOrSL = (lat in 5.0..38.0 && lng in 67.0..99.0) ||
                              c.contains("INDIA") || c.contains(", IN") || c.endsWith(" IN") || c == "IN" ||
                              c.contains("SRI LANKA") || c.contains(", LK") || c == "LK" || c.contains("COLOMBO")
            if (isIndiaOrSL && !cIsNonIndiaNeighbour) {
                // Snap a near-miss (within half an hour) to India's uniform +5:30 — this is what turns a
                // rounded +5.0 detection back into +5.5 and fixes the dashboard clock / sunrise-sunset cards
                // reading 30 minutes early. The neighbour guard above means a correctly-detected Pakistan
                // (+5), Bangladesh (+6) or Thailand (+7) is never reached here, and the <= 0.6 window leaves
                // anything more than 30 min away exactly as detected, so a far-off zone can't be clobbered.
                if (Math.abs(detectedOffset - 5.5) <= 0.6) return 5.5
            }
            // South/Central Australia (+9.5)
            val isSCAust = (lat in -45.0..-10.0 && lng in 128.0..142.0) ||
                           c.contains("ADELAIDE") || c.contains("DARWIN") || c.contains("SOUTH AUSTRALIA") || c.contains("NORTHERN TERRITORY")
            if (isSCAust) {
                // Only if the offset is already around +9 or +10 (the band overlaps +10 western Queensland,
                // so a near-miss nudge is safer here than an unconditional coordinate snap).
                if (Math.abs(detectedOffset - 9.5) <= 0.6) return 9.5
            }
            return detectedOffset
        }
    }

    fun getSavedLatitude(): Double = prefs.getFloat("lat", 40.7128f).toDouble() // Default NYC
    fun getSavedLongitude(): Double = prefs.getFloat("lng", -74.0060f).toDouble()
    fun getSavedTimezoneOffset(): Double = prefs.getFloat("tz_offset", -5.0f).toDouble()
    fun getSavedLocationName(): String = prefs.getString("loc_name", "New York (Default)").orEmpty()
    fun isAutoDetectEnabled(): Boolean = prefs.getBoolean("auto_detect", true)

    fun saveLocation(lat: Double, lng: Double, offset: Double, name: String) {
        prefs.edit().apply {
            putFloat("lat", lat.toFloat())
            putFloat("lng", lng.toFloat())
            putFloat("tz_offset", offset.toFloat())
            putString("loc_name", name)
            apply()
        }
    }

    fun setAutoDetect(enabled: Boolean) {
        prefs.edit().putBoolean("auto_detect", enabled).apply()
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun getSystemLocationFallback(
        onSuccess: (lat: Double, lng: Double, timezoneOffset: Double, name: String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        if (locationManager != null) {
            try {
                // Try GPS Provider first for exact location
                val gpsLocation = if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                } else null

                // Try Network Provider if GPS is null
                val networkLocation = if (gpsLocation == null && locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                    locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                } else null

                val finalLoc = gpsLocation ?: networkLocation
                if (finalLoc != null) {
                    processLocation(finalLoc, onSuccess, onFailure)
                } else {
                    // Try to request a single update via LocationManager if both last locations are null
                    val provider = if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                        android.location.LocationManager.GPS_PROVIDER
                    } else if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                        android.location.LocationManager.NETWORK_PROVIDER
                    } else {
                        null
                    }

                    if (provider != null) {
                        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        // Guard so exactly one of processLocation / fetchIPLocationFallback ever
                        // runs even if the fix arrives at ~15s and races the timeout runnable.
                        val completed = AtomicBoolean(false)
                        val singleListener = object : android.location.LocationListener {
                            override fun onLocationChanged(location: Location) {
                                if (!completed.compareAndSet(false, true)) return
                                timeoutHandler.removeCallbacksAndMessages(null)
                                try {
                                    locationManager.removeUpdates(this)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                singleUpdateListener = null
                                singleUpdateTimeoutHandler = null
                                if (cancelled.get()) return
                                processLocation(location, onSuccess, onFailure)
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onStatusChanged(p0: String?, p1: Int, p2: android.os.Bundle?) {}
                            override fun onProviderEnabled(p0: String) {}
                            override fun onProviderDisabled(p0: String) {}
                        }
                        singleUpdateListener = singleListener
                        singleUpdateTimeoutHandler = timeoutHandler
                        locationManager.requestSingleUpdate(provider, singleListener, android.os.Looper.getMainLooper())
                        // If no fix arrives within 15s, stop listening (avoids a leaked listener)
                        // and route to the IP-based fallback so the caller is not left hanging.
                        timeoutHandler.postDelayed({
                            if (!completed.compareAndSet(false, true)) return@postDelayed
                            try {
                                locationManager.removeUpdates(singleListener)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            singleUpdateListener = null
                            singleUpdateTimeoutHandler = null
                            if (cancelled.get()) return@postDelayed
                            fetchIPLocationFallback(onSuccess, onFailure)
                        }, 15000L)
                    } else {
                        // Dynamic IP Geolocation fallback as a final failsafe if no LocationManager providers are active
                        fetchIPLocationFallback(onSuccess, onFailure)
                    }
                }
            } catch (e: Exception) {
                fetchIPLocationFallback(onSuccess, onFailure)
            }
        } else {
            fetchIPLocationFallback(onSuccess, onFailure)
        }
    }

    private fun fetchIPLocationFallback(
        onSuccess: (lat: Double, lng: Double, timezoneOffset: Double, name: String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        Thread {
            try {
                val url = URL("https://ipapi.co/json/")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("User-Agent", "SolarAlarmApp")
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    
                    val json = JSONObject(response.toString())
                    val lat = json.optDouble("latitude", 0.0)
                    val lng = json.optDouble("longitude", 0.0)
                    val city = json.optString("city", "")
                    val country = json.optString("country_code", "")
                    // Use the IANA timezone id (not ipapi's `utc_offset`, which includes DST)
                    // so this path stores the RAW/standard offset, matching the GPS/city-search
                    // path (TimeZone.rawOffset) and SunCalculator/zoneOf's no-DST assumption.
                    val tzId = json.optString("timezone", "")
                    val timezoneOffset = if (tzId.isNotEmpty()) {
                        sanitizeOffset(lat, lng, parseTimezoneOffset(tzId) ?: estimateTimezoneOffsetOffline(lat, lng), country)
                    } else {
                        estimateTimezoneOffsetOffline(lat, lng)
                    }
                    
                    val name = if (city.isNotEmpty() && country.isNotEmpty()) {
                        "$city, $country"
                    } else if (city.isNotEmpty()) {
                        city
                    } else {
                        "Auto IP Location"
                    }
                    
                    if (lat != 0.0 || lng != 0.0) {
                        if (cancelled.get() || !isAutoDetectEnabled()) return@Thread
                        saveLocation(lat, lng, timezoneOffset, name)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (cancelled.get() || !isAutoDetectEnabled()) return@post
                            onSuccess(lat, lng, timezoneOffset, name)
                        }
                        return@Thread
                    }
                }
                throw Exception("IP Geolocation API response not valid")
            } catch (e: Exception) {
                // Secondary fallback API: freeipapi.com (keyless, HTTPS-capable).
                // ip-api.com free tier is HTTP-only and is blocked by Android's default
                // cleartext policy on API 28+, so it can never succeed without relaxing
                // cleartext globally; freeipapi serves the same data over HTTPS.
                try {
                    val url2 = URL("https://freeipapi.com/api/json")
                    val connection2 = url2.openConnection() as HttpURLConnection
                    connection2.requestMethod = "GET"
                    connection2.connectTimeout = 3000
                    connection2.readTimeout = 3000
                    connection2.setRequestProperty("User-Agent", "SolarAlarmApp")

                    val rCode = connection2.responseCode
                    if (rCode == HttpURLConnection.HTTP_OK) {
                        val reader2 = BufferedReader(InputStreamReader(connection2.inputStream))
                        val response2 = StringBuilder()
                        var line2: String?
                        while (reader2.readLine().also { line2 = it } != null) {
                            response2.append(line2)
                        }
                        reader2.close()

                        val json2 = JSONObject(response2.toString())
                        val lat = json2.optDouble("latitude", 0.0)
                        val lng = json2.optDouble("longitude", 0.0)
                        val city = json2.optString("cityName", "")
                        val country = json2.optString("countryCode", "")

                        // Mirror the ipapi.co branch: resolve the RAW/standard offset from the
                        // real IANA zone (no DST) instead of the crude longitude/15 estimate,
                        // which is wrong for half/quarter-hour zones (India +5.5, Nepal +5.45).
                        // freeipapi returns IANA ids in `timeZones` (array) and may also return
                        // `timeZone` either as an IANA id or as an offset string like "+05:30".
                        val ianaId = json2.optJSONArray("timeZones")?.optString(0).orEmpty()
                            .ifEmpty { json2.optString("timeZone", "") }
                        val timezoneOffset = sanitizeOffset(lat, lng, parseTimezoneOffset(ianaId) ?: estimateTimezoneOffsetOffline(lat, lng), country)
                        val name = if (city.isNotEmpty() && country.isNotEmpty()) {
                            "$city, $country"
                        } else if (city.isNotEmpty()) {
                            city
                        } else {
                            "Auto Network Location"
                        }

                        if (lat != 0.0 || lng != 0.0) {
                            if (cancelled.get() || !isAutoDetectEnabled()) return@Thread
                            saveLocation(lat, lng, timezoneOffset, name)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (cancelled.get() || !isAutoDetectEnabled()) return@post
                                onSuccess(lat, lng, timezoneOffset, name)
                            }
                            return@Thread
                        }
                    }
                } catch (pe: Exception) {
                    pe.printStackTrace()
                }

                if (cancelled.get()) return@Thread
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (cancelled.get()) return@post
                    onFailure(e)
                }
            }
        }.start()
    }

    private fun processLocation(
        location: Location,
        onSuccess: (lat: Double, lng: Double, timezoneOffset: Double, name: String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        // Uniform early guard so every caller (including the lastLocation success path,
        // which does not re-check the flag itself) skips the wasted reverse-geocoding
        // network I/O once the request has been cancelled (e.g. user picked a manual city).
        if (cancelled.get()) return
        val lat = location.latitude
        val lng = location.longitude

        // Use accurate timezone lookup via resolveTimezoneOffset, fall back to accurate offline estimator only if needed
        var roundedTzOffset = resolveTimezoneOffset(lat, lng) ?: estimateTimezoneOffsetOffline(lat, lng)
        
        // Run blocking reverse geocoding on a background thread to prevent NetworkOnMainThreadException
        Thread {
            var detectedName = ""
            var detectedCountry = ""
            try {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lng, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        val placeName = address.featureName
                        val locality = address.locality ?: address.subAdminArea ?: address.adminArea ?: ""
                        
                        detectedCountry = address.countryCode ?: address.countryName ?: ""
                        
                        detectedName = if (!placeName.isNullOrEmpty() && placeName != locality && !placeName.matches(Regex("^[0-9]+$"))) {
                            // Place name exists and is not just a house number
                            if (locality.isNotEmpty()) "$placeName, $locality" else placeName
                        } else if (locality.isNotEmpty()) {
                            if (detectedCountry.isNotEmpty()) "$locality, $detectedCountry" else locality
                        } else {
                            detectedCountry
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Fallback to OSM Nominatim reverse geocoding if local geocoder returned nothing
            if (detectedName.isEmpty()) {
                try {
                    val url = URL("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lng&format=json&accept-language=en")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000
                    connection.setRequestProperty("User-Agent", "SolariAlarmApp")
                    
                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream))
                        val response = StringBuilder()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            response.append(line)
                        }
                        reader.close()
                        
                        val jsonObject = JSONObject(response.toString())
                        if (jsonObject.has("address")) {
                            val addrObj = jsonObject.getJSONObject("address")
                            // Try to find a very specific place name first (POI/Building/Amenity)
                            val placeName = addrObj.optString("amenity", "")
                                .ifEmpty { addrObj.optString("building", "") }
                                .ifEmpty { addrObj.optString("shop", "") }
                                .ifEmpty { addrObj.optString("historic", "") }
                                .ifEmpty { addrObj.optString("tourism", "") }
                                .ifEmpty { addrObj.optString("office", "") }
                                .ifEmpty { addrObj.optString("leisure", "") }
                                .ifEmpty { addrObj.optString("place_of_worship", "") }

                            val city = addrObj.optString("city", "")
                                .ifEmpty { addrObj.optString("town", "") }
                                .ifEmpty { addrObj.optString("village", "") }
                                .ifEmpty { addrObj.optString("suburb", "") }
                                .ifEmpty { addrObj.optString("hamlet", "") }
                                .ifEmpty { addrObj.optString("county", "") }
                            detectedCountry = addrObj.optString("country_code", "").uppercase().ifEmpty {
                                addrObj.optString("country", "")
                            }
                            
                            detectedName = if (placeName.isNotEmpty()) {
                                if (city.isNotEmpty()) "$placeName, $city" else placeName
                            } else if (city.isNotEmpty() && detectedCountry.isNotEmpty()) {
                                "$city, $detectedCountry"
                            } else if (city.isNotEmpty()) {
                                city
                            } else {
                                detectedCountry
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (detectedName.isEmpty()) {
                detectedName = String.format(java.util.Locale.US, "GPS: %.4f, %.4f", lat, lng)
            }
            
            // Re-sanitize against the detected country name or code
            roundedTzOffset = sanitizeOffset(lat, lng, roundedTzOffset, detectedCountry.ifEmpty { detectedName })
            
            if (cancelled.get() || !isAutoDetectEnabled()) return@Thread
            saveLocation(lat, lng, roundedTzOffset, detectedName)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (cancelled.get() || !isAutoDetectEnabled()) return@post
                onSuccess(lat, lng, roundedTzOffset, detectedName)
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    fun requestCurrentLocation(
        onSuccess: (lat: Double, lng: Double, timezoneOffset: Double, name: String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (!hasLocationPermission()) {
            onFailure(SecurityException("Missing location permissions"))
            return
        }
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        cancelled.set(false)
        try {
            val cts = CancellationTokenSource()
            locationCts = cts

            // Top-level guard + timeout so detection always resolves even if
            // getCurrentLocation neither completes nor fails (e.g. a wedged Play
            // Services request). Without this, none of the listeners fire, the IP
            // fallback is never reached, and the caller's spinner sticks forever.
            val completed = AtomicBoolean(false)
            val overallTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
            overallTimeoutHandler.postDelayed({
                if (!completed.compareAndSet(false, true)) return@postDelayed
                // Cancel the wedged FusedLocation request and route to the system/IP
                // fallback so the caller's onSuccess/onFailure is guaranteed to fire.
                try {
                    cts.cancel()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                getSystemLocationFallback(onSuccess, onFailure)
            }, 15000L)

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cts.token
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    if (!completed.compareAndSet(false, true)) return@addOnSuccessListener
                    overallTimeoutHandler.removeCallbacksAndMessages(null)
                    processLocation(location, onSuccess, onFailure)
                } else {
                    // Fallback to Fused Location lastLocation
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                        if (lastLoc != null) {
                            if (!completed.compareAndSet(false, true)) return@addOnSuccessListener
                            overallTimeoutHandler.removeCallbacksAndMessages(null)
                            processLocation(lastLoc, onSuccess, onFailure)
                        } else {
                            if (!completed.compareAndSet(false, true)) return@addOnSuccessListener
                            overallTimeoutHandler.removeCallbacksAndMessages(null)
                            // Fallback to native LocationManager (GPS/Network)
                            getSystemLocationFallback(onSuccess, onFailure)
                        }
                    }.addOnFailureListener {
                        if (!completed.compareAndSet(false, true)) return@addOnFailureListener
                        overallTimeoutHandler.removeCallbacksAndMessages(null)
                        getSystemLocationFallback(onSuccess, onFailure)
                    }
                }
            }.addOnFailureListener { exception ->
                if (!completed.compareAndSet(false, true)) return@addOnFailureListener
                overallTimeoutHandler.removeCallbacksAndMessages(null)
                // Fallback to native LocationManager (GPS/Network)
                getSystemLocationFallback(onSuccess, onFailure)
            }
        } catch (e: SecurityException) {
            onFailure(e)
        }
    }

    // Converts a freeipapi/IANA timezone token into a RAW (no-DST) hour offset.
    // Accepts either an IANA id ("Asia/Kolkata") -> TimeZone.rawOffset, or an offset
    // string ("+05:30" / "UTC+5:30" / "+0530"). Returns null when unparseable so the
    // caller can fall back to a longitude estimate.
    private fun parseTimezoneOffset(token: String): Double? {
        if (token.isBlank()) return null
        val t = token.trim()
        // Offset string form, e.g. "+05:30", "-08:00", "+0530", "UTC+5:30".
        val offsetRegex = Regex("([+-])(\\d{1,2})(?::?(\\d{2}))?")
        if (t.contains('/') || t.equals("UTC", ignoreCase = true) || t.equals("GMT", ignoreCase = true)) {
            // Looks like an IANA id (or bare UTC/GMT) -> use raw offset.
            return try {
                val tz = java.util.TimeZone.getTimeZone(t)
                // getTimeZone returns GMT for unknown ids; only trust a real match, alias, or UTC/GMT.
                if (tz.id != "GMT" || t.equals("GMT", ignoreCase = true) || t.equals("UTC", ignoreCase = true)) {
                    tz.getOffset(System.currentTimeMillis()) / (1000.0 * 60.0 * 60.0)
                } else null
            } catch (e: Exception) {
                null
            }
        }
        val m = offsetRegex.find(t) ?: return null
        return try {
            val sign = if (m.groupValues[1] == "-") -1.0 else 1.0
            val hours = m.groupValues[2].toDouble()
            val minutes = m.groupValues[3].toDoubleOrNull() ?: 0.0
            (sign * (hours + minutes / 60.0)).coerceIn(-12.0, 14.0)
        } catch (e: Exception) {
            null
        }
    }

    // Resolves the real IANA standard (no-DST) offset for coordinates via Open-Meteo's
    // free timezone lookup, mirroring the primary geocoder branch's TimeZone.rawOffset
    // semantics. Returns null on any failure so callers refuse to persist a guess.
    // Must be called off the main thread (performs blocking network I/O).
    private fun resolveTimezoneOffset(lat: Double, lng: Double): Double? {
        return try {
            // §689 — routed through the OdioBook geo gateway. weather/current passes
            // timezone=auto upstream, so the response still carries the top-level
            // "timezone" field we read here. Null on any failure keeps the caller's
            // existing offset (callers refuse to persist a guess).
            val base = OlaMapsRepository.serverBaseUrl.trim().trimEnd('/')
                .ifBlank { OlaMapsRepository.DEFAULT_SERVER }
            val url = URL("$base/api/geo/weather/current?lat=$lat&lon=$lng&app=solaris")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.setRequestProperty("User-Agent", "SolarAlarmApp")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line)
            }
            reader.close()
            val json = JSONObject(response.toString())
            val tzId = json.optString("timezone", "")
            parseTimezoneOffset(tzId)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun searchCity(query: String): List<CityInfo> {
        val trimQuery = query.trim()
        if (trimQuery.length < 2) return emptyList()
        
        // 1. Online lookup via 100% free Open-Meteo Geocoding API with no API Key
        try {
            val encodedQuery = URLEncoder.encode(trimQuery, "UTF-8")
            val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=$encodedQuery&count=20&language=en")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                
                val jsonObject = JSONObject(response.toString())
                if (jsonObject.has("results")) {
                    val resultsArray = jsonObject.getJSONArray("results")
                    val cities = ArrayList<CityInfo>()
                    for (i in 0 until resultsArray.length()) {
                        val cityJson = resultsArray.getJSONObject(i)
                        val name = cityJson.optString("name", "")
                        val country = cityJson.optString("country", "")
                        val latitude = cityJson.optDouble("latitude", 0.0)
                        val longitude = cityJson.optDouble("longitude", 0.0)
                        val tzString = cityJson.optString("timezone", "")
                        
                        var tzOffset = if (tzString.isNotEmpty()) {
                            parseTimezoneOffset(tzString) ?: estimateTimezoneOffsetOffline(latitude, longitude)
                        } else {
                            estimateTimezoneOffsetOffline(latitude, longitude)
                        }
                        tzOffset = sanitizeOffset(latitude, longitude, tzOffset, country)
                        
                        if (name.isNotEmpty()) {
                            cities.add(CityInfo(name, country, latitude, longitude, tzOffset))
                        }
                    }
                    if (cities.isNotEmpty()) {
                        return cities
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback Online lookup via 100% free OpenStreetMap Nominatim API (Keyless)
        try {
            val encodedQuery = URLEncoder.encode(trimQuery, "UTF-8")
            val url = URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=50&addressdetails=1&namedetails=1&accept-language=en")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.setRequestProperty("User-Agent", "SolariAlarmApp")
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                
                val resultsArray = org.json.JSONArray(response.toString())
                val cities = ArrayList<CityInfo>()
                for (i in 0 until resultsArray.length()) {
                    val cityJson = resultsArray.getJSONObject(i)
                    val displayName = cityJson.optString("display_name", "")
                    val latStr = cityJson.optString("lat", "0.0")
                    val lonStr = cityJson.optString("lon", "0.0")
                    val latitude = latStr.toDoubleOrNull() ?: 0.0
                    val longitude = lonStr.toDoubleOrNull() ?: 0.0
                    
                    val parts = displayName.split(",")
                    val name = if (parts.isNotEmpty()) parts[0].trim() else ""
                    val country = if (parts.size > 1) parts[parts.size - 1].trim() else ""
                    
                    // Resolve the real IANA standard offset for these coordinates instead of
                    // the crude longitude/15 estimate (which is wrong for half/quarter-hour
                    // and politically-shifted zones: Adelaide +9.5, Kathmandu +5.45, Tehran
                    // +3.5, Newfoundland -3.5). This city is persisted verbatim into saved
                    // cities, so the offset must match the primary Open-Meteo branch's
                    // TimeZone.rawOffset semantics. Skip the entry if no zone can be resolved
                    // rather than saving a longitude-derived guess.
                    val tzOffset = resolveTimezoneOffset(latitude, longitude)

                    if (name.isNotEmpty() && tzOffset != null) {
                        cities.add(CityInfo(name, country, latitude, longitude, tzOffset))
                    }
                }
                if (cities.isNotEmpty()) {
                    return cities
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Fallback to predefined local cities if offline or network error
        return WORLD_CITIES.filter {
            it.name.contains(trimQuery, ignoreCase = true) || 
            it.country.contains(trimQuery, ignoreCase = true)
        }
    }
}