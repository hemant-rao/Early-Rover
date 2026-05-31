package com.example.alarm.weather

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** A coarse weather category that drives the animated dashboard background. */
enum class WeatherCondition {
    CLEAR, FEW_CLOUDS, CLOUDS, FOG, RAIN, SNOW, THUNDER
}

data class WeatherInfo(
    val condition: WeatherCondition,
    val isDay: Boolean,
    val temperatureC: Double,
    val code: Int
) {
    val description: String
        get() = when (condition) {
            WeatherCondition.CLEAR -> if (isDay) "Clear sky" else "Clear night"
            WeatherCondition.FEW_CLOUDS -> "Partly cloudy"
            WeatherCondition.CLOUDS -> "Cloudy"
            WeatherCondition.FOG -> "Foggy"
            WeatherCondition.RAIN -> "Rain"
            WeatherCondition.SNOW -> "Snow"
            WeatherCondition.THUNDER -> "Thunderstorm"
        }
}

/**
 * Fetches current conditions from the free, key-less Open-Meteo forecast API
 * (the same provider already used for geocoding). Network is done on a background
 * dispatcher by the caller; this class only performs the blocking request.
 */
object WeatherRepository {

    fun fetchCurrent(latitude: Double, longitude: Double): WeatherInfo? {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                    "&current=temperature_2m,weather_code,is_day&timezone=auto"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val current = JSONObject(response).optJSONObject("current") ?: return null

            val code = current.optInt("weather_code", 0)
            val isDay = current.optInt("is_day", 1) == 1
            val temp = current.optDouble("temperature_2m", Double.NaN)
            WeatherInfo(mapCode(code), isDay, temp, code)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Maps WMO weather interpretation codes to our coarse categories. */
    private fun mapCode(code: Int): WeatherCondition = when (code) {
        0 -> WeatherCondition.CLEAR
        1, 2 -> WeatherCondition.FEW_CLOUDS
        3 -> WeatherCondition.CLOUDS
        45, 48 -> WeatherCondition.FOG
        in 51..67 -> WeatherCondition.RAIN
        in 71..77 -> WeatherCondition.SNOW
        in 80..82 -> WeatherCondition.RAIN
        85, 86 -> WeatherCondition.SNOW
        95, 96, 99 -> WeatherCondition.THUNDER
        else -> WeatherCondition.CLOUDS
    }
}
