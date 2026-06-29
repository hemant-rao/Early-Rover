package com.example.alarm.weather

import com.example.alarm.maps.OlaMapsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

data class HourlyDetail(
    val timeIso: String,
    val temperatureC: Double,
    val condition: WeatherCondition,
    val rawCode: Int,
    val humidityPercent: Int,
    val apparentTempC: Double,
    val precipitationMm: Double,
    val windSpeedKmh: Double
)

data class DailyDetail(
    val dateIso: String,
    val maxTempC: Double,
    val minTempC: Double,
    val condition: WeatherCondition,
    val rawCode: Int,
    val sunriseIso: String,
    val sunsetIso: String
)

data class AirQualityInfo(
    val europeanAqi: Int,
    val usAqi: Int,
    val pm25: Double,
    val pm10: Double
)

data class DetailedWeatherInfo(
    val current: WeatherInfo,
    val relativeHumidity: Int,
    val apparentTemperatureC: Double,
    val precipitationMm: Double,
    val windSpeedKmh: Double,
    val hourlyList: List<HourlyDetail>,
    val dailyList: List<DailyDetail>,
    val timezoneOffset: Double? = null
)

/**
 * §689 — Fetches current conditions + extensive forecast/history reports through the
 * OdioBook GEO gateway ({server}/api/geo/weather/), which proxies Open-Meteo and
 * returns its JSON VERBATIM — so the parsing below is unchanged, only the URL moved.
 * Centralising via the gateway lets the admin disable/centralise weather; the gateway
 * supplies all Open-Meteo query params, so the app only sends lat/lon/app.
 */
object WeatherRepository {

    /** OdioBook server base (single source of truth = OlaMapsRepository.serverBaseUrl). */
    private fun base(): String =
        OlaMapsRepository.serverBaseUrl.trim().trimEnd('/').ifBlank { OlaMapsRepository.DEFAULT_SERVER }

