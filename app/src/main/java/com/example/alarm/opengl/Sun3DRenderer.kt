package com.example.alarm.opengl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.time.LocalTime
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

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
            programId = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertexShader)
                GLES20.glAttachShader(it, fragmentShader)
                GLES20.glLinkProgram(it)
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
            val ratio: Float = width.toFloat() / height.toFloat()
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

            // Theme colors
            if (isDarkMode) {
                GLES20.glClearColor(0.04f, 0.05f, 0.08f, 1.0f) // Deep celestial navy
            } else {
                GLES20.glClearColor(0.95f, 0.96f, 0.98f, 1.00f) // Clean slate white
            }
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

            // 2. DRAW NOON (Golden Sun Position on Dial)
            val noonAngle = - (currentHour / 24.0f) * 360.0f + 90f + rotationAngle
            drawMarkerOnRing(noonAngle, 0.16f, floatArrayOf(0.98f, 0.80f, 0.20f, 1.0f)) // bright sun

            // 3. DRAW SUNRISE INDICATOR (Sunburst Gold)
            val sunriseAngle = - (sunriseHour / 24.0f) * 360.0f + 90f + rotationAngle
            drawMarkerOnRing(sunriseAngle, 0.11f, floatArrayOf(0.96f, 0.50f, 0.10f, 0.9f)) // sunrise gold

            // 4. DRAW SUNSET INDICATOR (Crimson Orange)
            val sunsetAngle = - (sunsetHour / 24.0f) * 360.0f + 90f + rotationAngle
            drawMarkerOnRing(sunsetAngle, 0.11f, floatArrayOf(0.90f, 0.20f, 0.15f, 0.9f)) // sunset crimson

            // 5. DRAW ACTIVE ALARMS (Aura Violet / Teal)
            for (i in alarmHours.indices) {
                val alarmDegree = - (alarmHours[i] / 24.0f) * 360.0f + 90f + rotationAngle
                drawMarkerOnRing(alarmDegree, 0.08f, floatArrayOf(0.12f, 0.82f, 0.72f, 1.0f)) // vibrant teal
            }
        } catch (e: Exception) {
            Log.e("Sun3DRenderer", "onDrawFrame failed gracefully: ", e)
        }
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
        // Create simple 3D Diamond / Octahedron pointer mesh coordinates
        val coords = floatArrayOf(
            0.0f,  1.0f,  0.0f, // Top point
            1.0f,  0.0f,  1.0f, // Base midpoints
           -1.0f,  0.0f,  1.0f,
           -1.0f,  0.0f, -1.0f,
            1.0f,  0.0f, -1.0f,
            1.0f,  0.0f,  1.0f, // repeat starting point to close
            0.0f, -1.0f,  0.0f  // Bottom point
        )

        // Make composite geometry with multiple triangular subdivisions
        val pointerCoords = ArrayList<Float>()
        
        // Let's create an elegant polygonal bead shape using simple trig values
        pointerCoords.add(0.0f)
        pointerCoords.add(0.0f)
        pointerCoords.add(0.0f) // center point for GL_TRIANGLE_FAN
        
        val radialPoints = 16
        for (i in 0..radialPoints) {
            val theta = i * (2.0 * Math.PI) / radialPoints
            val x = cos(theta).toFloat()
            val y = sin(theta).toFloat()
            pointerCoords.add(x)
            pointerCoords.add(y)
            pointerCoords.add(0.0f) // flat diamond shape on face
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
                Log.e("Sun3DRenderer", "Shader compilation failure for type $type: " + GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
            }
        }
    }
}
