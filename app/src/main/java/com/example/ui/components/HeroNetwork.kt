package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.ui.theme.DarkAccent
import com.example.ui.theme.DarkAccentVariant
import com.example.ui.theme.DarkMuted
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private data class Node(
    val xRel: Float,
    val yRel: Float,
    val radius: Float,
    val bright: Boolean,
    val phase: Float
)

@Composable
fun HeroNetwork(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "hero_anim")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )

    // Precalculate deterministic nodes
    val nodes = remember {
        val list = mutableListOf<Node>()
        for (i in 0 until 38) {
            val seed1 = Math.sin((i + 1) * 9301.0 + 49297.0) * 233280.0
            val rand1 = (seed1 - Math.floor(seed1)).toFloat()
            val seed2 = Math.sin((i + 100) * 9301.0 + 49297.0) * 233280.0
            val rand2 = (seed2 - Math.floor(seed2)).toFloat()
            val seed3 = Math.sin((i + 200) * 9301.0 + 49297.0) * 233280.0
            val rand3 = (seed3 - Math.floor(seed3)).toFloat()

            val angle = rand1 * 2f * PI.toFloat()
            val r = 0.3f + rand2 * 0.65f
            val x = 0.5f + cos(angle) * r * 0.45f
            val y = 0.5f + sin(angle) * r * 0.42f

            // Filter out nodes too close to center
            if (hypot(x - 0.5f, y - 0.5f) > 0.16f) {
                list.add(
                    Node(
                        xRel = x,
                        yRel = y,
                        radius = 2.5f + rand3 * 4f,
                        bright = rand1 > 0.75f,
                        phase = rand2
                    )
                )
            }
        }
        list
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Center glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        DarkAccent.copy(alpha = 0.35f),
                        DarkAccent.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = Offset(w / 2f, h / 2f),
                    radius = w * 0.4f
                ),
                radius = w * 0.4f,
                center = Offset(w / 2f, h / 2f)
            )

            // Connect nearby nodes with edges
            for (i in nodes.indices) {
                val a = nodes[i]
                val ax = a.xRel * w
                val ay = a.yRel * h
                for (j in i + 1 until nodes.size) {
                    val b = nodes[j]
                    val bx = b.xRel * w
                    val by = b.yRel * h
                    val dist = hypot(ax - bx, ay - by)
                    if (dist < w * 0.22f) {
                        drawLine(
                            color = DarkAccent.copy(alpha = 0.22f),
                            start = Offset(ax, ay),
                            end = Offset(bx, by),
                            strokeWidth = 1f
                        )
                    }
                }
            }

            // Draw animated nodes
            for (node in nodes) {
                val nx = node.xRel * w
                val ny = node.yRel * h
                val breathing = sin((pulse + node.phase) * 2 * PI.toFloat()) * 0.3f + 1f
                val r = node.radius * breathing

                // Outer halo
                drawCircle(
                    color = DarkAccent.copy(alpha = 0.15f),
                    radius = r * 2.5f,
                    center = Offset(nx, ny)
                )
                // Core
                drawCircle(
                    color = if (node.bright) Color(0xFFF0F4FF) else DarkAccent,
                    radius = r,
                    center = Offset(nx, ny)
                )
            }

            // Left audio wave
            val leftWave = Path().apply {
                moveTo(24f, h / 2f)
                val segments = 5
                val segWidth = (w * 0.25f) / segments
                for (s in 0 until segments) {
                    val peak = if (s % 2 == 0) -16f else 16f
                    relativeQuadraticTo(
                        segWidth / 2f,
                        peak * sin((pulse * 3 + s) * PI.toFloat()),
                        segWidth,
                        0f
                    )
                }
            }
            drawPath(
                path = leftWave,
                color = DarkMuted.copy(alpha = 0.6f),
                style = Stroke(width = 2.5f)
            )

            // Right audio wave
            val rightWave = Path().apply {
                val startX = w - 24f - (w * 0.25f)
                moveTo(startX, h / 2f)
                val segments = 5
                val segWidth = (w * 0.25f) / segments
                for (s in 0 until segments) {
                    val peak = if (s % 2 == 0) 18f else -18f
                    relativeQuadraticTo(
                        segWidth / 2f,
                        peak * cos((pulse * 3 + s) * PI.toFloat()),
                        segWidth,
                        0f
                    )
                }
            }
            drawPath(
                path = rightWave,
                color = DarkAccentVariant,
                style = Stroke(width = 2.8f)
            )
        }

        // Left Mic Icon
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Input audio",
                tint = DarkMuted,
                modifier = Modifier.size(24.dp)
            )
        }

        // Right Speaker Icon
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
        ) {
            @Suppress("DEPRECATION")
            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = "Dubbed audio output",
                tint = DarkAccent,
                modifier = Modifier.size(24.dp)
            )
        }

        // Center Floating Hero Emblem
        Box(
            modifier = Modifier
                .size(76.dp)
                .shadow(elevation = 20.dp, shape = CircleShape, spotColor = DarkAccent)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF23284B),
                            Color(0xFF0F111E)
                        )
                    )
                )
                .border(2.dp, DarkAccent.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.RecordVoiceOver,
                contentDescription = "AI Dubber",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}
