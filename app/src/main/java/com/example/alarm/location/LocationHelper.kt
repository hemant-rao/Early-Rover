package com.example.alarm.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
    private var locationCts: CancellationTokenSource? = null

    fun cancelLocationRequest() {
        locationCts?.cancel()
        locationCts = null
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
                        val singleListener = object : android.location.LocationListener {
                            override fun onLocationChanged(location: Location) {
                                timeoutHandler.removeCallbacksAndMessages(null)
                                processLocation(location, onSuccess, onFailure)
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onStatusChanged(p0: String?, p1: Int, p2: android.os.Bundle?) {}
                            override fun onProviderEnabled(p0: String) {}
                            override fun onProviderDisabled(p0: String) {}
                        }
                        locationManager.requestSingleUpdate(provider, singleListener, android.os.Looper.getMainLooper())
                        // If no fix arrives within 15s, stop listening (avoids a leaked listener)
                        // and route to the IP-based fallback so the caller is not left hanging.
                        timeoutHandler.postDelayed({
                            try {
                                locationManager.removeUpdates(singleListener)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
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
                    val utcOffsetStr = json.optString("utc_offset", "+0000")
                    
                    var timezoneOffset = 0.0
                    try {
                        if (utcOffsetStr.isNotEmpty() && (utcOffsetStr.startsWith("+") || utcOffsetStr.startsWith("-")) && utcOffsetStr.length >= 5) {
                            val sign = if (utcOffsetStr[0] == '-') -1.0 else 1.0
                            val hours = utcOffsetStr.substring(1, 3).toDoubleOrNull() ?: 0.0
                            val mins = utcOffsetStr.substring(3, 5).toDoubleOrNull() ?: 0.0
                            timezoneOffset = sign * (hours + mins / 60.0)
                        } else {
                            timezoneOffset = Math.round(lng / 15.0).toDouble().coerceIn(-12.0, 14.0)
                        }
                    } catch (e: Exception) {
                        timezoneOffset = Math.round(lng / 15.0).toDouble().coerceIn(-12.0, 14.0)
                    }
                    
                    val name = if (city.isNotEmpty() && country.isNotEmpty()) {
                        "$city, $country"
                    } else if (city.isNotEmpty()) {
                        city
                    } else {
                        "Auto IP Location"
                    }
                    
                    if (lat != 0.0 || lng != 0.0) {
                        saveLocation(lat, lng, timezoneOffset, name)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onSuccess(lat, lng, timezoneOffset, name)
                        }
                        return@Thread
                    }
                }
                throw Exception("IP Geolocation API response not valid")
            } catch (e: Exception) {
                // Secondary fallback API: ip-api
                try {
                    val url2 = URL("http://ip-api.com/json")
                    val connection2 = url2.openConnection() as HttpURLConnection
                    connection2.requestMethod = "GET"
                    connection2.connectTimeout = 3000
                    connection2.readTimeout = 3000
                    
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
                        if (json2.optString("status") == "success") {
                            val lat = json2.optDouble("lat", 0.0)
                            val lng = json2.optDouble("lon", 0.0)
                            val city = json2.optString("city", "")
                            val country = json2.optString("countryCode", "")
                            
                            val timezoneOffset = Math.round(lng / 15.0).toDouble().coerceIn(-12.0, 14.0)
                            val name = if (city.isNotEmpty() && country.isNotEmpty()) {
                                "$city, $country"
                            } else if (city.isNotEmpty()) {
                                city
                            } else {
                                "Auto Network Location"
                            }
                            
                            if (lat != 0.0 || lng != 0.0) {
                                saveLocation(lat, lng, timezoneOffset, name)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    onSuccess(lat, lng, timezoneOffset, name)
                                }
                                return@Thread
                            }
                        }
                    }
                } catch (pe: Exception) {
                    pe.printStackTrace()
                }
                
                android.os.Handler(android.os.Looper.getMainLooper()).post {
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
        val lat = location.latitude
        val lng = location.longitude
        
        // Simple timezone offset formula based on longitude (15 degrees = 1 hour)
        val rawTzOffset = Math.round(lng / 15.0).toDouble()
        val roundedTzOffset = Math.max(-12.0, Math.min(14.0, rawTzOffset))
        
        // Run blocking reverse geocoding on a background thread to prevent NetworkOnMainThreadException
        Thread {
            var detectedName = ""
            try {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lng, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        val city = address.locality ?: address.subAdminArea ?: address.adminArea ?: address.thoroughfare ?: ""
                        val country = address.countryCode ?: address.countryName ?: ""
                        detectedName = if (city.isNotEmpty() && country.isNotEmpty()) {
                            "$city, $country"
                        } else if (city.isNotEmpty()) {
                            city
                        } else if (address.countryName != null) {
                            address.countryName
                        } else {
                            ""
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
                            val city = addrObj.optString("city", "")
                                .ifEmpty { addrObj.optString("town", "") }
                                .ifEmpty { addrObj.optString("village", "") }
                                .ifEmpty { addrObj.optString("suburb", "") }
                                .ifEmpty { addrObj.optString("hamlet", "") }
                                .ifEmpty { addrObj.optString("county", "") }
                            val country = addrObj.optString("country_code", "").uppercase().ifEmpty {
                                addrObj.optString("country", "")
                            }
                            
                            detectedName = if (city.isNotEmpty() && country.isNotEmpty()) {
                                "$city, $country"
                            } else if (city.isNotEmpty()) {
                                city
                            } else {
                                country
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
            
            saveLocation(lat, lng, roundedTzOffset, detectedName)
            
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onSuccess(lat, lng, roundedTzOffset, detectedName)
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    fun requestCurrentLocation(
        onSuccess: (lat: Double, lng: Double, timezoneOffset: Double, name: String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        try {
            val cts = CancellationTokenSource()
            locationCts = cts
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cts.token
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    processLocation(location, onSuccess, onFailure)
                } else {
                    // Fallback to Fused Location lastLocation
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                        if (lastLoc != null) {
                            processLocation(lastLoc, onSuccess, onFailure)
                        } else {
                            // Fallback to native LocationManager (GPS/Network)
                            getSystemLocationFallback(onSuccess, onFailure)
                        }
                    }.addOnFailureListener {
                        getSystemLocationFallback(onSuccess, onFailure)
                    }
                }
            }.addOnFailureListener { exception ->
                // Fallback to native LocationManager (GPS/Network)
                getSystemLocationFallback(onSuccess, onFailure)
            }
        } catch (e: SecurityException) {
            onFailure(e)
        }
    }

    fun searchCity(query: String): List<CityInfo> {
        val trimQuery = query.trim()
        if (trimQuery.length < 2) return emptyList()
        
        // 1. Online lookup via 100% free Open-Meteo Geocoding API with no API Key
        try {
            val encodedQuery = URLEncoder.encode(trimQuery, "UTF-8")
            val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=$encodedQuery&count=10&language=en")
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
                        
                        val tzOffset = if (tzString.isNotEmpty()) {
                            try {
                                val tz = java.util.TimeZone.getTimeZone(tzString)
                                tz.rawOffset / (1000.0 * 60.0 * 60.0)
                            } catch (e: Exception) {
                                Math.round(longitude / 15.0).toDouble()
                            }
                        } else {
                            Math.round(longitude / 15.0).toDouble()
                        }
                        
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
            val url = URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=10&accept-language=en")
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
                    
                    var tzOffset = Math.round(longitude / 15.0).toDouble()
                    if (displayName.contains("India", ignoreCase = true) || country.contains("India", ignoreCase = true)) {
                        tzOffset = 5.5
                    }
                    
                    if (name.isNotEmpty()) {
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
