package com.example.alarm.opengl

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
import java.time.LocalTime

/**
 * Compose wrapper for the 3D OpenGL Sun daylight/alarm tracker.
 */
@Composable
fun Sun3DProgressionView(
    modifier: Modifier = Modifier,
    sunriseTime: LocalTime = LocalTime.of(6, 0),
    sunsetTime: LocalTime = LocalTime.of(18, 0),
    currentTime: LocalTime = LocalTime.now(),
    alarmTimes: List<LocalTime> = emptyList(),
    isDark: Boolean = isSystemInDarkTheme()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var glView by remember { mutableStateOf<Sun3DGLView?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            Sun3DGLView(ctx).also {
                it.updateData(sunriseTime, sunsetTime, currentTime, alarmTimes, isDark)
                glView = it
            }
        },
        update = {
            it.updateData(sunriseTime, sunsetTime, currentTime, alarmTimes, isDark)
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
