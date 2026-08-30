/*
 * Native Jetpack Compose port of thinking-orbs by Jakub Antalik.
 * Original project: https://github.com/Jakubantalik/thinking-orbs
 * Used under the MIT License. Keep the original project's LICENSE notice
 * when redistributing this file.
 */

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class ThinkingOrbState {
    Working,
    Solving,
    Composing,
}

enum class ThinkingOrbSize(val value: Dp) {
    Inline(20.dp),
    Avatar(64.dp),
}

/**
 * A native Compose version of the "working", "solving", and "composing"
 * thinking-orbs.
 *
 * [primaryColor] and [secondaryColor] default to the product palette. The orb
 * uses motion, dot size, and depth as well as color, so its state remains clear
 * for users who cannot distinguish the two hues.
 */
@Composable
fun ThinkingOrb(
    state: ThinkingOrbState = ThinkingOrbState.Working,
    modifier: Modifier = Modifier,
    size: ThinkingOrbSize = ThinkingOrbSize.Avatar,
    diameter: Dp = size.value,
    primaryColor: Color = Color(0xFF0074FE),
    secondaryColor: Color = Color(0xFFFDBC22),
    speed: Float = 1f,
    paused: Boolean = false,
    contentDescription: String = when (state) {
        ThinkingOrbState.Working -> "Working"
        ThinkingOrbState.Solving -> "Solving"
        ThinkingOrbState.Composing -> "Composing"
    },
) {
    val motionEnabled = remember {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
    }
    var frameNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(paused, motionEnabled) {
        if (paused || !motionEnabled) {
            frameNanos = STATIC_FRAME_NANOS
            return@LaunchedEffect
        }
        while (isActive) {
            withFrameNanos { frameNanos = it }
        }
    }

    val baseSpeed = when (state) {
        ThinkingOrbState.Working ->
            if (size == ThinkingOrbSize.Avatar) 1.885f else 3.9f
        ThinkingOrbState.Solving ->
            if (size == ThinkingOrbSize.Avatar) 1.82f else 1.95f
        ThinkingOrbState.Composing ->
            if (size == ThinkingOrbSize.Avatar) 2.34f else 3.12f
    }
    val timeSeconds = (frameNanos / 1_000_000_000.0) * baseSpeed * speed

    Canvas(
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
            .then(Modifier.size(diameter)),
    ) {
        val logicalSize = this.size.minDimension
        val points = when (state) {
            ThinkingOrbState.Working -> workingDots(logicalSize, timeSeconds)
            ThinkingOrbState.Solving -> solvingDots(logicalSize, timeSeconds)
            ThinkingOrbState.Composing -> composingDots(logicalSize, timeSeconds)
        }
        paintDots(points, logicalSize, primaryColor, secondaryColor)
    }
}

private data class Dot(
    val x: Double,
    val y: Double,
    val z: Double,
    val radius: Double,
    val colorMix: Float,
    val alpha: Float = 1f,
)

private data class Projected(val x: Double, val y: Double, val z: Double)

private fun projector(
    yaw: Double,
    tilt: Double,
    centerX: Double,
    centerY: Double,
): (Double, Double, Double) -> Projected {
    val sinTilt = sin(tilt)
    val cosTilt = cos(tilt)
    val sinYaw = sin(yaw)
    val cosYaw = cos(yaw)
    return { x, y, z ->
        val x1 = x * cosYaw + z * sinYaw
        val z1 = -x * sinYaw + z * cosYaw
        val y1 = y * cosTilt - z1 * sinTilt
        val z2 = y * sinTilt + z1 * cosTilt
        Projected(centerX + x1, centerY - y1, z2)
    }
}

private fun workingDots(size: Float, time: Double): List<Dot> {
    val center = size / 2.0
    val orbRadius = center * 0.82
    val project = projector(time * 0.12, 0.3, center, center)
    val radiusScale = (size / 300.0).pow(0.6)

    val large = size >= 40f
    val orbitCount = if (large) 12 else 3
    val ghostCount = if (large) 40 else 10
    val dotScale = if (large) 1.0 else 2.4
    val dots = ArrayList<Dot>(orbitCount * (ghostCount + 3))

    repeat(orbitCount) { orbit ->
        val h1 = hash(orbit.toDouble(), 1.7)
        val h2 = hash(orbit.toDouble(), 5.2)
        val h3 = hash(orbit.toDouble(), 8.9)
        val orbitRadius = orbRadius * (0.45 + 0.52 * h1)
        val theta = h1 * 2 * PI
        val phi = acos(2 * h2 - 1)
        val nx = sin(phi) * cos(theta)
        val ny = cos(phi)
        val nz = sin(phi) * sin(theta)
        var ux = -ny
        var uy = nx
        val uz = 0.0
        val uLength = max(1e-6, sqrt(ux * ux + uy * uy))
        ux /= uLength
        uy /= uLength
        val vx = ny * uz - nz * uy
        val vy = nz * ux - nx * uz
        val vz = nx * uy - ny * ux
        val orbitSpeed = (0.25 + 0.55 * h3) * if (h3 > 0.5) 1 else -1

        repeat(ghostCount) { index ->
            val angle = index.toDouble() / ghostCount * 2 * PI
            val point = project(
                (ux * cos(angle) + vx * sin(angle)) * orbitRadius,
                (uy * cos(angle) + vy * sin(angle)) * orbitRadius,
                (uz * cos(angle) + vz * sin(angle)) * orbitRadius,
            )
            val depth = ((point.z / orbitRadius + 1) / 2).toFloat()
            dots += Dot(
                point.x,
                point.y,
                point.z,
                0.9 * dotScale * radiusScale,
                colorMix = depth * 0.18f,
                alpha = 0.5f * (0.4f + 0.6f * depth),
            )
        }

        repeat(3) { particle ->
            val angle = time * orbitSpeed + particle / 3.0 * 2 * PI + h2 * 6
            val point = project(
                (ux * cos(angle) + vx * sin(angle)) * orbitRadius,
                (uy * cos(angle) + vy * sin(angle)) * orbitRadius,
                (uz * cos(angle) + vz * sin(angle)) * orbitRadius,
            )
            val depth = ((point.z / orbitRadius + 1) / 2).toFloat()
            dots += Dot(
                point.x,
                point.y,
                point.z,
                (1.2 + 1.6 * depth) * dotScale * radiusScale,
                colorMix = 0.35f + 0.65f * depth,
            )
        }
    }
    return dots
}

