package com.example.alarm.astro

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low–precision heliocentric ephemeris based on Paul Schlyter's
 * "How to compute planetary positions" (keplerian elements linear in time).
 *
 * The goal here is not sub-arc-second accuracy but a *truthful relative layout*:
 * the angle of every planet around the Sun for the current instant is correct to
 * within a fraction of a degree, which is what lets the user read "which planet is
 * where, right now". Distances are returned in real AU but the renderer compresses
 * them to fit the screen — the angle is what carries the meaning.
 */
object SolarEphemeris {

    data class Body(
        val name: String,
        /** Heliocentric ecliptic longitude angle, radians, measured in the orbital plane. */
        val angleRad: Double,
        /** True heliocentric distance, astronomical units. */
        val radiusAu: Double
    )

    /** Orbital elements as linear functions of d (days since 1999-12-31 00:00 UT). */
    private data class Elements(
        val nN: Double, val dN: Double,   // longitude of ascending node
        val iN: Double, val dI: Double,   // inclination
        val wN: Double, val dW: Double,   // argument of perihelion
        val aN: Double, val dA: Double,   // semi-major axis (AU)
        val eN: Double, val dE: Double,   // eccentricity
        val mN: Double, val dM: Double    // mean anomaly
    )

    // name -> elements. Earth is derived from the Sun separately.
    private val planets = linkedMapOf(
        "Mercury" to Elements(48.3313, 3.24587e-5, 7.0047, 5.00e-8, 29.1241, 1.01444e-5,
            0.387098, 0.0, 0.205635, 5.59e-10, 168.6562, 4.0923344368),
        "Venus" to Elements(76.6799, 2.46590e-5, 3.3946, 2.75e-8, 54.8910, 1.38374e-5,
            0.723330, 0.0, 0.006773, -1.302e-9, 48.0052, 1.6021302244),
        "Mars" to Elements(49.5574, 2.11081e-5, 1.8497, -1.78e-8, 286.5016, 2.92961e-5,
            1.523688, 0.0, 0.093405, 2.516e-9, 18.6021, 0.5240207766),
        "Jupiter" to Elements(100.4542, 2.76854e-5, 1.3030, -1.557e-7, 273.8777, 1.64505e-5,
            5.20256, 0.0, 0.048498, 4.469e-9, 19.8950, 0.0830853001),
        "Saturn" to Elements(113.6634, 2.38980e-5, 2.4886, -1.081e-7, 339.3939, 2.97661e-5,
            9.55475, 0.0, 0.055546, -9.499e-9, 316.9670, 0.0334442282),
        "Uranus" to Elements(74.0005, 1.3978e-5, 0.7733, 1.9e-8, 96.6612, 3.0565e-5,
            19.18171, -1.55e-8, 0.047318, 7.45e-9, 142.5905, 0.011725806),
        "Neptune" to Elements(131.7806, 3.0173e-5, 1.7700, -2.55e-7, 272.8461, -6.027e-6,
            30.05826, 3.313e-8, 0.008606, 2.15e-9, 260.2471, 0.005995147)
    )

    /**
     * Returns Sun + all planets for the given instant, ordered inner-to-outer.
     * The Sun is index 0 with radius 0.
     */
    fun compute(time: LocalDateTime): List<Body> {
        val d = daysSinceEpoch(time)
        val result = ArrayList<Body>(9)
        result.add(Body("Sun", 0.0, 0.0))

        // Earth, derived from the Sun's apparent geocentric position.
        val earth = earthBody(d)

        // Mercury, Venus  (then Earth) then the outer planets in order.
        result.add(bodyFor("Mercury", planets["Mercury"]!!, d))
        result.add(bodyFor("Venus", planets["Venus"]!!, d))
        result.add(earth)
        result.add(bodyFor("Mars", planets["Mars"]!!, d))
        result.add(bodyFor("Jupiter", planets["Jupiter"]!!, d))
        result.add(bodyFor("Saturn", planets["Saturn"]!!, d))
        result.add(bodyFor("Uranus", planets["Uranus"]!!, d))
        result.add(bodyFor("Neptune", planets["Neptune"]!!, d))
        return result
    }

    private fun bodyFor(name: String, e: Elements, d: Double): Body {
        val n = rev(e.nN + e.dN * d)
        val i = e.iN + e.dI * d
        val w = e.wN + e.dW * d
        val a = e.aN + e.dA * d
        val ecc = e.eN + e.dE * d
        val m = rev(e.mN + e.dM * d)

        // Eccentric anomaly (degrees), one Newton iteration is plenty at this scale.
        var ea = m + ecc * RAD2DEG * sinD(m) * (1.0 + ecc * cosD(m))
        ea -= (ea - ecc * RAD2DEG * sinD(ea) - m) / (1.0 - ecc * cosD(ea))

        val xv = a * (cosD(ea) - ecc)
        val yv = a * (sqrt(1.0 - ecc * ecc) * sinD(ea))

        val v = atan2(yv, xv)                 // true anomaly (radians)
        val r = sqrt(xv * xv + yv * yv)       // distance (AU)

        val nR = Math.toRadians(n)
        val iR = Math.toRadians(i)
        val vw = v + Math.toRadians(w)

        // Heliocentric ecliptic rectangular coordinates.
        val xh = r * (cos(nR) * cos(vw) - sin(nR) * sin(vw) * cos(iR))
        val yh = r * (sin(nR) * cos(vw) + cos(nR) * sin(vw) * cos(iR))

        return Body(name, atan2(yh, xh), r)
    }

    private fun earthBody(d: Double): Body {
        // Sun's geocentric ecliptic position; Earth heliocentric is the opposite vector.
        val w = 282.9404 + 4.70935e-5 * d
        val ecc = 0.016709 - 1.151e-9 * d
        val m = rev(356.0470 + 0.9856002585 * d)

        var ea = m + ecc * RAD2DEG * sinD(m) * (1.0 + ecc * cosD(m))
        ea -= (ea - ecc * RAD2DEG * sinD(ea) - m) / (1.0 - ecc * cosD(ea))

        val xv = cosD(ea) - ecc
        val yv = sqrt(1.0 - ecc * ecc) * sinD(ea)
        val v = atan2(yv, xv)
        val r = sqrt(xv * xv + yv * yv)
        val lonSun = v + Math.toRadians(w)

        val xs = r * cos(lonSun)
        val ys = r * sin(lonSun)
        // Earth sits opposite the Sun as seen from Earth -> negate.
        return Body("Earth", atan2(-ys, -xs), r)
    }

    private fun daysSinceEpoch(time: LocalDateTime): Double {
        // Epoch is 1999-12-31 00:00 UT (Schlyter's day number 0).
        val epoch = LocalDateTime.of(1999, 12, 31, 0, 0)
        val seconds = time.toEpochSecond(ZoneOffset.UTC) - epoch.toEpochSecond(ZoneOffset.UTC)
        return seconds / 86400.0
    }

    private const val RAD2DEG = 180.0 / Math.PI
    private fun rev(x: Double): Double = (x % 360.0 + 360.0) % 360.0
    private fun sinD(deg: Double) = sin(Math.toRadians(deg))
    private fun cosD(deg: Double) = cos(Math.toRadians(deg))
}
