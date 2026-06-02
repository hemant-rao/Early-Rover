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
    isDark: Boolean = isSystemInDarkTheme(),
    /** true -> planets visibly orbit the Sun; false -> hold exact real-time positions. */
    animateOrbits: Boolean = true
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var glView by remember { mutableStateOf<SolarSystemGLView?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx -> SolarSystemGLView(ctx).also { it.animateOrbits = animateOrbits; glView = it } },
        update = { it.animateOrbits = animateOrbits }
    )

    DisposableEffect(lifecycleOwner, glView) {
        val view = glView ?: return@DisposableEffect onDispose {}

        // Sync with current lifecycle state upon attachment
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
            view.onPause() // ensure it is paused upon disposal to release graphics resources
        }
    }
}