private data class Move(
    val axis: Int,
    val lo: Double,
    val hi: Double,
    val angle: Double,
)

private data class SolveCycle(
    val amount: DoubleArray,
    val active: Int,
)

private fun solvingDots(size: Float, time: Double): List<Dot> {
    val center = size / 2.0
    val orbRadius = center * 0.82
    val project = projector(time * 0.55, 0.35 + 0.1 * sin(time * 0.9), center, center)
    val radiusScale = (size / 300.0).pow(0.6)
    val large = size >= 40f
    val latRings = if (large) 8 else 4
    val lonDensity = if (large) 14 else 8
    val dotScale = if (large) 1.05 else 1.9
    val moveCount = 14
    val moves = makeMoves(moveCount)
    val cycle = solveCycle(time, moveCount, slotDuration = 0.42, restDuration = 1.2)
    val dots = ArrayList<Dot>((latRings + 1) * lonDensity)

    for (latIndex in 0..latRings) {
        val lat = -PI / 2 + (latIndex.toDouble() / latRings) * PI
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val lonCount = max(1, (kotlin.math.abs(cosLat) * lonDensity).roundToInt())
        repeat(lonCount) { lonIndex ->
            val lon = (lonIndex.toDouble() / lonCount) * 2 * PI
            val moved = applyMoves(
                cosLat * cos(lon),
                sinLat,
                cosLat * sin(lon),
                moves,
                cycle,
            )
            val point = project(
                moved.x * orbRadius,
                moved.y * orbRadius,
                moved.z * orbRadius,
            )
            val depth = ((point.z / orbRadius + 1) / 2).toFloat().coerceIn(0f, 1f)
            dots += Dot(
                point.x,
                point.y,
                point.z,
                (0.6 + 1.7 * depth + if (moved.inActive) 0.3 else 0.0) *
                    dotScale *
                    radiusScale,
                colorMix = 0f,
                alpha = if (moved.inActive) 1f else 0.58f + 0.42f * depth,
            )
        }
    }
    return dots
}

private fun composingDots(size: Float, time: Double): List<Dot> {
    val center = size / 2.0
    val orbRadius = center * 0.78
    val project = projector(0.0, 0.3, center, center)
    val radiusScale = (size / 300.0).pow(0.6)
    val large = size >= 40f
    val ghostCount = if (large) 38 else 8
    val baseLanes = if (large) 3 else 2
    val segments = if (large) 44 else 20
    val lanes = max(1, (baseLanes * if (large) 3.9 else 4.94).roundToInt())
    val dotScale = if (large) 0.85 else 1.073
    val dots = ArrayList<Dot>(ghostCount + lanes * segments)

    repeat(ghostCount) { index ->
        val direction = fibonacciDirection(index, ghostCount)
        val point = project(
            direction[0] * orbRadius,
            direction[1] * orbRadius,
            direction[2] * orbRadius,
        )
        val depth = ((point.z / orbRadius + 1) / 2).toFloat()
        dots += Dot(
            point.x,
            point.y,
            point.z,
            0.8 * radiusScale,
            colorMix = depth * 0.15f,
            alpha = 0.1f + 0.22f * depth,
        )
    }

    // The original composing preset freezes the band's 3D tumble, leaving
    // the two traveling waves to animate a fixed sash.
    val tiltAngle = 0.55
    val ux = 1.0
    val uy = 0.0
    val uz = 0.0
    val vx = -uz * sin(tiltAngle)
    val vy = cos(tiltAngle)
    val vz = ux * sin(tiltAngle)
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx

    repeat(lanes) { lane ->
        val laneOffset = (lane - (lanes - 1) / 2.0) * 0.075
        val edge = kotlin.math.abs(lane - (lanes - 1) / 2.0) /
            max(1.0, (lanes - 1) / 2.0)
        repeat(segments) { segment ->
            val angle = segment.toDouble() / segments * 2 * PI
            val wobble =
                0.16 * sin(angle * 3 - time * 1.7 + lane * 0.22) +
                    0.07 * sin(angle * 5 + time * 1.1)
            val offset = laneOffset + wobble
            val x = ux * cos(angle) + vx * sin(angle) + nx * offset
            val y = uy * cos(angle) + vy * sin(angle) + ny * offset
            val z = uz * cos(angle) + vz * sin(angle) + nz * offset
            val length = sqrt(x * x + y * y + z * z)
            val point = project(
                x / length * orbRadius,
                y / length * orbRadius,
                z / length * orbRadius,
            )
            val depth = ((point.z / orbRadius + 1) / 2).toFloat()
            dots += Dot(
                point.x,
                point.y,
                point.z,
                (1.1 + 1.7 * depth) * (1 - 0.25 * edge) * dotScale * radiusScale,
                colorMix = (0.2f + 0.8f * depth).coerceIn(0f, 1f),
                alpha = 0.4f + 0.6f * depth,
            )
        }
    }
    return dots
}

