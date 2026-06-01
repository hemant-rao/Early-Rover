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
        onResume()
        postDelayed(ticker, 60_000L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        onPause()
        super.onDetachedFromWindow()
    }

    private fun requestDisallowIntercept(disallow: Boolean) {
        var p = parent
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(disallow)
            p = p.parent
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestDisallowIntercept(true)
                lastX = event.x; lastY = event.y; mode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastPinch = spacing(event); mode = ZOOM
            }
            MotionEvent.ACTION_MOVE -> {
                requestDisallowIntercept(true)
                if (mode == ZOOM && event.pointerCount >= 2) {
                    val d = spacing(event)
                    if (d > 10f && lastPinch > 10f) {
                        val factor = lastPinch / d
                        if (!factor.isNaN() && !factor.isInfinite()) {
                            renderer.distance = (renderer.distance * factor).coerceIn(7f, 32f)
                        }
                    }
                    lastPinch = d
                } else if (mode == DRAG) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (!dx.isNaN() && !dx.isInfinite() && !dy.isNaN() && !dy.isInfinite()) {
                        renderer.yaw += dx * 0.006f
                        renderer.pitch = (renderer.pitch + dy * 0.006f).coerceIn(-1.52f, 0.35f)
                    }
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                mode = DRAG
                val activeIndex = if (event.actionIndex == 0) 1 else 0
                if (activeIndex < event.pointerCount) {
                    try {
                        lastX = event.getX(activeIndex)
                        lastY = event.getY(activeIndex)
                    } catch (e: Exception) {
                        lastX = event.x
                        lastY = event.y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                requestDisallowIntercept(false)
                mode = NONE
            }
        }
        return true
    }

    private fun spacing(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return try {
            hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
        } catch (ex: Exception) {
            0f
        }
    }

    private companion object {
        const val NONE = 0
        const val DRAG = 1
        const val ZOOM = 2
    }
}
