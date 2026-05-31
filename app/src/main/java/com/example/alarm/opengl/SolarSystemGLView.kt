package com.example.alarm.opengl

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import java.time.LocalDateTime
import kotlin.math.hypot

/**
 * Interactive [GLSurfaceView] hosting the [SolarSystemRenderer].
 *
 * One finger drags to orbit the camera, two fingers pinch to zoom. Planet
 * positions are recomputed from the real clock on creation and refreshed on a
 * slow cadence (orbital angles change imperceptibly minute-to-minute).
 */
class SolarSystemGLView(context: Context) : GLSurfaceView(context) {

    private val renderer = SolarSystemRenderer()

    /** true -> planets visibly orbit; false -> hold the exact real-time position. */
    var animateOrbits: Boolean
        get() = renderer.animateOrbits
        set(value) { renderer.animateOrbits = value }

    private var lastX = 0f
    private var lastY = 0f
    private var lastPinch = 0f
    private var mode = NONE

    private val ticker = Runnable { refreshAndScheduleNext() }

    init {
        setEGLContextClientVersion(2)
        // Transparent surface so the animated weather sky shows through behind it.
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        renderer.updatePositions(LocalDateTime.now())
    }

    private fun refreshAndScheduleNext() {
        renderer.updatePositions(LocalDateTime.now())
        postDelayed(ticker, 60_000L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(ticker, 60_000L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x; lastY = event.y; mode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastPinch = spacing(event); mode = ZOOM
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (mode == ZOOM && event.pointerCount >= 2) {
                    val d = spacing(event)
                    if (lastPinch > 0f) {
                        val factor = lastPinch / d
                        renderer.distance = (renderer.distance * factor).coerceIn(7f, 32f)
                    }
                    lastPinch = d
                } else if (mode == DRAG) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    renderer.yaw += dx * 0.006f
                    renderer.pitch = (renderer.pitch + dy * 0.006f).coerceIn(-1.45f, -0.05f)
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                mode = DRAG
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                mode = NONE
            }
        }
        return true
    }

    private fun spacing(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    private companion object {
        const val NONE = 0
        const val DRAG = 1
        const val ZOOM = 2
    }
}
