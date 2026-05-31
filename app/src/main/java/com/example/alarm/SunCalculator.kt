package com.example.alarm

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.*

object SunCalculator {

    data class SunTimes(val sunrise: LocalTime?, val sunset: LocalTime?)

    /**
     * Calculates the sunrise and sunset times for a given coordinate, date, and timezone offset.
     * @param latitude user's latitude in degrees
     * @param longitude user's longitude in degrees
     * @param date target calculation date
     * @param timezoneOffsetHours timezone offset in decimal hours (e.g., Eastern Standard Time is -5.0)
     */
    fun calculateSunTimes(latitude: Double, longitude: Double, date: LocalDate, timezoneOffsetHours: Double): SunTimes {
        val year = date.year
        val month = date.monthValue
        val day = date.dayOfMonth

        val dy = dayOfYear(year, month, day)
        
        // Equation of time and declination of Sun (NOAA simple formula)
        // Gamma in radians
        val gamma = 2.0 * Math.PI / 365.0 * (dy - 1.0 + (12.0 - 12.0) / 24.0)
        
        // Equation of time in minutes
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) - 0.014615 * cos(2.0 * gamma) - 0.040849 * sin(2.0 * gamma))
        
        // Solar declination in radians
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2.0 * gamma) + 0.000907 * sin(2.0 * gamma) - 0.002697 * cos(3.0 * gamma) + 0.00148 * sin(3.0 * gamma)

        // Hour angle for sunrise/sunset (standard astronomical zenith angle of 90.833 degrees)
        val zenithRad = Math.toRadians(90.833)
        val latRad = Math.toRadians(latitude)
        
        val cosH = (cos(zenithRad) / (cos(latRad) * cos(decl))) - (tan(latRad) * tan(decl))
        
        if (cosH > 1.0) {
            // Polar night (sun never rises)
            return SunTimes(null, null)
        }
        if (cosH < -1.0) {
            // Polar day (sun never sets)
            return SunTimes(null, null)
        }

        val haRad = acos(cosH)
        val haDeg = Math.toDegrees(haRad)

        // Sunrise and sunset in minutes from UTC midnight
        // Solar Transit is 720 - 4*longitude - eqTime (approx solar noon)
        val sunriseUtc = 720.0 - 4.0 * (longitude + haDeg) - eqTime
        val sunsetUtc = 720.0 - 4.0 * (longitude - haDeg) - eqTime

        // Add local offset
        val offsetMinutes = timezoneOffsetHours * 60.0
        val sunriseLocalMinutes = (sunriseUtc + offsetMinutes + 1440.0) % 1440.0
        val sunsetLocalMinutes = (sunsetUtc + offsetMinutes + 1440.0) % 1440.0

        val sunriseHour = (sunriseLocalMinutes / 60.0).toInt() % 24
        val sunriseMin = (sunriseLocalMinutes % 60.0).toInt()
        
        val sunsetHour = (sunsetLocalMinutes / 60.0).toInt() % 24
        val sunsetMin = (sunsetLocalMinutes % 60.0).toInt()

        return SunTimes(
            LocalTime.of(sunriseHour, sunriseMin),
            LocalTime.of(sunsetHour, sunsetMin)
        )
    }

    private fun dayOfYear(year: Int, month: Int, day: Int): Int {
        val days = intArrayOf(0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) {
            days[2] = 29
        }
        var count = 0
        for (i in 1 until month) {
            count += days[i]
        }
        return count + day
    }
}
