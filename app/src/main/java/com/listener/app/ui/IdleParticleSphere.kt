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
import kotlin.math.floor
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
    var frameNanos by remember { mutableLongStateOf(0L) }
    val points = remember { makeIdleSpherePoints(IdleSpherePointCount) }
    val noise = remember { SimplexNoise(IdleSphereSeed) }

    LaunchedEffect(paused, motionEnabled) {
        if (paused || !motionEnabled) {
            frameNanos = IdleSphereStaticFrameNanos
            return@LaunchedEffect
        }
        while (isActive) {
            withFrameNanos { frameNanos = it }
        }
    }

    Canvas(
        modifier = modifier
            .clearAndSetSemantics { }
            .size(size),
    ) {
        val timeSeconds = frameNanos / 1_000_000_000.0
        drawIdleSphereHalo(haloColor)
        drawIdleSphereParticles(points, noise, timeSeconds, particleColor, particleRadiusScale)
    }
}

private data class SpherePoint(
    val x: Double,
    val y: Double,
    val z: Double,
)

private data class SphereParticle(
    val x: Float,
    val y: Float,
    val z: Double,
    val radius: Float,
    val alpha: Float,
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
    noise: SimplexNoise,
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
    val particles = ArrayList<SphereParticle>(points.size)
    val density = this.density

    points.forEach { point ->
        val noiseValue = noise.noise3D(
            point.x * 1.5 + timeSeconds * 0.33,
            point.y * 1.5 + timeSeconds * 0.33,
            point.z * 1.5 + timeSeconds * 0.33,
        )
        val noisyRadius = 1.0 + noiseValue * 0.06
        val x0 = point.x * noisyRadius
        val y0 = point.y * noisyRadius
        val z0 = point.z * noisyRadius

        val x1 = x0 * cosY + z0 * sinY
        val z1 = -x0 * sinY + z0 * cosY
        val y1 = y0 * cosX - z1 * sinX
        val z2 = y0 * sinX + z1 * cosX

        val perspective = focalLength / (distance - z2)
        val depth = ((z2 + 1.08) / 2.16).coerceIn(0.0, 1.0)
        particles += SphereParticle(
            x = (centerX + x1 * perspective * sphereScale).toFloat(),
            y = (centerY + y1 * perspective * sphereScale).toFloat(),
            z = z2,
            radius = lerp(0.7, 1.6, depth).toFloat() * density * particleRadiusScale,
            alpha = lerp(0.15, 0.95, depth).toFloat(),
        )
    }

    particles.sortedBy(SphereParticle::z).forEach { particle ->
        drawCircle(
            color = particleColor.copy(alpha = particle.alpha),
            radius = max(0.3f, particle.radius),
            center = Offset(particle.x, particle.y),
        )
    }
}

private fun makeIdleSpherePoints(count: Int): List<SpherePoint> {
    val result = ArrayList<SpherePoint>(count)
    val goldenAngle = PI * (3.0 - sqrt(5.0))
    repeat(count) { index ->
        val y = 1.0 - (index / (count - 1).toDouble()) * 2.0
        val radius = sqrt(max(0.0, 1.0 - y * y))
        val theta = index * goldenAngle
        result += SpherePoint(
            x = kotlin.math.cos(theta) * radius,
            y = y,
            z = kotlin.math.sin(theta) * radius,
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

private class SimplexNoise(seed: Int) {
    private val perm: IntArray
    private val grad3 = arrayOf(
        intArrayOf(1, 1, 0), intArrayOf(-1, 1, 0), intArrayOf(1, -1, 0), intArrayOf(-1, -1, 0),
        intArrayOf(1, 0, 1), intArrayOf(-1, 0, 1), intArrayOf(1, 0, -1), intArrayOf(-1, 0, -1),
        intArrayOf(0, 1, 1), intArrayOf(0, -1, 1), intArrayOf(0, 1, -1), intArrayOf(0, -1, -1),
    )

    init {
        val random = Mulberry32(seed)
        val source = IntArray(256) { it }
        for (i in 255 downTo 1) {
            val j = floor(random.next() * (i + 1)).toInt()
            val tmp = source[i]
            source[i] = source[j]
            source[j] = tmp
        }
        perm = IntArray(512) { source[it and 255] }
    }

    fun noise3D(xin: Double, yin: Double, zin: Double): Double {
        val f3 = 1.0 / 3.0
        val g3 = 1.0 / 6.0
        val s = (xin + yin + zin) * f3
        val i = floor(xin + s).toInt()
        val j = floor(yin + s).toInt()
        val k = floor(zin + s).toInt()
        val t = (i + j + k) * g3
        val x0 = xin - (i - t)
        val y0 = yin - (j - t)
        val z0 = zin - (k - t)

        val offsets = simplexOffsets(x0, y0, z0)
        val x1 = x0 - offsets.i1 + g3
        val y1 = y0 - offsets.j1 + g3
        val z1 = z0 - offsets.k1 + g3
        val x2 = x0 - offsets.i2 + 2 * g3
        val y2 = y0 - offsets.j2 + 2 * g3
        val z2 = z0 - offsets.k2 + 2 * g3
        val x3 = x0 - 1 + 3 * g3
        val y3 = y0 - 1 + 3 * g3
        val z3 = z0 - 1 + 3 * g3

        val ii = i and 255
        val jj = j and 255
        val kk = k and 255
        val gi0 = perm[ii + perm[jj + perm[kk]]] % 12
        val gi1 = perm[ii + offsets.i1 + perm[jj + offsets.j1 + perm[kk + offsets.k1]]] % 12
        val gi2 = perm[ii + offsets.i2 + perm[jj + offsets.j2 + perm[kk + offsets.k2]]] % 12
        val gi3 = perm[ii + 1 + perm[jj + 1 + perm[kk + 1]]] % 12

        return 32.0 * (
            cornerContribution(grad3[gi0], x0, y0, z0) +
                cornerContribution(grad3[gi1], x1, y1, z1) +
                cornerContribution(grad3[gi2], x2, y2, z2) +
                cornerContribution(grad3[gi3], x3, y3, z3)
            )
    }

    private fun cornerContribution(grad: IntArray, x: Double, y: Double, z: Double): Double {
        var t = 0.6 - x * x - y * y - z * z
        if (t < 0.0) return 0.0
        t *= t
        return t * t * (grad[0] * x + grad[1] * y + grad[2] * z)
    }
}

private data class SimplexOffsets(
    val i1: Int,
    val j1: Int,
    val k1: Int,
    val i2: Int,
    val j2: Int,
    val k2: Int,
)

private fun simplexOffsets(x0: Double, y0: Double, z0: Double): SimplexOffsets =
    if (x0 >= y0) {
        if (y0 >= z0) {
            SimplexOffsets(1, 0, 0, 1, 1, 0)
        } else if (x0 >= z0) {
            SimplexOffsets(1, 0, 0, 1, 0, 1)
        } else {
            SimplexOffsets(0, 0, 1, 1, 0, 1)
        }
    } else if (y0 < z0) {
        SimplexOffsets(0, 0, 1, 0, 1, 1)
    } else if (x0 < z0) {
        SimplexOffsets(0, 1, 0, 0, 1, 1)
    } else {
        SimplexOffsets(0, 1, 0, 1, 1, 0)
    }

private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

private const val IdleSpherePointCount = 3000
private const val IdleSphereSeed = 9427
private const val IdleSphereStaticFrameNanos = 600_000_000L