private data class MovedPoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val inActive: Boolean,
)

private fun solveCycle(
    time: Double,
    count: Int,
    slotDuration: Double,
    restDuration: Double,
): SolveCycle {
    val cycleDuration = 2 * count * slotDuration + restDuration
    val cycleTime = ((time % cycleDuration) + cycleDuration) % cycleDuration
    val amount = DoubleArray(count)
    var active = -1

    if (cycleTime < 2 * count * slotDuration) {
        val slot = floor(cycleTime / slotDuration).toInt()
        val progress = (cycleTime - slot * slotDuration) / slotDuration
        val clamped = (progress / 0.7).coerceAtMost(1.0)
        val eased = 1 - (1 - clamped).pow(3)
        if (slot < count) {
            for (index in 0 until slot) amount[index] = 1.0
            amount[slot] = eased
            active = slot
        } else {
            val undoIndex = 2 * count - 1 - slot
            for (index in 0 until undoIndex) amount[index] = 1.0
            amount[undoIndex] = 1 - eased
            active = undoIndex
        }
    }

    return SolveCycle(amount, active)
}

private fun applyMoves(
    startX: Double,
    startY: Double,
    startZ: Double,
    moves: List<Move>,
    cycle: SolveCycle,
): MovedPoint {
    var x = startX
    var y = startY
    var z = startZ
    var inActive = false

    moves.forEachIndexed { index, move ->
        if (cycle.amount[index] <= 0) return@forEachIndexed
        val coord = when (move.axis) {
            0 -> x
            1 -> y
            else -> z
        }
        if (coord < move.lo || coord >= move.hi) return@forEachIndexed
        if (index == cycle.active) inActive = true

        val angle = move.angle * cycle.amount[index]
        val cosAngle = cos(angle)
        val sinAngle = sin(angle)
        when (move.axis) {
            0 -> {
                val y2 = y * cosAngle - z * sinAngle
                z = y * sinAngle + z * cosAngle
                y = y2
            }
            1 -> {
                val x2 = x * cosAngle + z * sinAngle
                z = -x * sinAngle + z * cosAngle
                x = x2
            }
            else -> {
                val x2 = x * cosAngle - y * sinAngle
                y = x * sinAngle + y * cosAngle
                x = x2
            }
        }
    }

    return MovedPoint(x, y, z, inActive)
}

private fun makeMoves(count: Int): List<Move> {
    return List(count) { index ->
        val i = index.toDouble()
        val axis = minOf(2, floor(hash(i, 2.3) * 3).toInt())
        val lo = -1.0 + 0.5 * minOf(3, floor(hash(i, 5.9) * 4).toInt())
        val direction = if (hash(i, 7.7) < 0.5) 1 else -1
        Move(axis, lo, lo + 0.5, direction * PI / 2)
    }
}

private fun DrawScope.paintDots(
    dots: List<Dot>,
    logicalSize: Float,
    primary: Color,
    secondary: Color,
) {
    val scale = this.size.minDimension / logicalSize
    dots.sortedBy(Dot::z).forEach { dot ->
        if (dot.alpha < 0.02f) return@forEach
        drawCircle(
            color = lerp(primary, secondary, dot.colorMix).copy(alpha = dot.alpha),
            radius = max(0.3f, dot.radius.toFloat()) * scale,
            center = Offset(dot.x.toFloat() * scale, dot.y.toFloat() * scale),
        )
    }
}

private fun hash(a: Double, b: Double): Double {
    val value = sin(a * 12.9898 + b * 78.233) * 43758.5453
    return value - floor(value)
}

private fun fibonacciDirection(index: Int, count: Int): DoubleArray {
    val golden = PI * (3 - sqrt(5.0))
    val y = 1 - (2 * (index + 0.5)) / count
    val radius = sqrt(1 - y * y)
    val angle = index * golden
    return doubleArrayOf(radius * cos(angle), y, radius * sin(angle))
}

private const val STATIC_FRAME_NANOS = 600_000_000L
