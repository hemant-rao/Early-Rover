package com.example.alarm.ui.weather

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.alarm.weather.WeatherCondition
import com.example.alarm.weather.WeatherInfo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen animated sky reflecting the current location's weather.
 *
 * Pure Compose Canvas — gradient sky plus condition-specific motion (sun rays,
 * drifting clouds, falling rain/snow, fog bands, lightning, night stars). Designed
 * to sit behind the dashboard content with a readability scrim on top.
 */
@Composable
fun WeatherBackground(
    weather: WeatherInfo?,
    modifier: Modifier = Modifier
) {
    val condition = weather?.condition ?: WeatherCondition.CLEAR
    val isDay = weather?.isDay ?: true

    val transition = rememberInfiniteTransition(label = "weather")
    // Continuous phase 0..1 (periodic) for falling particles and ray rotation.
    val fast by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "fast"
    )
    val slow by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart),
        label = "slow"
    )
    val pulse by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )

    val sky = skyGradient(condition, isDay)

    // Deterministic particle/star layouts (regenerated only when the condition changes).
    val particles = remember(condition) { generateParticles(condition) }
    val stars = remember(condition, isDay) {
        if (!isDay && (condition == WeatherCondition.CLEAR || condition == WeatherCondition.FEW_CLOUDS))
            generateStars() else emptyList()
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(brush = Brush.verticalGradient(sky), size = size)

        // Night stars
        for (s in stars) {
            val tw = 0.4f + 0.6f * (0.5f + 0.5f * sin(((pulse * 2f * PI.toFloat()) + s.phase).toDouble()).toFloat())
            drawCircle(
                color = Color.White.copy(alpha = 0.7f * tw),
                radius = s.r * density,
                center = Offset(s.x * size.width, s.y * size.height)
            )
        }

        when (condition) {
            WeatherCondition.CLEAR, WeatherCondition.FEW_CLOUDS -> {
                if (isDay) drawSun(pulse)
                else drawMoon()
                if (condition == WeatherCondition.FEW_CLOUDS) drawClouds(particles, slow, isDay, light = true)
            }
            WeatherCondition.CLOUDS -> drawClouds(particles, slow, isDay, light = false)
            WeatherCondition.FOG -> drawFog(slow)
            WeatherCondition.RAIN -> {
                drawClouds(particles.filter { it.kind == Kind.CLOUD }, slow, isDay, light = false)
                drawRain(particles.filter { it.kind == Kind.DROP }, fast)
            }
            WeatherCondition.SNOW -> {
                drawClouds(particles.filter { it.kind == Kind.CLOUD }, slow, isDay, light = false)
                drawSnow(particles.filter { it.kind == Kind.FLAKE }, fast)
            }
            WeatherCondition.THUNDER -> {
                drawClouds(particles.filter { it.kind == Kind.CLOUD }, slow, isDay, light = false)
                drawRain(particles.filter { it.kind == Kind.DROP }, fast)
                drawLightning(pulse)
            }
        }
    }
}

