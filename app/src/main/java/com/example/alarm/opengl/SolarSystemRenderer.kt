package com.example.alarm.opengl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.alarm.astro.SolarEphemeris
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.time.LocalDateTime
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

private fun alloc(floats: Int): FloatBuffer =
    ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

/**
 * Native OpenGL ES 2.0 renderer for a time-accurate heliocentric solar system.
 *
 * The Sun sits at the origin; each planet starts at its *real* current
 * heliocentric angle (from [SolarEphemeris]) along a screen-compressed orbit ring,
 * so the layout answers "which planet is where, right now".
 *
 * Two modes, selected by [animateOrbits]:
 *  - true  (default): each planet revolves continuously from its real starting angle,
 *    at a Kepler-correct *relative* speed (inner planets fast, outer slow) compressed
 *    so every orbit is visible to the eye. This is the "watch them orbit" experience.
 *  - false: positions only change as real time advances (refreshed by the hosting view) —
 *    scientifically accurate but imperceptible minute-to-minute.
 * In both modes a gentle axial spin and a pulsing corona keep the scene alive.
 */
class SolarSystemRenderer : GLSurfaceView.Renderer {

    // ----- camera state, written from the GL thread-safe view -----
    @Volatile var yaw = 0.6f          // radians, drag horizontally
    @Volatile var pitch = -0.55f      // radians, drag vertically (looking down a bit)
    @Volatile var distance = 17f      // zoom (pinch)

    /**
     * true  -> planets visibly orbit from their real starting positions (animated).
     * false -> planets stay at the exact real-time position (accurate, near-static).
     */
    @Volatile var animateOrbits = true

    /** Refreshed by the view when the clock advances; angles in radians. */
    @Volatile private var bodyAngles = FloatArray(PLANETS.size) { 0f }

    fun updatePositions(time: LocalDateTime) {
        val bodies = SolarEphemeris.compute(time)
        // bodies[0] is the Sun; planets follow in the same order as PLANETS.
        val a = FloatArray(PLANETS.size)
        for (i in PLANETS.indices) {
            a[i] = bodies[i + 1].angleRad.toFloat()
        }
        bodyAngles = a
    }

