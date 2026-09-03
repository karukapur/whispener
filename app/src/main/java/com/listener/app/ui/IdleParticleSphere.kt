package com.listener.app.ui

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt

@Composable
fun IdleParticleSphere(
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    particleColor: Color,
    haloColor: Color,
    particleRadiusScale: Float = 1f,
    paused: Boolean = false,
) {
    val motionEnabled = remember {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
    }
    var elapsedNanos by remember { mutableLongStateOf(0L) }
    val points = remember { makeIdleSpherePoints(IdleSpherePointCount) }

    LaunchedEffect(paused, motionEnabled) {
        if (paused || !motionEnabled) {
            elapsedNanos = IdleSphereStaticFrameNanos
            return@LaunchedEffect
        }
        var startedAtNanos = 0L
        var lastRenderedAtNanos = 0L
        while (isActive) {
            withFrameNanos { frameNanos ->
                if (startedAtNanos == 0L) startedAtNanos = frameNanos
                if (frameNanos - lastRenderedAtNanos >= IdleSphereFrameIntervalNanos) {
                    elapsedNanos = frameNanos - startedAtNanos
                    lastRenderedAtNanos = frameNanos
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .clearAndSetSemantics { }
            .size(size),
    ) {
        val timeSeconds = elapsedNanos / 1_000_000_000.0
        drawIdleSphereHalo(haloColor)
        drawIdleSphereParticles(points, timeSeconds, particleColor, particleRadiusScale)
    }
}

private data class SpherePoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val radiusScale: Double,
)

private fun DrawScope.drawIdleSphereHalo(haloColor: Color) {
    val center = Offset(size.width / 2f, size.height / 2f)
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to haloColor.copy(alpha = 0.035f),
                0.52f to haloColor.copy(alpha = 0.012f),
                1f to haloColor.copy(alpha = 0f),
            ),
            center = center,
            radius = size.minDimension * 0.26f,
        ),
        radius = size.minDimension * 0.26f,
        center = center,
    )
}

private fun DrawScope.drawIdleSphereParticles(
    points: List<SpherePoint>,
    timeSeconds: Double,
    particleColor: Color,
    particleRadiusScale: Float,
) {
    val centerX = size.width / 2.0
    val centerY = size.height / 2.0
    val sphereScale = size.minDimension * 0.40
    val distance = 3.1
    val focalLength = 2.25
    val rotY = timeSeconds * 0.19
    val rotX = timeSeconds * 0.115
    val sinY = kotlin.math.sin(rotY)
    val cosY = kotlin.math.cos(rotY)
    val sinX = kotlin.math.sin(rotX)
    val cosX = kotlin.math.cos(rotX)
    val density = this.density

    points.forEach { point ->
        val x0 = point.x * point.radiusScale
        val y0 = point.y * point.radiusScale
        val z0 = point.z * point.radiusScale

        val x1 = x0 * cosY + z0 * sinY
        val z1 = -x0 * sinY + z0 * cosY
        val y1 = y0 * cosX - z1 * sinX
        val z2 = y0 * sinX + z1 * cosX

        val perspective = focalLength / (distance - z2)
        val depth = ((z2 + 1.08) / 2.16).coerceIn(0.0, 1.0)
        drawCircle(
            color = particleColor.copy(alpha = lerp(0.15, 0.95, depth).toFloat()),
            radius = max(0.3f, lerp(0.7, 1.6, depth).toFloat() * density * particleRadiusScale),
            center = Offset(
                x = (centerX + x1 * perspective * sphereScale).toFloat(),
                y = (centerY + y1 * perspective * sphereScale).toFloat(),
            ),
        )
    }
}

private fun makeIdleSpherePoints(count: Int): List<SpherePoint> {
    val result = ArrayList<SpherePoint>(count)
    val random = Mulberry32(IdleSphereSeed)
    val goldenAngle = PI * (3.0 - sqrt(5.0))
    repeat(count) { index ->
        val y = 1.0 - (index / (count - 1).toDouble()) * 2.0
        val radius = sqrt(max(0.0, 1.0 - y * y))
        val theta = index * goldenAngle
        result += SpherePoint(
            x = kotlin.math.cos(theta) * radius,
            y = y,
            z = kotlin.math.sin(theta) * radius,
            radiusScale = 0.94 + random.next() * 0.12,
        )
    }
    return result
}

private class Mulberry32(seed: Int) {
    private var value = seed

    fun next(): Double {
        value += 0x6D2B79F5
        var t = value
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + ((t xor (t ushr 7)) * (t or 61)))
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
    }
}

private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

private const val IdleSpherePointCount = 1800
private const val IdleSphereSeed = 9427
private const val IdleSphereFrameIntervalNanos = 33_333_333L
private const val IdleSphereStaticFrameNanos = 600_000_000L
