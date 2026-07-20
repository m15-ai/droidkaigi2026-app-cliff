package com.m15.cliff.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.*
import androidx.compose.material3.MaterialTheme

// Orange palette — warm amber/bronze family. Overlapping translucent orbs in
// these shades blend additively into a soft, glowing, lava-lamp-like core.
private val Amber = Color(0xFFFFBF00)
private val Apricot = Color(0xFFFBCEB1)
private val Bisque = Color(0xFFF2D2BD)
private val BrightOrange = Color(0xFFFFAC1C)
private val Bronze = Color(0xFFCD7F32)
private val Buff = Color(0xFFDAA06D)
private val BurntOrange = Color(0xFFCC5500)

/**
 * One soft orb in the stack.
 *
 * @param color        shade from the orange palette
 * @param radiusFactor size relative to the base radius
 * @param orbitFactor  how far it drifts from the shared center, relative to base radius
 * @param orbitSpeed   angular speed multiplier for its slow drift
 * @param orbitPhase   starting angle offset so orbs don't bunch up
 * @param wobbleSeed   phase offset for the gentle edge wobble
 * @param alpha        peak opacity of its core
 */
private data class Orb(
    val color: Color,
    val radiusFactor: Float,
    val orbitFactor: Float,
    val orbitSpeed: Float,
    val orbitPhase: Float,
    val wobbleSeed: Float,
    val alpha: Float,
)

// Deeper shades form the base mass; brighter ones float on top as highlights.
private val ORBS = listOf(
    Orb(BurntOrange, radiusFactor = 1.18f, orbitFactor = 0.10f, orbitSpeed = 0.6f, orbitPhase = 0.0f, wobbleSeed = 0.0f, alpha = 0.42f),
    Orb(Bronze, radiusFactor = 1.02f, orbitFactor = 0.20f, orbitSpeed = -0.9f, orbitPhase = 1.1f, wobbleSeed = 1.7f, alpha = 0.40f),
    Orb(Buff, radiusFactor = 0.88f, orbitFactor = 0.30f, orbitSpeed = 1.2f, orbitPhase = 2.4f, wobbleSeed = 3.0f, alpha = 0.36f),
    Orb(BrightOrange, radiusFactor = 0.80f, orbitFactor = 0.26f, orbitSpeed = -1.5f, orbitPhase = 3.7f, wobbleSeed = 4.2f, alpha = 0.40f),
    Orb(Amber, radiusFactor = 0.66f, orbitFactor = 0.34f, orbitSpeed = 1.8f, orbitPhase = 4.9f, wobbleSeed = 5.5f, alpha = 0.38f),
    Orb(Apricot, radiusFactor = 0.52f, orbitFactor = 0.40f, orbitSpeed = -2.2f, orbitPhase = 0.6f, wobbleSeed = 6.1f, alpha = 0.34f),
    Orb(Bisque, radiusFactor = 0.44f, orbitFactor = 0.46f, orbitSpeed = 2.6f, orbitPhase = 5.8f, wobbleSeed = 2.3f, alpha = 0.30f),
)

@Composable
fun AudioBlobVisualizer(
    level: Float,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    accent2: Color = Color(0xFF666666),
) {
    var heldPeak by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(level) {
        // Fast attack, slow-ish release so peaks pop then settle.
        heldPeak = max(level, heldPeak * 0.88f)
    }
    // Snappy: bias hard toward the live level, very short tween for an almost
    // instantaneous attack while still smoothing out per-frame jitter.
    val smooth by animateFloatAsState(
        targetValue = (level * 0.88f + heldPeak * 0.12f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 16, easing = LinearEasing),
        label = "smoothLevel"
    )

    // Orbit angle is integrated over time at an amplitude-dependent rate, so the
    // orbs revolve faster as the voice gets louder while speed changes stay
    // smooth (no position jumps). Idle ≈ 0.05 rev/s, loud ≈ 0.5 rev/s.
    var spin by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000.0).toFloat()
                    val revPerSec = 0.05f + 0.6f * smooth
                    spin += dt * revPerSec * 2f * PI.toFloat()
                }
                last = now
            }
        }
    }

    val t = rememberInfiniteTransition(label = "blobTime")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val shimmer by t.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)

        val minDim = size.minDimension
        val baseR = minDim * 0.38f
        val punch = smooth * smooth
        // The whole stack breathes with the audio; orbs also spread apart a
        // little when loud so the colored fringes separate and read distinctly.
        val r = baseR * (1f + 0.85f * punch)
        val spread = 1f + 0.55f * smooth

        // Warm ambient haze behind everything.
        drawHaze(center, minDim, shimmer)

        // Overlapping orbs, additively blended so where they cross they brighten
        // and the orange shades mix toward amber/gold.
        for (orb in ORBS) {
            drawOrb(
                base = center,
                baseRadius = r,
                spin = spin,
                phase = phase,
                level = smooth,
                spread = spread,
                shimmer = shimmer,
                orb = orb
            )
        }

        // Faint accent bloom at the very center to tie the stack together.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.06f + 0.10f * punch),
                    Color.Transparent
                ),
                center = center,
                radius = r * 0.9f
            ),
            radius = r * 0.9f,
            center = center,
            blendMode = BlendMode.Plus
        )
    }
}

private fun DrawScope.drawHaze(center: Offset, minDim: Float, shimmer: Float) {
    val fogR = minDim * 0.66f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                BurntOrange.copy(alpha = 0.07f * shimmer),
                Bronze.copy(alpha = 0.04f * shimmer),
                Color.Transparent
            ),
            center = center,
            radius = fogR
        ),
        radius = fogR,
        center = center
    )
}

private fun DrawScope.drawOrb(
    base: Offset,
    baseRadius: Float,
    spin: Float,
    phase: Float,
    level: Float,
    spread: Float,
    shimmer: Float,
    orb: Orb,
) {
    val angle = spin * orb.orbitSpeed + orb.orbitPhase
    val drift = baseRadius * orb.orbitFactor * spread
    val c = Offset(
        base.x + drift * cos(angle),
        base.y + drift * sin(angle)
    )

    val radius = baseRadius * orb.radiusFactor
    // Gentle, rounded wobble — soft organic edge, no spikes.
    val path = orbPath(c, radius, phase, orb.wobbleSeed, level)

    val coreAlpha = orb.alpha * (0.6f + 0.4f * shimmer)
    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(
                orb.color.copy(alpha = coreAlpha),
                orb.color.copy(alpha = coreAlpha * 0.55f),
                Color.Transparent
            ),
            center = c,
            radius = radius * 1.35f
        ),
        blendMode = BlendMode.Plus
    )
}

private fun orbPath(
    center: Offset,
    radius: Float,
    phase: Float,
    seed: Float,
    level: Float,
): Path {
    val path = Path()
    val cx = center.x
    val cy = center.y
    val points = 96

    // Only low harmonics → smooth, slow lobes. Wobble grows modestly with level.
    val k1 = 2.0f
    val k2 = 3.0f
    val strength = 0.05f + 0.07f * level

    for (i in 0..points) {
        val a = (i / points.toFloat()) * (2f * PI.toFloat())
        val wobble =
            sin(a * k1 + phase + seed) * 0.65f +
                sin(a * k2 - phase * 0.7f + seed) * 0.35f
        val rr = radius * (1f + strength * wobble)
        val x = cx + rr * cos(a)
        val y = cy + rr * sin(a)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}