    // ----- matrices -----
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)
    private val normalMat = FloatArray(16)

    // ----- geometry -----
    private lateinit var sphere: Sphere
    private lateinit var orbitRing: FloatBuffer
    private var orbitVertexCount = 0
    private lateinit var stars: FloatBuffer
    private var starCount = 0
    private lateinit var saturnRing: FloatBuffer
    private var saturnRingCount = 0
    private val glowBuf: FloatBuffer = alloc(6 * 4) // 6 verts * (x,y,z,radial)

    // ----- programs -----
    private var planetProg = 0
    private var sunProg = 0
    private var orbitProg = 0
    private var starProg = 0
    private var glowProg = 0

    private var startNanos = 0L
    @Volatile private var initError = false

    // ----- picking: viewport + projected screen positions (read from UI thread) -----
    @Volatile var viewportW = 0
    @Volatile var viewportH = 0
    /** [Sun, Mercury..Neptune] each as (px, py); -1f means off-screen / behind camera. */
    @Volatile private var screenPos: FloatArray = FloatArray(9 * 2) { -1f }
    val bodyNames = listOf("Sun", "Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune")

    // scratch matrices/vectors for screen projection (GL thread only)
    private val projView = FloatArray(16)
    private val pickIn = FloatArray(4)
    private val pickOut = FloatArray(4)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            GLES20.glClearColor(0f, 0f, 0f, 0f) // transparent — Compose paints the sky behind
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)

            sphere = Sphere(28, 36)
            buildOrbitRing()
            buildStars()
            buildSaturnRing()

            planetProg = link(VS_PLANET, FS_PLANET)
            sunProg = link(VS_PLANET, FS_SUN)
            orbitProg = link(VS_SIMPLE, FS_FLAT)
            starProg = link(VS_STAR, FS_STAR)
            glowProg = link(VS_GLOW, FS_GLOW)

            startNanos = System.nanoTime()
            initError = false
        } catch (e: Exception) {
            android.util.Log.e("SolarSystemRenderer", "Surface creation failed gracefully: ", e)
            initError = true
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (initError) return
        try {
            GLES20.glViewport(0, 0, width, height)
            viewportW = width
            viewportH = height
            val aspect = width.toFloat() / height.coerceAtLeast(1)
            Matrix.perspectiveM(projection, 0, 42f, aspect, 0.5f, 200f)
        } catch (e: Exception) {
            android.util.Log.e("SolarSystemRenderer", "Surface change failed: ", e)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        if (initError) return
        try {
            val t = (System.nanoTime() - startNanos) / 1_000_000_000f

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            // Camera orbiting the origin.
            val cp = cos(pitch); val sp = sin(pitch)
            val cy = cos(yaw); val sy = sin(yaw)
            val ex = distance * cp * sy
            val ey = distance * -sp
            val ez = distance * cp * cy
            Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, 0f, 0f, 0f, 1f, 0f)

            // Camera basis (rows of the view rotation) for billboards.
            val rightX = view[0]; val rightY = view[4]; val rightZ = view[8]
            val upX = view[1]; val upY = view[5]; val upZ = view[9]

            // Combined projection*view for projecting body world positions to screen pixels.
            Matrix.multiplyMM(projView, 0, projection, 0, view, 0)
            // Local buffer filled this frame: index 0 = Sun, 1..8 = planets (PLANETS order).
            val screenPositions = FloatArray(9 * 2) { -1f }
            projectToScreen(0f, 0f, 0f)?.let { screenPositions[0] = it[0]; screenPositions[1] = it[1] }

            // --- starfield (far, depth write off) ---
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
            drawStars(t)

            // --- orbit rings ---
            val angles = bodyAngles
            for (i in PLANETS.indices) {
                drawOrbit(PLANETS[i].orbit)
            }
            GLES20.glDepthMask(true)

            // --- sun corona glow (layered additive billboards) ---
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
            GLES20.glDepthMask(false)
            // Wide, slow-breathing outer halo + a tighter brighter inner flare give the Sun
            // a living, radiant atmosphere instead of a flat disc.
            val pulseOuter = 1f + 0.10f * sin(t * 0.9f + 1.3f)
            val pulseInner = 1f + 0.06f * sin(t * 1.7f)
            drawGlow(0f, 0f, 0f, SUN_RADIUS * 6.4f * pulseOuter, rightX, rightY, rightZ, upX, upY, upZ)
            drawGlow(0f, 0f, 0f, SUN_RADIUS * 3.4f * pulseInner, rightX, rightY, rightZ, upX, upY, upZ)
            GLES20.glDepthMask(true)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            // --- the Sun ---
            Matrix.setIdentityM(model, 0)
            Matrix.scaleM(model, 0, SUN_RADIUS, SUN_RADIUS, SUN_RADIUS)
            drawSphere(sunProg, floatArrayOf(1.0f, 0.78f, 0.30f), t, emissive = true)

            // --- planets ---
            for (i in PLANETS.indices) {
                val p = PLANETS[i]
                // Start at the real heliocentric angle; in animated mode revolve onward from
                // there at a Kepler-correct (compressed) speed so the orbit is visible.
                val ang = if (animateOrbits) angles[i] + t * orbitOmega(p) else angles[i]
                val x = p.orbit * cos(ang)
                val z = p.orbit * sin(ang)

                // Project this planet's world centre to screen pixels for tap picking.
                projectToScreen(x, 0f, z)?.let { screenPositions[(i + 1) * 2] = it[0]; screenPositions[(i + 1) * 2 + 1] = it[1] }

                Matrix.setIdentityM(model, 0)
                Matrix.translateM(model, 0, x, 0f, z)
                // slow axial spin for life (not orbital motion)
                Matrix.rotateM(model, 0, (t * p.spin) % 360f, 0f, 1f, 0f)
                Matrix.rotateM(model, 0, p.tilt, 0f, 0f, 1f)
                Matrix.scaleM(model, 0, p.size, p.size, p.size)
                drawSphere(planetProg, p.color, t, emissive = false)

                if (p.name == "Earth") {
                    // Outer space-blue orbit line for the Moon around Earth
                    drawOrbitAt(x, 0f, z, 0.44f, floatArrayOf(0.4f, 0.55f, 0.8f, 0.25f))

                    // Moon orbits Earth at an offset with a cool 3D plane inclination/tilt animation
                    val mSpeed = t * 3.2f
                    val mx = x + 0.44f * cos(mSpeed.toDouble()).toFloat()
                    // Use sine waves to incline the Moon's 3D orbit around Earth for gorgeous depth
                    val my = 0.08f * sin(mSpeed.toDouble()).toFloat()
                    val mz = z + 0.44f * sin(mSpeed.toDouble()).toFloat()

                    Matrix.setIdentityM(model, 0)
                    Matrix.translateM(model, 0, mx, my, mz)
                    Matrix.scaleM(model, 0, 0.045f, 0.045f, 0.045f) // Moon is smaller
                    drawSphere(planetProg, floatArrayOf(0.72f, 0.72f, 0.74f), t, emissive = false)
                }

                if (p.name == "Saturn") {
                    drawSaturnRing(x, z, t)
                }
            }

            // Publish this frame's projected screen positions for UI-thread tap picking.
            screenPos = screenPositions
        } catch (e: Exception) {
            android.util.Log.e("SolarSystemRenderer", "Frame composition failed gracefully: ", e)
        }
    }

    /**
     * Project a world-space point to screen pixels using projection*view.
     * Returns [px, py] (a fresh 2-element array) or null if the point is behind the
     * camera (clip w <= 0). GL thread only (reuses [pickIn]/[pickOut]).
     */
    private fun projectToScreen(wx: Float, wy: Float, wz: Float): FloatArray? {
        pickIn[0] = wx; pickIn[1] = wy; pickIn[2] = wz; pickIn[3] = 1f
        Matrix.multiplyMV(pickOut, 0, projView, 0, pickIn, 0)
        val w = pickOut[3]
        if (w <= 0f) return null
        val ndcX = pickOut[0] / w
        val ndcY = pickOut[1] / w
        val px = (ndcX * 0.5f + 0.5f) * viewportW
        val py = (1f - (ndcY * 0.5f + 0.5f)) * viewportH
        return floatArrayOf(px, py)
    }

    /**
     * Hit-test a screen tap against the most recently projected body positions.
     * Callable from the UI thread (reads volatile [screenPos]/[viewportW]/[viewportH]).
     * Returns the nearest body name within the touch threshold, or null.
     */
    fun pickBody(px: Float, py: Float): String? {
        val sp = screenPos
        val w = viewportW
        val h = viewportH
        if (w <= 0 || h <= 0) return null
        val threshold = (kotlin.math.max(w, h) * 0.07f).coerceAtLeast(60f)
        var bestIdx = -1
        var bestDist = threshold
        for (i in bodyNames.indices) {
            val sx = sp[i * 2]
            val sy = sp[i * 2 + 1]
            if (sx == -1f && sy == -1f) continue // off-screen / behind camera sentinel
            val d = hypot(px - sx, py - sy)
            if (d <= bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return if (bestIdx >= 0) bodyNames[bestIdx] else null
    }

    /**
     * Angular speed (rad/s of wall-clock) for the animated orbit of [p].
     *
     * Real orbital periods span Mercury (88 d) to Neptune (60 000 d) — a 684× range,
     * far too wide to watch. We keep Earth at one revolution per [EARTH_REVOLUTION_SECONDS]
     * and scale the rest by (Earth period / planet period) raised to [SPEED_COMPRESS],
     * which preserves the true ordering (inner faster than outer) while squeezing the
     * range so even Neptune visibly drifts.
     */
    private fun orbitOmega(p: Planet): Float {
        val baseOmega = (2.0 * Math.PI / EARTH_REVOLUTION_SECONDS).toFloat()
        val ratio = (EARTH_PERIOD_DAYS / p.periodDays).pow(SPEED_COMPRESS)
        return baseOmega * ratio
    }

    // ---------------------------------------------------------------- drawing

    private fun drawSphere(prog: Int, color: FloatArray, t: Float, emissive: Boolean) {
        GLES20.glUseProgram(prog)

        Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, tmp, 0)
        // Normal matrix = model (uniform scale, rotation only) — fine after normalize.
        System.arraycopy(model, 0, normalMat, 0, 16)

        GLES20.glUniformMatrix4fv(uni(prog, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uni(prog, "uModel"), 1, false, model, 0)
        GLES20.glUniformMatrix4fv(uni(prog, "uNormal"), 1, false, normalMat, 0)
        GLES20.glUniform3fv(uni(prog, "uColor"), 1, color, 0)
        val tu = uni(prog, "uTime"); if (tu >= 0) GLES20.glUniform1f(tu, t)

        val pos = attr(prog, "aPos")
        val nrm = attr(prog, "aNormal")
        GLES20.glEnableVertexAttribArray(pos)
        sphere.vertices.position(0)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 24, sphere.vertices)
        if (nrm >= 0) {
            GLES20.glEnableVertexAttribArray(nrm)
            sphere.vertices.position(3)
            GLES20.glVertexAttribPointer(nrm, 3, GLES20.GL_FLOAT, false, 24, sphere.vertices)
        }
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphere.indexCount, GLES20.GL_UNSIGNED_SHORT, sphere.indices)
        GLES20.glDisableVertexAttribArray(pos)
        if (nrm >= 0) GLES20.glDisableVertexAttribArray(nrm)
    }

    private fun drawOrbitAt(cx: Float, cy: Float, cz: Float, radius: Float, rColor: FloatArray) {
        GLES20.glUseProgram(orbitProg)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, cx, cy, cz)
        Matrix.scaleM(model, 0, radius, radius, radius)
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, tmp, 0)
        GLES20.glUniformMatrix4fv(uni(orbitProg, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniform4f(uni(orbitProg, "uColor"), rColor[0], rColor[1], rColor[2], rColor[3])
        val pos = attr(orbitProg, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        orbitRing.position(0)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 12, orbitRing)
        GLES20.glLineWidth(1.5f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, orbitVertexCount)
        GLES20.glDisableVertexAttribArray(pos)
    }

    private fun drawOrbit(radius: Float) {
        drawOrbitAt(0f, 0f, 0f, radius, floatArrayOf(0.45f, 0.5f, 0.7f, 0.22f))
    }

    private fun drawStars(t: Float) {
        GLES20.glUseProgram(starProg)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
        GLES20.glUniformMatrix4fv(uni(starProg, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniform1f(uni(starProg, "uTime"), t)
        val pos = attr(starProg, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        stars.position(0)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 16, stars)
        val seed = attr(starProg, "aSeed")
        GLES20.glEnableVertexAttribArray(seed)
        stars.position(3)
        GLES20.glVertexAttribPointer(seed, 1, GLES20.GL_FLOAT, false, 16, stars)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starCount)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(seed)
    }

    private fun drawGlow(
        cx: Float, cy: Float, cz: Float, size: Float,
        rx: Float, ry: Float, rz: Float, ux: Float, uy: Float, uz: Float
    ) {
        // Build a camera-facing quad (two triangles) with a radial coordinate.
        val v = FloatArray(24)
        val corners = arrayOf(
            floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f),
            floatArrayOf(-1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f)
        )
        var o = 0
        for (c in corners) {
            v[o++] = cx + (rx * c[0] + ux * c[1]) * size
            v[o++] = cy + (ry * c[0] + uy * c[1]) * size
            v[o++] = cz + (rz * c[0] + uz * c[1]) * size
            v[o++] = 0f // radial filled below via separate attribute layout
        }
        glowBuf.position(0); glowBuf.put(v); glowBuf.position(0)

        GLES20.glUseProgram(glowProg)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
        GLES20.glUniformMatrix4fv(uni(glowProg, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniform3f(uni(glowProg, "uCenter"), cx, cy, cz)
        GLES20.glUniform1f(uni(glowProg, "uSize"), size)
        GLES20.glUniform3f(uni(glowProg, "uColor"), 1.0f, 0.62f, 0.22f)
        val pos = attr(glowProg, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        glowBuf.position(0)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 16, glowBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(pos)
    }

    private fun drawSaturnRing(x: Float, z: Float, t: Float) {
        GLES20.glUseProgram(orbitProg)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, 0f, z)
        Matrix.rotateM(model, 0, 26f, 1f, 0f, 0.3f)
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, tmp, 0)
        GLES20.glUniformMatrix4fv(uni(orbitProg, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniform4f(uni(orbitProg, "uColor"), 0.86f, 0.76f, 0.55f, 0.55f)
        val pos = attr(orbitProg, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        saturnRing.position(0)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 12, saturnRing)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, saturnRingCount)
        GLES20.glDisableVertexAttribArray(pos)
    }

    // ---------------------------------------------------------------- geometry builders

    private fun buildOrbitRing() {
        val n = 128
        orbitVertexCount = n
        val b = alloc(n * 3)
        for (i in 0 until n) {
            val a = (i.toFloat() / n) * (2.0 * Math.PI).toFloat()
            b.put(cos(a)); b.put(0f); b.put(sin(a))
        }
        b.position(0)
        orbitRing = b
    }

    private fun buildStars() {
        val n = 320
        starCount = n
        val b = alloc(n * 4)
        var s = 12345L
        fun rnd(): Float { s = s * 6364136223846793005L + 1442695040888963407L; return ((s ushr 33).toInt() % 10000) / 10000f }
        for (i in 0 until n) {
            // random point on a large sphere shell
            val u = rnd() * 2f - 1f
            val theta = rnd() * (2.0 * Math.PI).toFloat()
            val r = 60f
            val sq = kotlin.math.sqrt(1f - u * u)
            b.put(r * sq * cos(theta)); b.put(r * u); b.put(r * sq * sin(theta))
            b.put(rnd()) // twinkle seed
        }
        b.position(0)
        stars = b
    }

    private fun buildSaturnRing() {
        // annulus triangle strip in the XZ plane around origin (translated at draw time)
        val seg = 64
        saturnRingCount = (seg + 1) * 2
        val inner = 0.55f; val outer = 0.95f
        val b = alloc(saturnRingCount * 3)
        for (i in 0..seg) {
            val a = (i.toFloat() / seg) * (2.0 * Math.PI).toFloat()
            val cx = cos(a); val sz = sin(a)
            b.put(inner * cx); b.put(0f); b.put(inner * sz)
            b.put(outer * cx); b.put(0f); b.put(outer * sz)
        }
        b.position(0)
        saturnRing = b
    }

    // ---------------------------------------------------------------- gl helpers

    private fun uni(prog: Int, name: String) = GLES20.glGetUniformLocation(prog, name)
    private fun attr(prog: Int, name: String) = GLES20.glGetAttribLocation(prog, name)

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader compile failed: $log")
        }
        return s
    }

    // ---------------------------------------------------------------- sphere mesh

    private class Sphere(stacks: Int, slices: Int) {
        val vertices: FloatBuffer
        val indices: ShortBuffer
        val indexCount: Int

        init {
            val verts = ArrayList<Float>()
            for (i in 0..stacks) {
                val phi = Math.PI * i / stacks
                val sinPhi = sin(phi); val cosPhi = cos(phi)
                for (j in 0..slices) {
                    val theta = 2.0 * Math.PI * j / slices
                    val x = (sinPhi * cos(theta)).toFloat()
                    val y = cosPhi.toFloat()
                    val z = (sinPhi * sin(theta)).toFloat()
                    verts.add(x); verts.add(y); verts.add(z) // position (unit -> normal too)
                    verts.add(x); verts.add(y); verts.add(z) // normal
                }
            }
            val idx = ArrayList<Short>()
            val cols = slices + 1
            for (i in 0 until stacks) {
                for (j in 0 until slices) {
                    val a = (i * cols + j).toShort()
                    val b = ((i + 1) * cols + j).toShort()
                    val c = (i * cols + j + 1).toShort()
                    val d = ((i + 1) * cols + j + 1).toShort()
                    idx.add(a); idx.add(b); idx.add(c)
                    idx.add(c); idx.add(b); idx.add(d)
                }
            }
            indexCount = idx.size
            vertices = alloc(verts.size).apply { for (f in verts) put(f); position(0) }
            indices = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder())
                .asShortBuffer().apply { for (sVal in idx) put(sVal); position(0) }
        }
    }

    data class Planet(
        val name: String, val orbit: Float, val size: Float,
        val color: FloatArray, val spin: Float, val tilt: Float,
        /** Real sidereal orbital period in days — drives the animated revolution speed. */
        val periodDays: Float
    )

    companion object {
        private const val SUN_RADIUS = 0.95f

        // ----- animated-orbit tuning -----
        private const val EARTH_REVOLUTION_SECONDS = 60f  // Earth = 1 orbit per 60 s
        private const val EARTH_PERIOD_DAYS = 365.256f
        private const val SPEED_COMPRESS = 0.62f          // 0 = all equal, 1 = true Kepler

        // Order MUST match SolarEphemeris.compute() planet order (after the Sun).
        val PLANETS = listOf(
            Planet("Mercury", 1.7f, 0.10f, floatArrayOf(0.62f, 0.60f, 0.58f), 24f, 0.1f, 87.969f),
            Planet("Venus", 2.4f, 0.16f, floatArrayOf(0.90f, 0.72f, 0.40f), 16f, 2.6f, 224.701f),
            Planet("Earth", 3.2f, 0.17f, floatArrayOf(0.27f, 0.52f, 0.90f), 40f, 23.4f, 365.256f),
            Planet("Mars", 4.1f, 0.13f, floatArrayOf(0.82f, 0.40f, 0.27f), 38f, 25f, 686.980f),
            Planet("Jupiter", 5.3f, 0.42f, floatArrayOf(0.80f, 0.66f, 0.50f), 70f, 3f, 4332.59f),
            Planet("Saturn", 6.4f, 0.36f, floatArrayOf(0.86f, 0.76f, 0.55f), 65f, 26f, 10759.22f),
            Planet("Uranus", 7.3f, 0.26f, floatArrayOf(0.55f, 0.80f, 0.84f), 50f, 82f, 30688.5f),
            Planet("Neptune", 8.1f, 0.25f, floatArrayOf(0.30f, 0.45f, 0.85f), 48f, 28f, 60182.0f)
        )

        // ---- shaders (GLSL ES 1.00) ----

        private const val VS_PLANET = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            uniform mat4 uNormal;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            varying vec3 vWorld;
            varying vec3 vNormal;
            void main() {
                vWorld = (uModel * vec4(aPos, 1.0)).xyz;
                vNormal = normalize((uNormal * vec4(aNormal, 0.0)).xyz);
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private const val FS_PLANET = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uTime;
            varying vec3 vWorld;
            varying vec3 vNormal;

            // cheap hash + 3D value noise for procedural surface detail (no textures/assets)
            float hash(vec3 p) {
                return fract(sin(dot(p, vec3(17.1, 113.5, 71.7))) * 43758.5453);
            }
            float vnoise(vec3 p) {
                vec3 i = floor(p);
                vec3 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float n000 = hash(i + vec3(0.0, 0.0, 0.0));
                float n100 = hash(i + vec3(1.0, 0.0, 0.0));
                float n010 = hash(i + vec3(0.0, 1.0, 0.0));
                float n110 = hash(i + vec3(1.0, 1.0, 0.0));
                float n001 = hash(i + vec3(0.0, 0.0, 1.0));
                float n101 = hash(i + vec3(1.0, 0.0, 1.0));
                float n011 = hash(i + vec3(0.0, 1.0, 1.0));
                float n111 = hash(i + vec3(1.0, 1.0, 1.0));
                float nx00 = mix(n000, n100, f.x);
                float nx10 = mix(n010, n110, f.x);
                float nx01 = mix(n001, n101, f.x);
                float nx11 = mix(n011, n111, f.x);
                return mix(mix(nx00, nx10, f.y), mix(nx01, nx11, f.y), f.z);
            }

            void main() {
                vec3 N = normalize(vNormal);
                vec3 L = normalize(-vWorld);            // Sun is at the origin
                float diff = max(dot(N, L), 0.0);
                float ambient = 0.12;

                // Procedural surface: latitudinal banding (gas giants) + mottling (terrestrials),
                // with a very slow time drift so the surface feels alive.
                float bands = 0.5 + 0.5 * sin(N.y * 9.0);
                float n = vnoise(N * 5.0 + vec3(uTime * 0.02));
                n += 0.5 * vnoise(N * 12.0);
                n /= 1.5;
                float detail = mix(0.80, 1.20, mix(n, bands, 0.3));

                vec3 col = uColor * detail * (ambient + diff * 0.95);

                // Soft specular glint near the sub-solar point.
                col += vec3(pow(diff, 16.0) * 0.18);

                // Atmospheric limb glow along the terminator edge.
                float rim = pow(1.0 - diff, 3.0) * 0.28;
                col += uColor * rim;

                gl_FragColor = vec4(col, 1.0);
            }
        """

        private const val FS_SUN = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uTime;
            varying vec3 vWorld;
            varying vec3 vNormal;

            // cheap hash + 3D value noise (GLSL ES 1.00 has no builtin noise)
            float hash(vec3 p) {
                return fract(sin(dot(p, vec3(17.1, 113.5, 71.7))) * 43758.5453);
            }
            float vnoise(vec3 p) {
                vec3 i = floor(p);
                vec3 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float n000 = hash(i + vec3(0.0, 0.0, 0.0));
                float n100 = hash(i + vec3(1.0, 0.0, 0.0));
                float n010 = hash(i + vec3(0.0, 1.0, 0.0));
                float n110 = hash(i + vec3(1.0, 1.0, 0.0));
                float n001 = hash(i + vec3(0.0, 0.0, 1.0));
                float n101 = hash(i + vec3(1.0, 0.0, 1.0));
                float n011 = hash(i + vec3(0.0, 1.0, 1.0));
                float n111 = hash(i + vec3(1.0, 1.0, 1.0));
                float nx00 = mix(n000, n100, f.x);
                float nx10 = mix(n010, n110, f.x);
                float nx01 = mix(n001, n101, f.x);
                float nx11 = mix(n011, n111, f.x);
                return mix(mix(nx00, nx10, f.y), mix(nx01, nx11, f.y), f.z);
            }

            void main() {
                vec3 N = normalize(vNormal);
                vec3 V = normalize(-vWorld);
                float facing = max(dot(N, V), 0.0);

                // Two octaves of drifting noise => boiling granulation across the surface.
                float t = uTime * 0.22;
                vec3 sp = N * 4.0;
                float n = vnoise(sp + vec3(t, t * 0.6, -t));
                n += 0.5 * vnoise(sp * 2.4 + vec3(-t * 1.3, t, t * 0.7));
                n /= 1.5;

                // bright plages vs darker convection lanes
                float gran = smoothstep(0.30, 0.85, n);
                float flicker = 0.90 + 0.10 * sin(uTime * 3.0 + n * 12.0);

                vec3 hot = vec3(1.0, 0.95, 0.78);
                vec3 col = mix(uColor * 0.7, hot, gran);   // mottled photosphere
                col = mix(col, hot, facing * 0.45);        // hotter toward the viewer

                // glowing chromosphere / limb at the silhouette edge
                float limb = pow(1.0 - facing, 2.5);
                col += vec3(1.0, 0.45, 0.12) * limb * 0.9;

                gl_FragColor = vec4(col * flicker * 1.2, 1.0);
            }
        """

        private const val VS_SIMPLE = """
            uniform mat4 uMvp;
            attribute vec3 aPos;
            void main() { gl_Position = uMvp * vec4(aPos, 1.0); }
        """

        private const val FS_FLAT = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """

        private const val VS_STAR = """
            uniform mat4 uMvp;
            uniform float uTime;
            attribute vec3 aPos;
            attribute float aSeed;
            varying float vTw;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                gl_PointSize = 2.0 + 1.5 * aSeed;
                vTw = 0.5 + 0.5 * sin(uTime * (1.0 + aSeed * 2.0) + aSeed * 30.0);
            }
        """

        private const val FS_STAR = """
            precision mediump float;
            varying float vTw;
            void main() {
                vec2 d = gl_PointCoord - vec2(0.5);
                float a = smoothstep(0.5, 0.0, length(d)) * (0.4 + 0.6 * vTw);
                gl_FragColor = vec4(vec3(0.9, 0.93, 1.0), a);
            }
        """

        private const val VS_GLOW = """
            uniform mat4 uMvp;
            uniform vec3 uCenter;
            uniform float uSize;
            attribute vec3 aPos;
            varying float vR;
            void main() {
                vR = length(aPos - uCenter) / uSize;   // 0 at centre -> ~1.4 at corners
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private const val FS_GLOW = """
            precision mediump float;
            uniform vec3 uColor;
            varying float vR;
            void main() {
                float a = pow(max(1.0 - vR, 0.0), 2.2);
                gl_FragColor = vec4(uColor * a, a);
            }
        """
    }
}
