package com.example.alarm.opengl

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
 * The heliocentric planet layout is computed natively from the device clock inside
 * [SolarSystemGLView], so the scene already reflects the current moment (each planet
 * sits at its real heliocentric angle "right now").
 *
 * On top of the heliocentric layout this view layers the spec-conformant daylight ring
 * ([Sun3DView]): [sunriseTime], [sunsetTime] and [activeAlarms] drive a "now" sun marker
 * whose position on the ring encodes the current time within the sunrise->sunset daylight
 * span (sunrise at one side, sunset at the other), plus sunrise/sunset/alarm beads — the
 * defining requirement of the original 3D spec.
 */
@Composable
fun Celestial3DView(
    modifier: Modifier = Modifier,
    sunriseTime: LocalTime = LocalTime.of(6, 0),
    sunsetTime: LocalTime = LocalTime.of(18, 0),
    activeAlarms: List<Pair<Int, Int>> = emptyList(),
    isDark: Boolean = isSystemInDarkTheme(),
    /** true -> planets visibly orbit the Sun; false -> hold exact real-time positions. */
    animateOrbits: Boolean = true,
    /** Invoked with the tapped body name ("Sun","Mercury",..,"Neptune"). */
    onPlanetSelected: (String) -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var glView by remember { mutableStateOf<SolarSystemGLView?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SolarSystemGLView(ctx).also {
                    it.animateOrbits = animateOrbits
                    it.onBodyTap = { name -> onPlanetSelected(name) }
                    glView = it
                }
            },
            update = {
                it.animateOrbits = animateOrbits
                it.onBodyTap = { name -> onPlanetSelected(name) }
            }
        )

        // Spec-conformant time-of-day overlay: a "now" sun marker travels the daylight
        // ring from sunrise to sunset, with sunrise/sunset and per-alarm beads. This is
        // the requirement the heliocentric planet layout alone does not express.
        Sun3DView(
            modifier = Modifier.fillMaxSize(),
            sunriseTime = sunriseTime,
            sunsetTime = sunsetTime,
            activeAlarms = activeAlarms.map { LocalTime.of(it.first, it.second) },
            isDark = isDark
        )
    }

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
