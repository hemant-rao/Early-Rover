package com.example.alarm.opengl

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.time.LocalTime
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * GLSurfaceView host for [Sun3DRenderer]. This is the concrete, instantiable surface
 * that draws the spec-required daylight ring with a "now" marker whose position encodes
 * the current time between sunrise and sunset, plus sunrise/sunset/alarm markers.
 *
 * Wire this into the dashboard (see [Sun3DView]) to actually render the time-encoded
 * marker the original 3D spec calls for.
 */
class Sun3DGLView(context: Context) : GLSurfaceView(context) {

    val sunRenderer = Sun3DRenderer()

    private var isRenderingActive = false

    // Self-rescheduling ~20 FPS ticker so the decorative ring spin doesn't run the
    // GL thread at the device's full refresh rate (which doubled GPU/compositor load
    // against the throttled sibling planet surface). Mirrors SolarSystemGLView.
    private val renderTicker = object : Runnable {
        override fun run() {
            if (isRenderingActive) {
                requestRender()
                postDelayed(this, 50L) // Peaceful ~20 FPS
            }
        }
    }

    private fun startRenderLoop() {
        if (!isRenderingActive) {
            isRenderingActive = true
            removeCallbacks(renderTicker)
            post(renderTicker)
        }
    }

    private fun stopRenderLoop() {
        isRenderingActive = false
        removeCallbacks(renderTicker)
    }

    init {
        setEGLContextClientVersion(2)
        // Transparent surface so any weather/sky backdrop shows through behind the ring.
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setRenderer(sunRenderer)
        // Throttled redraw (WHEN_DIRTY + ~20 FPS ticker) instead of CONTINUOUSLY so
        // the two stacked GL surfaces don't both run unbounded.
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startRenderLoop()
    }

    override fun onDetachedFromWindow() {
        stopRenderLoop()
        super.onDetachedFromWindow()
    }

    override fun onResume() {
        super.onResume()
        startRenderLoop()
    }

    override fun onPause() {
        stopRenderLoop()
        super.onPause()
    }

    /** Update the daylight span, current time and active alarms drawn on the ring. */
    fun setTimes(
        sunrise: LocalTime,
        sunset: LocalTime,
        now: LocalTime = LocalTime.now(),
        alarms: List<LocalTime> = emptyList(),
        dark: Boolean
    ) {
        sunRenderer.sunriseHour = sunrise.hour + sunrise.minute / 60.0f
        sunRenderer.sunsetHour = sunset.hour + sunset.minute / 60.0f
        sunRenderer.currentHour = now.hour + now.minute / 60.0f
        sunRenderer.alarmHours = alarms.map { it.hour + it.minute / 60.0f }
        sunRenderer.isDarkMode = dark
        // RENDERMODE_WHEN_DIRTY: repaint so data changes are reflected even if the
        // ticker hasn't fired yet.
        requestRender()
    }
}

/**
 * Compose host for the spec-conformant daylight-ring scene ([Sun3DGLView] + [Sun3DRenderer]).
 *
 * Unlike [Celestial3DView] (the heliocentric planet layout), this view actually draws the
 * sunrise->sunset "now" marker and per-alarm markers required by the original 3D spec.
 * Drop it into DashboardScreen to surface that behaviour to the user.
 */
@Composable
fun Sun3DView(
    modifier: Modifier = Modifier,
    sunriseTime: LocalTime = LocalTime.of(6, 0),
    sunsetTime: LocalTime = LocalTime.of(18, 0),
    activeAlarms: List<LocalTime> = emptyList(),
    isDark: Boolean = isSystemInDarkTheme(),
    // "now" in the ACTIVE LOCATION's timezone. Callers in a different device TZ
    // (travel/multi-city) should pass the location-local time so the marker lands
    // on the correct fraction of the daylight span. Defaults to device wall clock.
    now: LocalTime = LocalTime.now()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var glView by remember { mutableStateOf<Sun3DGLView?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            Sun3DGLView(ctx).also {
                it.setTimes(sunriseTime, sunsetTime, now, activeAlarms, isDark)
                glView = it
            }
        },
        update = {
            it.setTimes(sunriseTime, sunsetTime, now, activeAlarms, isDark)
        }
    )

    DisposableEffect(lifecycleOwner, glView) {
        val view = glView ?: return@DisposableEffect onDispose {}

        val currentLifecycleState = lifecycleOwner.lifecycle.currentState
        if (currentLifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            view.onResume()
        } else {
            view.onPause()
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.onPause()
        }
    }
}

class Sun3DRenderer : GLSurfaceView.Renderer {

