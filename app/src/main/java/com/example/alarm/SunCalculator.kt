package com.example.alarm

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.*

/**
 * Distinguishes the two "no sun event" cases that both yield null sunrise/sunset, so callers can
 * pick sensible substitute times (the sun is already up all day vs. down all day).
 */
enum class PolarState { NONE, POLAR_DAY, POLAR_NIGHT }

object SunCalculator {

    data class SunTimes(
        val sunrise: LocalTime?,
        val sunset: LocalTime?,
        val polar: PolarState = PolarState.NONE
    )

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
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

        // Equation of time and declination of Sun (NOAA simple formula)
        // Gamma in radians (use the actual length of the year so leap years don't bias the angle).
        // The NOAA method nominally evaluates EoT/declination at the location's solar transit, not at
        // 00:00 UTC. For longitudes far from the prime meridian (worst near the date line) anchoring at
        // UTC midnight leaves the ephemeris up to ~half a day stale, biasing sunrise/sunset by ~1 minute.
        // To remove that bias we estimate the solar noon longitude-aware, recompute gamma once at that
        // transit, and use the refined EoT/declination below.
        val daysInYear = if (leap) 366.0 else 365.0

        fun gammaFor(fracDay: Double) = 2.0 * Math.PI / daysInYear * (dy - 1.0 + fracDay)

        fun eqTimeFor(g: Double) =
            229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2.0 * g) - 0.040849 * sin(2.0 * g))

        // First pass: anchor at noon UTC (fracDay == 0, matching the existing 00:00-UTC reference).
        val eqTimeFirst = eqTimeFor(gammaFor(0.0))

        // Estimate solar noon in UTC minutes, then map it to a fractional-day offset where noon UTC -> 0.
        val noonUtcMin = 720.0 - 4.0 * longitude - eqTimeFirst
        val fracDay = noonUtcMin / 1440.0 - 0.5

        // Second pass: gamma now tracks the location's actual solar transit.
        val gamma = gammaFor(fracDay)

        // Equation of time in minutes
        val eqTime = eqTimeFor(gamma)

        // Solar declination in radians
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2.0 * gamma) + 0.000907 * sin(2.0 * gamma) - 0.002697 * cos(3.0 * gamma) + 0.00148 * sin(3.0 * gamma)

        // Hour angle for sunrise/sunset (standard astronomical zenith angle of 90.833 degrees)
        val zenithRad = Math.toRadians(90.833)
        val latRad = Math.toRadians(latitude)
        
        val cosH = (cos(zenithRad) / (cos(latRad) * cos(decl))) - (tan(latRad) * tan(decl))
        
        if (cosH > 1.0) {
            // Polar night (sun never rises). The exact boundary cosH == 1.0 is a real grazing
            // sunrise/sunset at solar noon, so only strictly-beyond values are treated as polar.
            return SunTimes(null, null, PolarState.POLAR_NIGHT)
        }
        if (cosH < -1.0) {
            // Polar day (sun never sets); cosH == -1.0 is the 24h-grazing boundary, kept as a real event.
            return SunTimes(null, null, PolarState.POLAR_DAY)
        }

        // coerceIn guards acos against any tiny float overshoot past the strict comparisons above.
        val haRad = acos(cosH.coerceIn(-1.0, 1.0))
        val haDeg = Math.toDegrees(haRad)

        // Sunrise and sunset in minutes from UTC midnight
        // Solar Transit is 720 - 4*longitude - eqTime (approx solar noon)
        val sunriseUtc = 720.0 - 4.0 * (longitude + haDeg) - eqTime
        val sunsetUtc = 720.0 - 4.0 * (longitude - haDeg) - eqTime

        // Add local offset
        val offsetMinutes = timezoneOffsetHours * 60.0
        val sunriseLocalMinutes = (sunriseUtc + offsetMinutes + 1440.0) % 1440.0
        val sunsetLocalMinutes = (sunsetUtc + offsetMinutes + 1440.0) % 1440.0

        // Round to the nearest minute (less biased than truncation). The % 1440 carry guard prevents
        // a rounded-up value of exactly 1440 from producing an invalid hour 24.
        val sunriseTotal = Math.round(sunriseLocalMinutes).toInt() % 1440
        val sunsetTotal = Math.round(sunsetLocalMinutes).toInt() % 1440

        val sunriseHour = sunriseTotal / 60
        val sunriseMin = sunriseTotal % 60

        val sunsetHour = sunsetTotal / 60
        val sunsetMin = sunsetTotal % 60

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