    suspend fun fetchCurrent(latitude: Double, longitude: Double): WeatherInfo? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "${base()}/api/geo/weather/current?lat=$latitude&lon=$longitude&app=earlyrover"
                )
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    connection.errorStream?.use { it.readBytes() }
                    connection.disconnect()
                    return@withContext null
                }

                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                val current = JSONObject(response).optJSONObject("current") ?: return@withContext null

                // Missing weather_code -> -1 (unknown) so we don't silently fall back to "clear".
                val code = current.optInt("weather_code", -1)
                val cloudCover = current.optInt("cloud_cover", -1)
                val isDay = current.optInt("is_day", 1) == 1
                val temp = current.optDouble("temperature_2m", Double.NaN)
                WeatherInfo(refineCondition(code, cloudCover), isDay, temp, code)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    /**
     * Fetches current air-quality indices (European AQI, US AQI) and particulate matter
     * concentrations from the key-less Open-Meteo air-quality API. Returns null on any failure.
     */
    suspend fun fetchAirQuality(latitude: Double, longitude: Double): AirQualityInfo? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "${base()}/api/geo/weather/air-quality?lat=$latitude&lon=$longitude&app=earlyrover"
                )
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    connection.errorStream?.use { it.readBytes() }
                    connection.disconnect()
                    return@withContext null
                }

                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                val current = JSONObject(response).optJSONObject("current") ?: return@withContext null

                val europeanAqi = current.optInt("european_aqi", -1)
                val usAqi = current.optInt("us_aqi", -1)
                val pm25 = current.optDouble("pm2_5", Double.NaN)
                val pm10 = current.optDouble("pm10", Double.NaN)
                AirQualityInfo(europeanAqi, usAqi, pm25, pm10)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    /**
     * Fetches details required for high fidelity Weather apps:
     * - Next 24 hours of hourly records
     * - 10 past days & 10 future days of records (21 days in total)
     * - Humidity, apparent temperature, precipitation, wind speed, wind gusts
     */
    suspend fun fetchDetailed(latitude: Double, longitude: Double): DetailedWeatherInfo? =
        withContext(Dispatchers.IO) {
        try {
            // §689 — gateway supplies the full Open-Meteo param set (hourly/daily/
            // past_days=10/forecast_days=11/timezone=auto); response is verbatim.
            val urlString = "${base()}/api/geo/weather/detailed?lat=$latitude&lon=$longitude&app=earlyrover"

            val url = URL(urlString)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.use { it.readBytes() }
                connection.disconnect()
                return@withContext null
            }

            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val root = JSONObject(response)
            
            val utcOffsetSeconds = root.optInt("utc_offset_seconds", Int.MAX_VALUE)
            val tzOffset = if (utcOffsetSeconds != Int.MAX_VALUE) utcOffsetSeconds / 3600.0 else null

            val currentJson = root.optJSONObject("current") ?: return@withContext null
            val code = currentJson.optInt("weather_code", -1)
            val cloudCover = currentJson.optInt("cloud_cover", -1)
            val isDay = currentJson.optInt("is_day", 1) == 1
            val temp = currentJson.optDouble("temperature_2m", Double.NaN)
            val relativeHumidity = currentJson.optInt("relative_humidity_2m", 0)
            val apparentTemperature = currentJson.optDouble("apparent_temperature", temp)
            val precipitation = currentJson.optDouble("precipitation", 0.0)
            val windSpeed = currentJson.optDouble("wind_speed_10m", 0.0)

            val currentInfo = WeatherInfo(refineCondition(code, cloudCover), isDay, temp, code)

            // Parse Hourly List
            val hourlyObj = root.optJSONObject("hourly")
            val hourlyList = mutableListOf<HourlyDetail>()
            if (hourlyObj != null) {
                val times = hourlyObj.optJSONArray("time")
                val temps = hourlyObj.optJSONArray("temperature_2m")
                val codes = hourlyObj.optJSONArray("weather_code")
                val humidities = hourlyObj.optJSONArray("relative_humidity_2m")
                val appTemps = hourlyObj.optJSONArray("apparent_temperature")
                val precips = hourlyObj.optJSONArray("precipitation")
                val winds = hourlyObj.optJSONArray("wind_speed_10m")

                val length = times?.length() ?: 0
                for (i in 0 until length) {
                    val tIso = times?.optString(i) ?: ""
                    val tVal = temps?.optDouble(i) ?: Double.NaN
                    val codeVal = codes?.optInt(i, -1) ?: -1
                    val hum = humidities?.optInt(i, 0) ?: 0
                    val app = appTemps?.optDouble(i) ?: Double.NaN
                    val prec = precips?.optDouble(i) ?: 0.0
                    val wind = winds?.optDouble(i) ?: 0.0

                    hourlyList.add(
                        HourlyDetail(
                            timeIso = tIso,
                            temperatureC = tVal,
                            condition = mapCode(codeVal),
                            rawCode = codeVal,
                            humidityPercent = hum,
                            apparentTempC = app,
                            precipitationMm = if (prec.isNaN()) 0.0 else prec,
                            windSpeedKmh = if (wind.isNaN()) 0.0 else wind
                        )
                    )
                }
            }

            // Parse Daily List
            val dailyObj = root.optJSONObject("daily")
            val dailyList = mutableListOf<DailyDetail>()
            if (dailyObj != null) {
                val times = dailyObj.optJSONArray("time")
                val codes = dailyObj.optJSONArray("weather_code")
                val maxTemps = dailyObj.optJSONArray("temperature_2m_max")
                val minTemps = dailyObj.optJSONArray("temperature_2m_min")
                val sunrises = dailyObj.optJSONArray("sunrise")
                val sunsets = dailyObj.optJSONArray("sunset")

                val length = times?.length() ?: 0
                for (i in 0 until length) {
                    val dIso = times?.optString(i) ?: ""
                    val codeVal = codes?.optInt(i, -1) ?: -1
                    val maxT = maxTemps?.optDouble(i) ?: Double.NaN
                    val minT = minTemps?.optDouble(i) ?: Double.NaN
                    val rise = sunrises?.optString(i) ?: ""
                    val set = sunsets?.optString(i) ?: ""

                    dailyList.add(
                        DailyDetail(
                            dateIso = dIso,
                            maxTempC = maxT,
                            minTempC = minT,
                            condition = mapCode(codeVal),
                            rawCode = codeVal,
                            sunriseIso = rise,
                            sunsetIso = set
                        )
                    )
                }
            }

            DetailedWeatherInfo(
                current = currentInfo,
                relativeHumidity = relativeHumidity,
                apparentTemperatureC = apparentTemperature,
                precipitationMm = precipitation,
                windSpeedKmh = windSpeed,
                hourlyList = hourlyList,
                dailyList = dailyList,
                timezoneOffset = tzOffset
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        }

    /**
     * Combines the WMO [code] with the measured [cloudCover] percent so the visible sky
     * matches reality. Precipitation / fog / snow / thunder codes always win. For the
     * clear↔overcast family we trust the actual cloud cover (the WMO code alone often
     * reports "mainly clear" on a hazy/overcast day, which is why the app used to show
     * "clear sky" while every other app showed clouds). [cloudCover] < 0 means unknown.
     */
    fun refineCondition(code: Int, cloudCover: Int): WeatherCondition {
        val base = mapCode(code)
        val isClearFamily = base == WeatherCondition.CLEAR ||
            base == WeatherCondition.FEW_CLOUDS ||
            base == WeatherCondition.CLOUDS
        // Only the clear/cloud family is refined by cloud cover; keep rain/snow/fog/thunder intact.
        if (isClearFamily && cloudCover >= 0) {
            return when {
                cloudCover < 12 -> WeatherCondition.CLEAR
                cloudCover < 50 -> WeatherCondition.FEW_CLOUDS
                else -> WeatherCondition.CLOUDS
            }
        }
        return base
    }

    /** Maps WMO weather interpretation codes to our coarse categories. */
    fun mapCode(code: Int): WeatherCondition = when (code) {
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