    // Thread-safe update properties
    @Volatile var sunriseHour: Float = 6.0f
    @Volatile var sunsetHour: Float = 18.0f
    @Volatile var currentHour: Float = 12.0f
    @Volatile var alarmHours: List<Float> = emptyList()
    @Volatile var isDarkMode: Boolean = true

    private val vPMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var programId = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpMatrixHandle = 0

    private var ringBuffer: FloatBuffer? = null
    private var ringVertexCount = 0

    private var markerBuffer: FloatBuffer? = null
    private var markerVertexCount = 36 // standard simple sphere

    private var rotationAngle = 0f

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        uniform vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """.trimIndent()

    @Volatile private var initError = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            // Build Shaders
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            if (programId != 0) {
                GLES20.glDeleteProgram(programId)
                programId = 0
            }
            programId = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertexShader)
                GLES20.glAttachShader(it, fragmentShader)
                GLES20.glLinkProgram(it)
                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linkStatus, 0)
                if (linkStatus[0] == 0) {
                    val log = GLES20.glGetProgramInfoLog(it)
                    GLES20.glDetachShader(it, vertexShader)
                    GLES20.glDetachShader(it, fragmentShader)
                    GLES20.glDeleteShader(vertexShader)
                    GLES20.glDeleteShader(fragmentShader)
                    GLES20.glDeleteProgram(it)
                    throw RuntimeException("Sun3DRenderer shader link failed: $log")
                }
                GLES20.glDetachShader(it, vertexShader)
                GLES20.glDetachShader(it, fragmentShader)
                GLES20.glDeleteShader(vertexShader)
                GLES20.glDeleteShader(fragmentShader)
            }

            // Initialize shape buffers
            setupRingCoords()
            setupMarkerCoords()

            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            initError = false
        } catch (e: Exception) {
            Log.e("Sun3DRenderer", "Surface creation failed gracefully: ", e)
            initError = true
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (initError) return
        try {
            GLES20.glViewport(0, 0, width, height)
            val ratio: Float = width.toFloat() / height.coerceAtLeast(1)
            Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 3f, 7f)
        } catch (e: Exception) {
            Log.e("Sun3DRenderer", "Surface changed failed: ", e)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        if (initError || ringBuffer == null || markerBuffer == null) return
        try {
            // Rotate sphere over time for dynamic ambient atmosphere
            rotationAngle = (rotationAngle + 0.3f) % 360f

            // Transparent clear: this is the top overlay surface stacked above the
            // heliocentric planet view, so the planet scene (and the Compose themed
            // backdrop behind both GL surfaces) must show through behind the ring.
            // A themed/opaque clear here would occlude the entire planet scene.
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            // Camera position (Perspective 3D view and tilt)
            Matrix.setLookAtM(
                viewMatrix, 0,
                0f, 2.5f, 4f,     // Eye XYZ (elevated and back)
                0f, 0f, 0f,       // LookAt XYZ
                0f, 1f, 0f        // Up vector
            )
            Matrix.multiplyMM(vPMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            GLES20.glUseProgram(programId)

            // Handles
            positionHandle = GLES20.glGetAttribLocation(programId, "vPosition")
            colorHandle = GLES20.glGetUniformLocation(programId, "vColor")
            mvpMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")

            // 1. DRAW TIMELINE RING
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.rotateM(modelMatrix, 0, rotationAngle, 0f, 1f, 0f) // dynamic rotation
            Matrix.multiplyMM(mvpMatrix, 0, vPMatrix, 0, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

            val ringColor = if (isDarkMode) floatArrayOf(0.2f, 0.4f, 0.8f, 0.3f) else floatArrayOf(0.7f, 0.8f, 0.9f, 0.5f)
            drawCoords(ringBuffer!!, GLES20.GL_LINE_STRIP, ringVertexCount, ringColor)

            // 2. DRAW NOW (Golden Sun Position on Dial)
            // Encode current time as a fraction of the daylight span (sunrise -> sunset),
            // not a raw 24h clock, so the sun sweeps the full ring across the day.
            // Only draw the marker during daylight (fraction within 0..1); at night the
            // fraction exceeds the span and would mis-encode the time on the daylight arc.
            val nowFrac = dayFraction(currentHour)
            if (nowFrac in 0f..1f) {
                val nowAngle = -nowFrac * 360.0f + 90f + rotationAngle
                drawMarkerOnRing(nowAngle, 0.16f, floatArrayOf(0.98f, 0.80f, 0.20f, 1.0f)) // bright sun
            }

            // 3. DRAW SUNRISE INDICATOR (Sunburst Gold) -> start of daylight span (fraction 0)
            val sunriseAngle = - dayFraction(sunriseHour) * 360.0f + 90f + rotationAngle
            drawMarkerOnRing(sunriseAngle, 0.11f, floatArrayOf(0.96f, 0.50f, 0.10f, 0.9f)) // sunrise gold

            // 4. DRAW SUNSET INDICATOR (Crimson Orange) -> end of daylight span (fraction 1)
            val sunsetAngle = - dayFraction(sunsetHour) * 360.0f + 90f + rotationAngle
            drawMarkerOnRing(sunsetAngle, 0.11f, floatArrayOf(0.90f, 0.20f, 0.15f, 0.9f)) // sunset crimson

            // 5. DRAW ACTIVE ALARMS (Aura Violet / Teal)
            for (i in alarmHours.indices) {
                val alarmDegree = - dayFraction(alarmHours[i]) * 360.0f + 90f + rotationAngle
                drawMarkerOnRing(alarmDegree, 0.08f, floatArrayOf(0.12f, 0.82f, 0.72f, 1.0f)) // vibrant teal
            }
        } catch (e: Exception) {
            Log.e("Sun3DRenderer", "onDrawFrame failed gracefully: ", e)
        }
    }

    /**
     * Normalizes an hour-of-day to its position within the daylight span.
     * Returns 0f at sunrise and 1f at sunset so markers encode the daylight
     * fraction (per spec) rather than a raw 24h clock angle. Handles a
     * cross-midnight daylight span and guards against an empty (sunrise == sunset)
     * span to avoid division by zero.
     */
    private fun dayFraction(hour: Float): Float {
        var span = sunsetHour - sunriseHour
        if (span < 0f) span += 24f // daylight crosses midnight
        if (span == 0f) return 0f  // degenerate span: avoid divide-by-zero
        var delta = hour - sunriseHour
        if (delta < 0f) delta += 24f
        return delta / span
    }

    private fun drawMarkerOnRing(angleDeg: Float, scale: Float, color: FloatArray) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val radius = 1.25f // matches ring layout radius
        val x = (radius * cos(rad)).toFloat()
        val z = (radius * sin(rad)).toFloat()

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, 0f, z)
        Matrix.scaleM(modelMatrix, 0, scale, scale, scale)
        Matrix.multiplyMM(mvpMatrix, 0, vPMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        drawCoords(markerBuffer!!, GLES20.GL_TRIANGLE_FAN, markerVertexCount, color)
    }

    private fun drawCoords(buffer: FloatBuffer, drawMode: Int, count: Int, color: FloatArray) {
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, buffer)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glDrawArrays(drawMode, 0, count)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun setupRingCoords() {
        val segments = 72
        val coords = ArrayList<Float>()
        val radius = 1.25f

        for (i in 0..segments) {
            val theta = i * (2.0f * Math.PI) / segments
            val x = radius * cos(theta).toFloat()
            val z = radius * sin(theta).toFloat()
            coords.add(x)
            coords.add(0.0f) // flat plane ring
            coords.add(z)
        }
        
        ringVertexCount = coords.size / 3
        val array = coords.toFloatArray()
        ringBuffer = ByteBuffer.allocateDirect(array.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(array)
                position(0)
            }
        }
    }

    private fun setupMarkerCoords() {
        // Build a flat polygonal bead as a GL_TRIANGLE_FAN laid in the XZ plane so it
        // matches the orientation of the timeline ring (also in XZ). Emitting (x, 0, z)
        // makes the disc face up toward the camera at (0, 2.5, 4) instead of standing
        // edge-on as a thin vertical sliver in the XY plane.
        val pointerCoords = ArrayList<Float>()

        // Center point for GL_TRIANGLE_FAN.
        pointerCoords.add(0.0f)
        pointerCoords.add(0.0f)
        pointerCoords.add(0.0f)

        val radialPoints = 16
        for (i in 0..radialPoints) {
            val theta = i * (2.0 * Math.PI) / radialPoints
            val x = cos(theta).toFloat()
            val z = sin(theta).toFloat()
            pointerCoords.add(x)
            pointerCoords.add(0.0f) // flat disc lying in the XZ plane (matches ring)
            pointerCoords.add(z)
        }

        markerVertexCount = pointerCoords.size / 3
        val array = pointerCoords.toFloatArray()
        markerBuffer = ByteBuffer.allocateDirect(array.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(array)
                position(0)
            }
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            
            // Check for error
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                Log.e("Sun3DRenderer", "Shader compilation failure for type $type: $log")
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Sun3DRenderer shader compile failed for type $type: $log")
            }
        }
    }
}
