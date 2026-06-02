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
        set(value) {
            renderer.animateOrbits = value
            if (value) {
                startRenderLoop()
            } else {
                stopRenderLoop()
            }
        }

    /** Invoked (on the main thread) with a body name when one is tapped. */
    var onBodyTap: ((String) -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var lastPinch = 0f
    private var mode = NONE

    // ----- tap-vs-drag discrimination -----
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private val ticker = Runnable { refreshAndScheduleNext() }

    private var isRenderingActive = false

    private val renderTicker = object : Runnable {
        override fun run() {
            if (isRenderingActive && animateOrbits) {
                requestRender()
                postDelayed(this, 50L) // Peaceful ~20 FPS when animating
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
        // Transparent surface so the animated weather sky shows through behind it.
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        renderer.updatePositions(LocalDateTime.now())
    }

    private fun refreshAndScheduleNext() {
        renderer.updatePositions(LocalDateTime.now())
        removeCallbacks(ticker)
        postDelayed(ticker, 60_000L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animateOrbits) {
            startRenderLoop()
        }
        removeCallbacks(ticker)
        postDelayed(ticker, 60_000L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        stopRenderLoop()
        super.onDetachedFromWindow()
    }

    override fun onResume() {
        super.onResume()
        if (animateOrbits) {
            startRenderLoop()
        }
    }

    override fun onPause() {
        stopRenderLoop()
        super.onPause()
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
                downX = event.x; downY = event.y; moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastPinch = spacing(event); mode = ZOOM; moved = true
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
                    // Past the slop radius this gesture is a drag, not a tap.
                    if (hypot(event.x - downX, event.y - downY) > TAP_SLOP) moved = true
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
                if (event.actionMasked == MotionEvent.ACTION_UP && mode == DRAG && !moved) {
                    // A real tap (no drag, no pinch): pick the body under the finger.
                    val name = renderer.pickBody(event.x, event.y)
                    if (name != null) post { onBodyTap?.invoke(name) }
                }
                requestDisallowIntercept(false)
                mode = NONE
            }
        }
        requestRender()
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
        const val TAP_SLOP = 12f
    }
}
