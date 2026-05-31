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
import kotlin.math.sin

private fun alloc(floats: Int): FloatBuffer =
    ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

/**
 * Native OpenGL ES 2.0 renderer for a time-accurate heliocentric solar system.
 *
 * The Sun sits at the origin; each planet is placed at its *real* current
 * heliocentric angle (from [SolarEphemeris]) along a screen-compressed orbit ring,
 * so the layout answers "which planet is where, right now". Nothing revolves on its
 * own — positions only change as real time advances (refreshed by the hosting view).
 * A gentle axial spin and a pulsing corona keep the scene alive without faking motion.
 */
class SolarSystemRenderer : GLSurfaceView.Renderer {

    // ----- camera state, written from the GL thread-safe view -----
    @Volatile var yaw = 0.6f          // radians, drag horizontally
    @Volatile var pitch = -0.55f      // radians, drag vertically (looking down a bit)
    @Volatile var distance = 17f      // zoom (pinch)

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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
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
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(projection, 0, 42f, aspect, 0.5f, 200f)
    }

    override fun onDrawFrame(gl: GL10?) {
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

        // --- sun corona glow (additive billboard) ---
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        val pulse = 1f + 0.06f * sin(t * 1.4f)
        drawGlow(0f, 0f, 0f, SUN_RADIUS * 4.2f * pulse, rightX, rightY, rightZ, upX, upY, upZ)
        GLES20.glDepthMask(true)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // --- the Sun ---
        Matrix.setIdentityM(model, 0)
        Matrix.scaleM(model, 0, SUN_RADIUS, SUN_RADIUS, SUN_RADIUS)
        drawSphere(sunProg, floatArrayOf(1.0f, 0.78f, 0.30f), t, emissive = true)

        // --- planets ---
        for (i in PLANETS.indices) {
            val p = PLANETS[i]
            val ang = angles[i]
            val x = p.orbit * cos(ang)
            val z = p.orbit * sin(ang)

            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, x, 0f, z)
            // slow axial spin for life (not orbital motion)
            Matrix.rotateM(model, 0, (t * p.spin) % 360f, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, p.tilt, 0f, 0f, 1f)
            Matrix.scaleM(model, 0, p.size, p.size, p.size)
            drawSphere(planetProg, p.color, t, emissive = false)

            if (p.name == "Saturn") {
                drawSaturnRing(x, z, t)
            }
        }
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

    private fun drawOrbit(radius: Float) {
        GLES20.glUseProgram(orbitProg)
        Matrix.setIdentityM(model, 0)
        Matrix.scaleM(model, 0, radius, radius, radius)
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, tmp, 0)
        GLES20.glUniformMatrix4fv(uni(orbitProg, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniform4f(uni(orbitProg, "uColor"), 0.45f, 0.5f, 0.7f, 0.22f)
        val pos = attr(orbitProg, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        orbitRing.position(0)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 12, orbitRing)
        GLES20.glLineWidth(1.5f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, orbitVertexCount)
        GLES20.glDisableVertexAttribArray(pos)
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
        val color: FloatArray, val spin: Float, val tilt: Float
    )

    companion object {
        private const val SUN_RADIUS = 0.95f

        // Order MUST match SolarEphemeris.compute() planet order (after the Sun).
        val PLANETS = listOf(
            Planet("Mercury", 1.7f, 0.10f, floatArrayOf(0.62f, 0.60f, 0.58f), 24f, 0.1f),
            Planet("Venus", 2.4f, 0.16f, floatArrayOf(0.90f, 0.72f, 0.40f), 16f, 2.6f),
            Planet("Earth", 3.2f, 0.17f, floatArrayOf(0.27f, 0.52f, 0.90f), 40f, 23.4f),
            Planet("Mars", 4.1f, 0.13f, floatArrayOf(0.82f, 0.40f, 0.27f), 38f, 25f),
            Planet("Jupiter", 5.3f, 0.42f, floatArrayOf(0.80f, 0.66f, 0.50f), 70f, 3f),
            Planet("Saturn", 6.4f, 0.36f, floatArrayOf(0.86f, 0.76f, 0.55f), 65f, 26f),
            Planet("Uranus", 7.3f, 0.26f, floatArrayOf(0.55f, 0.80f, 0.84f), 50f, 82f),
            Planet("Neptune", 8.1f, 0.25f, floatArrayOf(0.30f, 0.45f, 0.85f), 48f, 28f)
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
            varying vec3 vWorld;
            varying vec3 vNormal;
            void main() {
                vec3 L = normalize(-vWorld);            // Sun is at the origin
                float diff = max(dot(vNormal, L), 0.0);
                float ambient = 0.14;
                float rim = pow(1.0 - max(dot(vNormal, L), 0.0), 3.0) * 0.10;
                vec3 col = uColor * (ambient + diff * 0.95) + vec3(rim);
                gl_FragColor = vec4(col, 1.0);
            }
        """

        private const val FS_SUN = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uTime;
            varying vec3 vWorld;
            varying vec3 vNormal;
            void main() {
                // bright emissive core with a hotter centre and warm limb
                vec3 V = normalize(-vWorld);
                float facing = max(dot(normalize(vNormal), V), 0.0);
                float flicker = 0.93 + 0.07 * sin(uTime * 3.0 + vWorld.y * 4.0);
                vec3 core = mix(uColor, vec3(1.0, 0.95, 0.8), facing * 0.6);
                gl_FragColor = vec4(core * flicker * 1.25, 1.0);
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
