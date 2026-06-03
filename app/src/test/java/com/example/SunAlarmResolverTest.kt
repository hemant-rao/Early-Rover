package com.example

import com.example.alarm.data.Alarm
import com.example.alarm.data.SunAlarmResolver
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Tests for the per-location sun-alarm logic.
 *
 * Two layers are covered:
 *  1. [SunAlarmResolver.recalibrate]/targetTime — each alarm resolves against ITS OWN city, so
 *     switching the active city never rewrites another city's alarm (the original bug).
 *  2. [SunAlarmResolver.nextTriggerInstant] — the alarm's stored wall-clock time is interpreted in
 *     its OWN city's timezone, so a city in a different timezone fires at its real sun moment, not at
 *     the same digits on the phone's clock (the cross-timezone scheduling bug).
 *
 * Pure JVM (no Android deps), so these run on the host JVM.
 */
class SunAlarmResolverTest {

    // A fixed mid-year date keeps results deterministic (no dependence on "today").
    private val date = LocalDate.of(2026, 6, 15)

    // Well-separated cities with realistic RAW (standard, no-DST) timezone offsets — matching how the
    // app stores them (TimeZone.rawOffset / longitude-based).
    private val newYork = SunAlarmResolver.Location(40.7128, -74.0060, -5.0, "New York")
    private val london  = SunAlarmResolver.Location(51.5074, -0.1278, 0.0, "London")
    private val tokyo   = SunAlarmResolver.Location(35.6762, 139.6503, 9.0, "Tokyo")
    private val mumbai  = SunAlarmResolver.Location(19.0760, 72.8777, 5.5, "Mumbai")

    private fun sunriseAlarm(id: Int, loc: SunAlarmResolver.Location, offset: Int = 0, repeat: String = "") = Alarm(
        id = id, title = "Sunrise @ ${loc.name}", alarmType = "SUNRISE",
        hour = 0, minute = 0, offsetMinutes = offset, repeatDays = repeat,
        latitude = loc.latitude, longitude = loc.longitude,
        timezoneOffset = loc.timezoneOffset, locationName = loc.name
    )

    /** A SUNRISE alarm with explicit clock digits + location (for scheduling-layer tests). */
    private fun boundAlarmAt(hour: Int, minute: Int, loc: SunAlarmResolver.Location, repeat: String = "") = Alarm(
        id = 1, title = "t", alarmType = "SUNRISE",
        hour = hour, minute = minute, repeatDays = repeat,
        latitude = loc.latitude, longitude = loc.longitude,
        timezoneOffset = loc.timezoneOffset, locationName = loc.name
    )

    // ---------------------------------------------------------------------------------------------
    // Layer 1: per-location binding (the original "switching city overwrites the alarm" bug)
    // ---------------------------------------------------------------------------------------------

    /** Sanity: three different cities genuinely have three different local sunrise clock times. */
    @Test
    fun differentCities_haveDifferentSunriseTimes() {
        val ny = SunAlarmResolver.targetTime("SUNRISE", newYork, date, 0)
        val ldn = SunAlarmResolver.targetTime("SUNRISE", london, date, 0)
        val tyo = SunAlarmResolver.targetTime("SUNRISE", tokyo, date, 0)

        assertNotEquals("NY and London sunrise should differ", ny, ldn)
        assertNotEquals("London and Tokyo sunrise should differ", ldn, tyo)
        assertNotEquals("NY and Tokyo sunrise should differ", ny, tyo)
    }

    /** THE CORE BUG: a bound alarm keeps its own city's time even when the active city changed. */
    @Test
    fun boundAlarm_ignoresActiveCity() {
        val nyAlarm = sunriseAlarm(1, newYork)
        val recalced = SunAlarmResolver.recalibrate(nyAlarm, fallback = tokyo, date = date)
        val expected = SunAlarmResolver.targetTime("SUNRISE", newYork, date, 0)

        assertEquals("New York", recalced.locationName)
        assertEquals(expected.hour, recalced.hour)
        assertEquals(expected.minute, recalced.minute)
    }

