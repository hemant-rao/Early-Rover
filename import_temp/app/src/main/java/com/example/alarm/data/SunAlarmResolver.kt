package com.example.alarm.data

import com.example.alarm.PolarState
import com.example.alarm.SunCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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
     * Date+time a SUNRISE/SUNSET alarm should fire at on [date], at [location], including the user's
     * before/after [offsetMinutes].
     *
     * Returns a [LocalDateTime] (not a bare [LocalTime]) so a negative [offsetMinutes] that pushes the
     * moment before midnight correctly rolls back to the previous calendar day instead of silently
     * wrapping to a wrong time on the same day.
     *
     * Polar handling (sun never rises/sets that day) picks a sensible substitute base time:
     *  - POLAR_DAY (sun up all day): a SUNRISE is treated as already-risen at 00:00; a SUNSET as the
     *    last moment of the day, 23:59.
     *  - POLAR_NIGHT (sun down all day): falls back to the app's nominal 06:00 / 18:00.
     * The plain 06:00 / 18:00 defaults are kept only as a no-data fallback (PolarState.NONE but a null
     * time, which should not normally happen).
     */
    fun targetTime(alarmType: String, location: Location, date: LocalDate, offsetMinutes: Int): LocalDateTime {
        val sun = SunCalculator.calculateSunTimes(
            location.latitude, location.longitude, date, location.timezoneOffset
        )
        val isSunrise = alarmType == "SUNRISE"
        val base: LocalTime = when (sun.polar) {
            PolarState.POLAR_DAY ->
                if (isSunrise) LocalTime.of(0, 0) else LocalTime.of(23, 59)
            PolarState.POLAR_NIGHT ->
                if (isSunrise) LocalTime.of(6, 0) else LocalTime.of(18, 0)
            PolarState.NONE ->
                if (isSunrise) (sun.sunrise ?: LocalTime.of(6, 0))
                else (sun.sunset ?: LocalTime.of(18, 0))
        }
        return date.atTime(base).plusMinutes(offsetMinutes.toLong())
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
            // target may roll to the previous day for large negative offsets; hour/minute is still the
            // wall-clock the scheduler interprets per-date via fireTimeOn, so only the clock time is stored.
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
        val day = nextOccurrenceDate(alarm, now, deviceZone)
        return fireDateTimeOn(alarm, day).atZone(zone).toInstant()
    }

    /**
     * The sun-event/calendar date (in the alarm's own zone) of the occurrence the alarm will next
     * fire for, relative to [now]. This is the date the user selected the sun event on — NOT
     * necessarily the date of the resulting fire INSTANT, which a large negative offset may roll back
     * to the previous day (handled in [fireDateTimeOn]). [nextTriggerInstant] turns this date into the
     * absolute instant; the ViewModel's "skip today only" action marks this date as skipped.
     *
     * Honors any [Alarm.skipDate] already set, so skipping repeatedly walks forward one occurrence at
     * a time instead of re-marking the same day. A stale (past) or empty skipDate is ignored.
     */
    fun nextOccurrenceDate(alarm: Alarm, now: Instant, deviceZone: ZoneId): LocalDate {
        val zone = zoneFor(alarm, deviceZone)
        val today = now.atZone(zone).toLocalDate()
        val repeatDays = alarm.getRepeatDaysList()
        // A non-empty, non-stale skip date (the single upcoming occurrence the user chose to skip).
        // Parsed in the alarm's own zone terms via [today]; a date strictly before today is stale.
        val skip = skipDateFor(alarm, today)

        if (repeatDays.isEmpty()) {
            val todayFire = fireDateTimeOn(alarm, today).atZone(zone).toInstant()
            val firstDay = if (todayFire.isAfter(now)) today else today.plusDays(1)
            // If that one occurrence is the skipped date, advance one more day.
            return if (skip != null && firstDay == skip) firstDay.plusDays(1) else firstDay
        }

        // Repeating: nearest selected weekday whose time is strictly in the future.
        // java.time DayOfWeek.value is 1=Mon..7=Sun, matching this app's repeat-day scheme.
        // Match the weekday against the sun-event day the user selected; the resulting fire INSTANT
        // may still shift to the previous calendar day for a large negative offset (handled inside
        // fireDateTimeOn), which is the astronomically correct moment.
        // Scan up to 15 days so a skipped occurrence can always advance to the following one.
        for (add in 0..14) {
            val day = today.plusDays(add.toLong())
            if (repeatDays.contains(day.dayOfWeek.value)) {
                // Skip the single chosen occurrence; the next matching weekday is used instead.
                if (skip != null && day == skip) continue
                val fire = fireDateTimeOn(alarm, day).atZone(zone).toInstant()
                if (fire.isAfter(now)) return day
            }
        }
        // Unreachable in practice (a non-empty repeat set always matches within 8 days).
        return today
    }

    /**
     * Parses [Alarm.skipDate] into a [LocalDate], or null when it is empty, unparseable, or stale
     * (strictly before [today] in the alarm's own zone). Stale skip dates are ignored so a skip the
     * user set in the past can never suppress a future occurrence.
     */
    private fun skipDateFor(alarm: Alarm, today: LocalDate): LocalDate? {
        if (alarm.skipDate.isEmpty()) return null
        val parsed = try {
            LocalDate.parse(alarm.skipDate)
        } catch (e: Exception) {
            return null
        }
        return if (parsed.isBefore(today)) null else parsed
    }

    /**
     * The wall-clock time [alarm] should fire at on a SPECIFIC [date], in its own zone's terms.
     *
     * For a location-bound SUNRISE/SUNSET this recomputes the sun time for THAT date (sunrise/sunset
     * drift day to day, so reusing the cached [Alarm.hour]/[Alarm.minute] would slowly go stale). For
     * CUSTOM and unbound alarms the stored clock digits are authoritative and returned as-is.
     */
    fun fireTimeOn(alarm: Alarm, date: LocalDate): LocalTime =
        fireDateTimeOn(alarm, date).toLocalTime()

    /**
     * The full date+time [alarm] should fire at, for the sun event of [date], in its own zone's terms.
     *
     * Unlike [fireTimeOn] this preserves the day-shift that [targetTime] applies for a negative
     * [Alarm.offsetMinutes] that pushes the moment before midnight: e.g. a high-latitude sunrise at
     * 00:10 with a -30 'before' offset belongs astronomically to the PREVIOUS calendar day at 23:40.
     * The scheduler uses this (not [fireTimeOn]) so that previous-day rollover is honored instead of
     * being re-attached to the query date. For CUSTOM and unbound alarms the stored clock digits on
     * the given date are authoritative.
     */
    fun fireDateTimeOn(alarm: Alarm, date: LocalDate): LocalDateTime =
        if ((alarm.alarmType == "SUNRISE" || alarm.alarmType == "SUNSET") && alarm.hasLocation()) {
            val loc = Location(alarm.latitude, alarm.longitude, alarm.timezoneOffset, alarm.locationName)
            targetTime(alarm.alarmType, loc, date, alarm.offsetMinutes)
        } else {
            date.atTime(alarm.hour, alarm.minute)
        }

    /** The zone an alarm's stored hour/minute must be interpreted in. */
    private fun zoneFor(alarm: Alarm, deviceZone: ZoneId): ZoneId =
        if ((alarm.alarmType == "SUNRISE" || alarm.alarmType == "SUNSET") && alarm.hasLocation()) {
            zoneOf(alarm.timezoneOffset)
        } else {
            deviceZone
        }
}