// ----------------------------------------------------------------- drawing pieces

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSun(pulse: Float) {
    val center = Offset(size.width * 0.78f, size.height * 0.16f)
    val rays = 12
    val rot = pulse * 2f * PI.toFloat()
    val rayLen = size.minDimension * 0.10f
    val baseR = size.minDimension * 0.07f
    for (i in 0 until rays) {
        val a = rot + i * (2f * PI.toFloat() / rays)
        val start = Offset(center.x + cos(a) * baseR * 1.3f, center.y + sin(a) * baseR * 1.3f)
        val end = Offset(center.x + cos(a) * (baseR * 1.3f + rayLen), center.y + sin(a) * (baseR * 1.3f + rayLen))
        drawLine(Color(0xFFFFE08A).copy(alpha = 0.45f), start, end, strokeWidth = 6f)
    }
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFF3C4), Color(0xFFFFC861).copy(alpha = 0.0f)),
            center = center, radius = baseR * 2.6f
        ),
        radius = baseR * 2.6f, center = center
    )
    drawCircle(Color(0xFFFFE49B), radius = baseR, center = center)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMoon() {
    val center = Offset(size.width * 0.78f, size.height * 0.16f)
    val r = size.minDimension * 0.06f
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFE8ECF5), Color(0xFFB8C2D9).copy(alpha = 0f)),
            center = center, radius = r * 3f
        ),
        radius = r * 3f, center = center
    )
    drawCircle(Color(0xFFE8ECF5), radius = r, center = center)
    // crescent shadow
    drawCircle(Color(0xFF12203A), radius = r, center = Offset(center.x + r * 0.6f, center.y - r * 0.3f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawClouds(
    particles: List<Particle>, slow: Float, isDay: Boolean, light: Boolean
) {
    val cloudColor = if (isDay) Color.White else Color(0xFF8A93A8)
    val alpha = if (light) 0.5f else 0.8f
    for (p in particles.filter { it.kind == Kind.CLOUD }) {
        val x = (((p.x + slow * p.speed) % 1.3f) - 0.15f) * size.width
        val y = p.y * size.height
        val s = p.scale * size.minDimension
        drawCloud(Offset(x, y), s, cloudColor.copy(alpha = alpha))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCloud(c: Offset, s: Float, color: Color) {
    drawCircle(color, radius = s * 0.5f, center = c)
    drawCircle(color, radius = s * 0.38f, center = Offset(c.x - s * 0.55f, c.y + s * 0.12f))
    drawCircle(color, radius = s * 0.42f, center = Offset(c.x + s * 0.55f, c.y + s * 0.1f))
    drawCircle(color, radius = s * 0.34f, center = Offset(c.x + s * 0.12f, c.y - s * 0.28f))
    drawRect(color, topLeft = Offset(c.x - s * 0.9f, c.y), size = Size(s * 1.8f, s * 0.5f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRain(drops: List<Particle>, fast: Float) {
    for (p in drops) {
        val y = ((p.y + fast * p.speed) % 1f) * size.height
        val x = p.x * size.width
        val len = p.scale * size.height
        drawLine(
            Color(0xFF9FC8FF).copy(alpha = 0.55f),
            Offset(x, y), Offset(x - len * 0.15f, y + len),
            strokeWidth = 3f
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSnow(flakes: List<Particle>, fast: Float) {
    for (p in flakes) {
        val baseY = (p.y + fast * p.speed) % 1f
        val sway = sin((baseY * 6f + p.phase) * PI.toFloat()) * 0.02f
        val x = (p.x + sway) * size.width
        val y = baseY * size.height
        drawCircle(Color.White.copy(alpha = 0.85f), radius = p.scale * size.minDimension, center = Offset(x, y))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFog(slow: Float) {
    for (i in 0 until 5) {
        val y = (0.2f + i * 0.16f) * size.height
        val shift = (((slow * (1 + i * 0.3f)) + i * 0.2f) % 1f - 0.5f) * size.width * 0.4f
        drawRect(
            color = Color(0xFFCBD2DE).copy(alpha = 0.12f),
            topLeft = Offset(shift, y),
            size = Size(size.width, size.height * 0.10f)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLightning(pulse: Float) {
    // brief flashes near the start of each cycle
    val flash = when {
        pulse < 0.04f -> 0.5f
        pulse in 0.08f..0.11f -> 0.35f
        else -> 0f
    }
    if (flash > 0f) drawRect(Color.White.copy(alpha = flash), size = size)
}

// ----------------------------------------------------------------- data + gradients

private enum class Kind { CLOUD, DROP, FLAKE }
private class Particle(
    val kind: Kind, val x: Float, val y: Float,
    val speed: Float, val scale: Float, val phase: Float
)
private class Star(val x: Float, val y: Float, val r: Float, val phase: Float)

private fun generateParticles(condition: WeatherCondition): List<Particle> {
    val rnd = java.util.Random(condition.ordinal.toLong() * 99991L + 7L)
    fun f() = rnd.nextFloat()
    val list = ArrayList<Particle>()
    // clouds for cloudy / rain / snow / thunder / few clouds
    val cloudCount = when (condition) {
        WeatherCondition.CLOUDS, WeatherCondition.THUNDER -> 6
        WeatherCondition.RAIN, WeatherCondition.SNOW -> 4
        WeatherCondition.FEW_CLOUDS -> 3
        else -> 0
    }
    repeat(cloudCount) {
        list.add(Particle(Kind.CLOUD, f(), 0.08f + f() * 0.35f, 0.4f + f() * 0.6f, 0.10f + f() * 0.10f, f()))
    }
    if (condition == WeatherCondition.RAIN || condition == WeatherCondition.THUNDER) {
        repeat(90) {
            list.add(Particle(Kind.DROP, f(), f(), (1 + rnd.nextInt(3)).toFloat(), 0.05f + f() * 0.05f, f()))
        }
    }
    if (condition == WeatherCondition.SNOW) {
        repeat(70) {
            list.add(Particle(Kind.FLAKE, f(), f(), (1 + rnd.nextInt(2)).toFloat(), 0.004f + f() * 0.006f, f() * 2f))
        }
    }
    return list
}

private fun generateStars(): List<Star> {
    val rnd = java.util.Random(424242L)
    return List(60) { Star(rnd.nextFloat(), rnd.nextFloat() * 0.6f, 1f + rnd.nextFloat() * 1.6f, rnd.nextFloat() * 6f) }
}

private fun skyGradient(condition: WeatherCondition, isDay: Boolean): List<Color> = when (condition) {
    WeatherCondition.CLEAR ->
        if (isDay) listOf(Color(0xFF4A90E2), Color(0xFF87C4F0), Color(0xFFCDE7FA))
        else listOf(Color(0xFF0A1230), Color(0xFF111B3E), Color(0xFF1C2A52))
    WeatherCondition.FEW_CLOUDS ->
        if (isDay) listOf(Color(0xFF5E96D6), Color(0xFF9FC4E8), Color(0xFFD6E6F2))
        else listOf(Color(0xFF0C1530), Color(0xFF15203F), Color(0xFF222F50))
    WeatherCondition.CLOUDS ->
        if (isDay) listOf(Color(0xFF6E7C8C), Color(0xFF93A1AE), Color(0xFFB9C3CC))
        else listOf(Color(0xFF161B22), Color(0xFF222932), Color(0xFF313943))
    WeatherCondition.FOG ->
        if (isDay) listOf(Color(0xFF9AA3AD), Color(0xFFB7BEC6), Color(0xFFD3D8DD))
        else listOf(Color(0xFF20262E), Color(0xFF2C333C), Color(0xFF3A424C))
    WeatherCondition.RAIN ->
        if (isDay) listOf(Color(0xFF49566B), Color(0xFF647387), Color(0xFF8493A6))
        else listOf(Color(0xFF0E141F), Color(0xFF18212F), Color(0xFF26303F))
    WeatherCondition.SNOW ->
        if (isDay) listOf(Color(0xFF8FA4BC), Color(0xFFB7C6D8), Color(0xFFE2EAF2))
        else listOf(Color(0xFF1A2233), Color(0xFF273248), Color(0xFF3A475E))
    WeatherCondition.THUNDER ->
        listOf(Color(0xFF1C2030), Color(0xFF2A2F45), Color(0xFF3A3F58))
}