    /** Two alarms for two cities stay independent when both are recalibrated. */
    @Test
    fun twoCityAlarms_doNotOverwriteEachOther() {
        val nyAlarm = sunriseAlarm(1, newYork)
        val tyoAlarm = sunriseAlarm(2, tokyo)

        val nyOut = SunAlarmResolver.recalibrate(nyAlarm, fallback = london, date = date)
        val tyoOut = SunAlarmResolver.recalibrate(tyoAlarm, fallback = london, date = date)

        val nyExpected = SunAlarmResolver.targetTime("SUNRISE", newYork, date, 0)
        val tyoExpected = SunAlarmResolver.targetTime("SUNRISE", tokyo, date, 0)

        assertEquals(nyExpected.hour, nyOut.hour)
        assertEquals(nyExpected.minute, nyOut.minute)
        assertEquals(tyoExpected.hour, tyoOut.hour)
        assertEquals(tyoExpected.minute, tyoOut.minute)
        assertFalse(nyOut.hour == tyoOut.hour && nyOut.minute == tyoOut.minute)
    }

    /** A legacy alarm (no recorded location) adopts the active city and becomes bound to it. */
    @Test
    fun legacyAlarm_adoptsActiveCity() {
        val legacy = Alarm(
            id = 5, title = "Old sunrise", alarmType = "SUNRISE",
            hour = 6, minute = 30, offsetMinutes = 0
        )
        assertFalse(legacy.hasLocation())

        val recalced = SunAlarmResolver.recalibrate(legacy, fallback = london, date = date)
        val expected = SunAlarmResolver.targetTime("SUNRISE", london, date, 0)

        assertTrue("should now be bound", recalced.hasLocation())
        assertEquals("London", recalced.locationName)
        assertEquals(expected.hour, recalced.hour)
        assertEquals(expected.minute, recalced.minute)
    }

    /** The before/after offset is applied on top of the city's sun time. */
    @Test
    fun offsetMinutes_shiftTheTime() {
        val base = SunAlarmResolver.targetTime("SUNRISE", tokyo, date, 0)
        val minus30 = SunAlarmResolver.targetTime("SUNRISE", tokyo, date, -30)
        val plus45 = SunAlarmResolver.targetTime("SUNRISE", tokyo, date, 45)

        assertEquals(base.minusMinutes(30), minus30)
        assertEquals(base.plusMinutes(45), plus45)
    }

    /** Sunset resolves against the alarm's own city too, independent of the active city. */
    @Test
    fun sunsetAlarm_isAlsoPerLocation() {
        val nySunset = Alarm(
            id = 9, title = "NY sunset", alarmType = "SUNSET",
            hour = 0, minute = 0,
            latitude = newYork.latitude, longitude = newYork.longitude,
            timezoneOffset = newYork.timezoneOffset, locationName = newYork.name
        )
        val recalced = SunAlarmResolver.recalibrate(nySunset, fallback = tokyo, date = date)
        val expected = SunAlarmResolver.targetTime("SUNSET", newYork, date, 0)

        assertEquals("New York", recalced.locationName)
        assertEquals(expected.hour, recalced.hour)
        assertEquals(expected.minute, recalced.minute)
    }

    /** CUSTOM alarms have a fixed clock time and must pass through recalibrate untouched. */
    @Test
    fun customAlarm_isUnchanged() {
        val custom = Alarm(id = 3, title = "Wake", alarmType = "CUSTOM", hour = 7, minute = 15)
        val out = SunAlarmResolver.recalibrate(custom, fallback = tokyo, date = date)

        assertSame("CUSTOM should be returned as-is", custom, out)
        assertEquals(7, out.hour)
        assertEquals(15, out.minute)
        assertFalse(out.hasLocation())
    }

    /** recalibrate with no explicit date still binds the location and computes a valid clock time. */
    @Test
    fun recalibrate_withDefaultDate_bindsLocation() {
        val recalced = SunAlarmResolver.recalibrate(sunriseAlarm(7, tokyo), fallback = london)
        assertEquals("Tokyo", recalced.locationName)
        assertEquals(tokyo.timezoneOffset, recalced.timezoneOffset, 0.0)
        assertTrue(recalced.hour in 0..23)
        assertTrue(recalced.minute in 0..59)
    }

    // ---------------------------------------------------------------------------------------------
    // Layer 1b: zoneOf — decimal offset -> fixed zone
    // ---------------------------------------------------------------------------------------------

