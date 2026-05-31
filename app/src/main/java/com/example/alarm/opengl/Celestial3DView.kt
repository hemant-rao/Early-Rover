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
 * Compose host for the native OpenGL ES solar system. Replaces the old WebView /
 * Three.js scene that caused the "pixel rendering not responding" ANR.
 *
 * The parameters are kept for call-site compatibility; the heliocentric layout is
 * computed natively from the device clock inside [SolarSystemGLView].
 */
@Composable
fun Celestial3DView(
    modifier: Modifier = Modifier,
    sunriseTime: LocalTime = LocalTime.of(6, 0),
    sunsetTime: LocalTime = LocalTime.of(18, 0),
    activeAlarms: List<Pair<Int, Int>> = emptyList(),
    isDark: Boolean = isSystemInDarkTheme()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var glView by remember { mutableStateOf<SolarSystemGLView?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx -> SolarSystemGLView(ctx).also { glView = it } }
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> glView?.onResume()
                Lifecycle.Event.ON_PAUSE -> glView?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
