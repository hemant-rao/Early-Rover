package com.example.alarm.opengl

import android.content.Context
import android.opengl.GLSurfaceView
import java.time.LocalTime

class Sun3DGLView(context: Context) : GLSurfaceView(context) {
    val renderer = Sun3DRenderer()

    private var isRenderingActive = false

    private val renderTicker = object : Runnable {
        override fun run() {
            if (isRenderingActive) {
                requestRender()
                postDelayed(this, 33L) // ~30 FPS animation
            }
        }
    }

    init {
        setEGLContextClientVersion(2)
        // Setup transparent drawing surface so it blends with background cards
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun updateData(
        sunrise: LocalTime,
        sunset: LocalTime,
        current: LocalTime,
        alarms: List<LocalTime>,
        darkTheme: Boolean
    ) {
        queueEvent {
            renderer.sunriseHour = sunrise.hour + sunrise.minute / 60.0f
            renderer.sunsetHour = sunset.hour + sunset.minute / 60.0f
            renderer.currentHour = current.hour + current.minute / 60.0f
            renderer.alarmHours = alarms.map { it.hour + it.minute / 60.0f }
            renderer.isDarkMode = darkTheme
        }
        requestRender()
    }

    fun startAnimation() {
        if (!isRenderingActive) {
            isRenderingActive = true
            removeCallbacks(renderTicker)
            post(renderTicker)
        }
    }

    fun stopAnimation() {
        isRenderingActive = false
        removeCallbacks(renderTicker)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onResume() {
        super.onResume()
        startAnimation()
    }

    override fun onPause() {
        stopAnimation()
        super.onPause()
    }
}