    @Test
    fun zoneOf_mapsDecimalOffsetsCorrectly() {
        assertEquals(ZoneOffset.ofHours(-5), SunAlarmResolver.zoneOf(-5.0))
        assertEquals(ZoneOffset.ofHours(9), SunAlarmResolver.zoneOf(9.0))
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), SunAlarmResolver.zoneOf(5.5))   // India
        assertEquals(ZoneOffset.ofHoursMinutes(5, 45), SunAlarmResolver.zoneOf(5.75))  // Nepal
        assertEquals(ZoneOffset.ofHours(0), SunAlarmResolver.zoneOf(0.0))
    }

    // ---------------------------------------------------------------------------------------------
    // Layer 2: nextTriggerInstant — cross-timezone scheduling (the firing-time bug)
    // ---------------------------------------------------------------------------------------------

    private val utc: ZoneId = ZoneOffset.UTC

    /** CUSTOM, time later today: fires today at that wall time in the DEVICE zone. */
    @Test
    fun custom_laterToday_firesToday() {
        val now = Instant.parse("2026-06-15T08:00:00Z")
        val alarm = Alarm(id = 1, title = "t", alarmType = "CUSTOM", hour = 10, minute = 0)
        val instant = SunAlarmResolver.nextTriggerInstant(alarm, now, utc)
        assertEquals(Instant.parse("2026-06-15T10:00:00Z"), instant)
    }

    /** CUSTOM, time already passed today: rolls to tomorrow. */
    @Test
    fun custom_earlierToday_rollsToTomorrow() {
        val now = Instant.parse("2026-06-15T12:00:00Z")
        val alarm = Alarm(id = 1, title = "t", alarmType = "CUSTOM", hour = 10, minute = 0)
        val instant = SunAlarmResolver.nextTriggerInstant(alarm, now, utc)
        assertEquals(Instant.parse("2026-06-16T10:00:00Z"), instant)
    }

    /** Boundary: target time exactly equals now -> next day (strictly-future rule). */
    @Test
    fun custom_exactlyNow_rollsToNextDay() {
        val now = Instant.parse("2026-06-15T10:00:00Z")
        val alarm = Alarm(id = 1, title = "t", alarmType = "CUSTOM", hour = 10, minute = 0)
        val instant = SunAlarmResolver.nextTriggerInstant(alarm, now, utc)
        assertEquals(Instant.parse("2026-06-16T10:00:00Z"), instant)
    }

    /**
     * THE CROSS-TIMEZONE BUG. A Tokyo (+9) sunrise alarm at 04:25 must fire at 04:25 TOKYO time,
     * even though the phone is in New York. Before the fix it fired at 04:25 on the phone's clock.
     */
    @Test
    fun sunAlarm_firesInItsOwnTimezone_notTheDevices() {
        val now = Instant.parse("2026-06-15T14:00:00Z")
        val deviceNy = ZoneId.of("America/New_York")
        val alarm = boundAlarmAt(4, 25, tokyo)

        val instant = SunAlarmResolver.nextTriggerInstant(alarm, now, deviceNy)

        // 04:25 of 2026-06-15 in +9 is already past at now, so next is 2026-06-16 04:25 +09:00.
        val expected = ZonedDateTime.of(2026, 6, 16, 4, 25, 0, 0, ZoneOffset.ofHours(9)).toInstant()
        assertEquals(expected, instant)

        // And the wall clock at +9 really is 04:25 (not whatever the device zone would show).
        val atTokyo = instant.atZone(ZoneOffset.ofHours(9))
        assertEquals(LocalTime.of(4, 25), atTokyo.toLocalTime())
    }

    /** Two alarms with the SAME clock digits but different cities resolve to different instants. */
    @Test
    fun sameDigitsDifferentCities_giveDifferentInstants() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val nyAlarm = boundAlarmAt(5, 0, newYork)   // 05:00 in -5
        val tyoAlarm = boundAlarmAt(5, 0, tokyo)    // 05:00 in +9

        val nyInstant = SunAlarmResolver.nextTriggerInstant(nyAlarm, now, utc)
        val tyoInstant = SunAlarmResolver.nextTriggerInstant(tyoAlarm, now, utc)

        assertNotEquals(nyInstant, tyoInstant)
        assertEquals(LocalTime.of(5, 0), nyInstant.atZone(ZoneOffset.ofHours(-5)).toLocalTime())
        assertEquals(LocalTime.of(5, 0), tyoInstant.atZone(ZoneOffset.ofHours(9)).toLocalTime())
    }

    /** Half-hour offset zones (India +5:30) are handled exactly. */
    @Test
    fun halfHourOffsetZone_isHandled() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val alarm = boundAlarmAt(6, 10, mumbai)
        val instant = SunAlarmResolver.nextTriggerInstant(alarm, now, utc)

        val atMumbai = instant.atZone(ZoneOffset.ofHoursMinutes(5, 30))
        assertEquals(LocalTime.of(6, 10), atMumbai.toLocalTime())
        assertTrue(instant.isAfter(now))
    }

    /** A legacy sun alarm with no location falls back to the device zone (best effort). */
    @Test
    fun legacySunAlarm_withoutLocation_usesDeviceZone() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val device: ZoneId = ZoneOffset.ofHours(2)
        val alarm = Alarm(id = 1, title = "t", alarmType = "SUNRISE", hour = 6, minute = 0) // no location
        assertFalse(alarm.hasLocation())

        val instant = SunAlarmResolver.nextTriggerInstant(alarm, now, device)
        assertEquals(LocalTime.of(6, 0), instant.atZone(device).toLocalTime())
    }

    /** Repeating sun alarm picks the next matching weekday IN ITS OWN zone. */
    @Test
    fun repeatingSunAlarm_matchesWeekdayInAlarmZone() {
        val now = Instant.parse("2026-06-15T14:00:00Z")
        // Repeat only on Wednesday (3) and Saturday (6), Tokyo time.
        val alarm = boundAlarmAt(6, 0, tokyo, repeat = "3,6")

        val instant = SunAlarmResolver.nextTriggerInstant(alarm, now, ZoneId.of("America/New_York"))
        val fire = instant.atZone(ZoneOffset.ofHours(9))

        assertTrue(instant.isAfter(now))
        assertEquals(LocalTime.of(6, 0), fire.toLocalTime())
        assertTrue("must land on a selected weekday in Tokyo",
            fire.dayOfWeek == DayOfWeek.WEDNESDAY || fire.dayOfWeek == DayOfWeek.SATURDAY)
    }

    /**
     * Weekday is decided in the ALARM's zone, not the device's. Near the date line the two zones can
     * be on different calendar days; the selected day must follow the alarm's city.
     */
    @Test
    fun repeating_weekdayFollowsAlarmZone_notDevice() {
        // 2026-06-15T16:00Z is still Mon 15th in UTC, but already Tue 16th 01:00 in Tokyo (+9).
        val now = Instant.parse("2026-06-15T16:00:00Z")
        val tuesday = "2"
        val alarm = boundAlarmAt(6, 0, tokyo, repeat = tuesday)

        // Device zone is UTC (where it is still Monday) — must NOT push the alarm a week out.
        val instant = SunAlarmResolver.nextTriggerInstant(alarm, now, utc)
        val fire = instant.atZone(ZoneOffset.ofHours(9))

        assertEquals(DayOfWeek.TUESDAY, fire.dayOfWeek)
        // Tue 16th 06:00 Tokyo == 2026-06-15T21:00Z, the very next day — not 8 days later.
        assertEquals(ZonedDateTime.of(2026, 6, 16, 6, 0, 0, 0, ZoneOffset.ofHours(9)).toInstant(), instant)
    }

    /** Repeating, today selected but time already passed -> same weekday next week. */
    @Test
    fun repeating_todayElapsed_goesNextWeek() {
        // Use the device zone so we control "today". Pick a now, then require today's weekday only.
        val now = Instant.parse("2026-06-15T12:00:00Z")
        val todaysDow = now.atZone(utc).dayOfWeek.value // device-zone weekday number
        val alarm = Alarm(
            id = 1, title = "t", alarmType = "CUSTOM",
            hour = 9, minute = 0, repeatDays = todaysDow.toString() // 09:00 already passed at 12:00
        )

        val instant = SunAlarmResolver.nextTriggerInstant(alarm, now, utc)
        assertEquals(Instant.parse("2026-06-22T09:00:00Z"), instant) // exactly 7 days later
    }

    /**
     * INTEGRATION: the time recalibrate writes, fed back through nextTriggerInstant in the same zone,
     * reproduces the city's sun time exactly — proving the "compute" and "fire" sides never disagree
     * (no double-applied offset, no zone drift).
     */
    @Test
    fun recalibrateThenSchedule_areConsistent() {
        val target = SunAlarmResolver.targetTime("SUNRISE", tokyo, date, 0)
        val alarm = SunAlarmResolver.recalibrate(sunriseAlarm(1, tokyo), fallback = newYork, date = date)

        // "now" just after midnight in Tokyo on the same date -> today's fire is selected.
        val now = ZonedDateTime.of(2026, 6, 15, 0, 0, 0, 0, ZoneOffset.ofHours(9)).toInstant()
        val instant = SunAlarmResolver.nextTriggerInstant(alarm, now, ZoneId.of("America/New_York"))
        val fire = instant.atZone(ZoneOffset.ofHours(9))

        assertEquals(date, fire.toLocalDate())
        assertEquals(target.toLocalTime(), fire.toLocalTime())
    }
}
