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

data class DetailedWeatherInfo(
    val current: WeatherInfo,
    val relativeHumidity: Int,
    val apparentTemperatureC: Double,
    val precipitationMm: Double,
    val windSpeedKmh: Double,
    val hourlyList: List<HourlyDetail>,
    val dailyList: List<DailyDetail>
)

/**
 * Fetches current conditions and extensive forecast/history reports from the free, key-less
 * Open-Meteo API. Supports 10 past days and 10 upcoming days.
 */
object WeatherRepository {

    fun fetchCurrent(latitude: Double, longitude: Double): WeatherInfo? {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                    "&current=temperature_2m,weather_code,is_day,cloud_cover&timezone=auto"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val current = JSONObject(response).optJSONObject("current") ?: return null

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
     * Fetches details required for high fidelity Weather apps:
     * - Next 24 hours of hourly records
     * - 10 past days & 10 future days of records (21 days in total)
     * - Humidity, apparent temperature, precipitation, wind speed, wind gusts
     */
    fun fetchDetailed(latitude: Double, longitude: Double): DetailedWeatherInfo? {
        return try {
            val urlString = "https://api.open-meteo.com/v1/forecast?" +
                    "latitude=$latitude" +
                    "&longitude=$longitude" +
                    "&current=temperature_2m,weather_code,is_day,cloud_cover,relative_humidity_2m,apparent_temperature,precipitation,wind_speed_10m" +
                    "&hourly=temperature_2m,weather_code,relative_humidity_2m,apparent_temperature,precipitation,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                    "&past_days=10" +
                    "&forecast_days=11" +
                    "&timezone=auto"
            
            val url = URL(urlString)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val root = JSONObject(response)
            
            val currentJson = root.optJSONObject("current") ?: return null
            val code = currentJson.optInt("weather_code", -1)
            val cloudCover = currentJson.optInt("cloud_cover", -1)
            val isDay = currentJson.optInt("is_day", 1) == 1
            val temp = currentJson.optDouble("temperature_2m", 0.0)
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
                    val tVal = temps?.optDouble(i) ?: 0.0
                    val codeVal = codes?.optInt(i, 0) ?: 0
                    val hum = humidities?.optInt(i, 0) ?: 0
                    val app = appTemps?.optDouble(i) ?: 0.0
                    val prec = precips?.optDouble(i) ?: 0.0
                    val wind = winds?.optDouble(i) ?: 0.0

                    hourlyList.add(
                        HourlyDetail(
                            timeIso = tIso,
                            temperatureC = if (tVal.isNaN()) 0.0 else tVal,
                            condition = mapCode(codeVal),
                            rawCode = codeVal,
                            humidityPercent = hum,
                            apparentTempC = if (app.isNaN()) 0.0 else app,
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
                    val codeVal = codes?.optInt(i, 0) ?: 0
                    val maxT = maxTemps?.optDouble(i) ?: 0.0
                    val minT = minTemps?.optDouble(i) ?: 0.0
                    val rise = sunrises?.optString(i) ?: ""
                    val set = sunsets?.optString(i) ?: ""

                    dailyList.add(
                        DailyDetail(
                            dateIso = dIso,
                            maxTempC = if (maxT.isNaN()) 0.0 else maxT,
                            minTempC = if (minT.isNaN()) 0.0 else minT,
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
                dailyList = dailyList
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
