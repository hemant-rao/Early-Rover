package com.example.alarm.data

import com.example.alarm.SunCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Single source of truth for turning a SUNRISE/SUNSET [Alarm] into the clock time it should fire at.
 *
 * The defining rule: a sun-alarm is resolved against ITS OWN bound location ([Alarm.latitude] etc.),
 * never against whatever location the app is currently showing. This is what keeps each city's
 * sun-alarm independent — switching the active city can no longer overwrite another city's alarm.
 *
 * Pure JVM (no Android), so it is unit-testable and shared by the ViewModel, the save flow, and the
 * boot/timezone reschedule receiver — keeping all three from drifting apart.
 */
object SunAlarmResolver {

    /** A location an alarm can be bound to. */
    data class Location(
        val latitude: Double,
        val longitude: Double,
        val timezoneOffset: Double,
        val name: String
    )

    /**
     * The location to compute [alarm] against: its own bound location, or [fallback] (the active
     * location) when the alarm predates per-location binding ([Alarm.hasLocation] == false).
     */
    fun locationFor(alarm: Alarm, fallback: Location): Location =
        if (alarm.hasLocation()) {
            Location(alarm.latitude, alarm.longitude, alarm.timezoneOffset, alarm.locationName)
        } else {
            fallback
        }

    /**
     * The fixed-offset zone for a location's decimal [timezoneOffsetHours] (e.g. 5.5 -> +05:30).
     *
     * Throughout the app the stored offset is a RAW/standard offset (no DST) — it is the same value
     * [SunCalculator] bakes into the sun time. Interpreting that sun time back in THIS exact zone is
     * what recovers the true astronomical instant, regardless of DST or the phone's own timezone.
     * Shared by the scheduler so the "compute" and "fire" sides can never drift apart.
     */
    fun zoneOf(timezoneOffsetHours: Double): ZoneOffset =
        ZoneOffset.ofTotalSeconds(Math.round(timezoneOffsetHours * 3600.0).toInt())

    /**
     * Clock time a SUNRISE/SUNSET alarm should fire at on [date], at [location], including the
     * user's before/after [offsetMinutes]. Falls back to 06:00 / 18:00 for polar days where the sun
     * never rises or sets (matching the rest of the app).
     */
    fun targetTime(alarmType: String, location: Location, date: LocalDate, offsetMinutes: Int): LocalTime {
        val sun = SunCalculator.calculateSunTimes(
            location.latitude, location.longitude, date, location.timezoneOffset
        )
        val base = if (alarmType == "SUNRISE") {
            sun.sunrise ?: LocalTime.of(6, 0)
        } else {
            sun.sunset ?: LocalTime.of(18, 0)
        }
        return base.plusMinutes(offsetMinutes.toLong())
    }

    /**
     * Returns [alarm] with its location bound and its hour/minute recomputed for [date]. CUSTOM
     * alarms are returned unchanged. Used wherever a sun-alarm needs (re)calibrating.
     *
     * [date] defaults to "today in the resolved location's own timezone" — not the phone's date — so
     * an alarm bound to a far-away city still calibrates against the right calendar day there (the
     * phone can be on a different date when crossing the date line). Tests pass an explicit [date]
     * to stay deterministic.
     */
    fun recalibrate(alarm: Alarm, fallback: Location, date: LocalDate? = null): Alarm {
        if (alarm.alarmType != "SUNRISE" && alarm.alarmType != "SUNSET") return alarm
        val loc = locationFor(alarm, fallback)
        val effectiveDate = date ?: LocalDate.now(zoneOf(loc.timezoneOffset))
        val target = targetTime(alarm.alarmType, loc, effectiveDate, alarm.offsetMinutes)
        return alarm.copy(
            hour = target.hour,
            minute = target.minute,
            latitude = loc.latitude,
            longitude = loc.longitude,
            timezoneOffset = loc.timezoneOffset,
            locationName = loc.name,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * The absolute instant [alarm] should next fire at, relative to [now]. Pure and timezone-explicit
     * so it can be unit-tested without Android, and shared by the scheduler.
     *
     * The critical rule: a SUNRISE/SUNSET alarm bound to a location stores its hour/minute as the
     * wall-clock time IN THAT LOCATION'S timezone (that is how [SunCalculator] computed it). So it must
     * be interpreted back in that same zone — never the phone's. This is what makes an alarm for a city
     * in another timezone fire at that city's real sun moment instead of the same digits on the phone's
     * clock. CUSTOM (and any unbound) alarms keep ordinary "device local clock time" semantics via
     * [deviceZone]. Weekday matching for repeating alarms also happens in the alarm's own zone.
     */
    fun nextTriggerInstant(alarm: Alarm, now: Instant, deviceZone: ZoneId): Instant {
        val zone = zoneFor(alarm, deviceZone)
        val today = now.atZone(zone).toLocalDate()
        val repeatDays = alarm.getRepeatDaysList()

        if (repeatDays.isEmpty()) {
            val todayFire = today.atTime(alarm.hour, alarm.minute).atZone(zone).toInstant()
            return if (todayFire.isAfter(now)) todayFire
            else today.plusDays(1).atTime(alarm.hour, alarm.minute).atZone(zone).toInstant()
        }

        // Repeating: nearest selected weekday whose time is strictly in the future.
        // java.time DayOfWeek.value is 1=Mon..7=Sun, matching this app's repeat-day scheme.
        for (add in 0..7) {
            val day = today.plusDays(add.toLong())
            if (repeatDays.contains(day.dayOfWeek.value)) {
                val fire = day.atTime(alarm.hour, alarm.minute).atZone(zone).toInstant()
                if (fire.isAfter(now)) return fire
            }
        }
        // Unreachable in practice (a non-empty repeat set always matches within 8 days).
        return today.atTime(alarm.hour, alarm.minute).atZone(zone).toInstant()
    }

    /** The zone an alarm's stored hour/minute must be interpreted in. */
    private fun zoneFor(alarm: Alarm, deviceZone: ZoneId): ZoneId =
        if ((alarm.alarmType == "SUNRISE" || alarm.alarmType == "SUNSET") && alarm.hasLocation()) {
            zoneOf(alarm.timezoneOffset)
        } else {
            deviceZone
        }
}
