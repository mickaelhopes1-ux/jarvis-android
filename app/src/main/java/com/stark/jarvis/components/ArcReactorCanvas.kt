package com.stark.jarvis.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.stark.jarvis.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ArcReactorCanvas(
    modifier: Modifier = Modifier,
    isListening: Boolean = false,
    isSpeaking: Boolean = false,
    isAlert: Boolean = false,
    sizeDp: Int = 220
) {
    val infiniteTransition = rememberInfiniteTransition(label = "arc_reactor")

    // Slow clockwise rotation
    val rotationClockwise by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation_cw"
    )

    // Counter clockwise rotation for inner ring
    val rotationCounter by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation_ccw"
    )

    // Pulse scale for core
    val corePulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isSpeaking) 600 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_pulse"
    )

    val primaryColor = if (isAlert) RoseSecurity else if (isListening) AmberAlert else CyanPrimary
    val glowColor = if (isAlert) AmberGlow else CyanGlow

    Box(
        modifier = modifier.size(sizeDp.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(sizeDp.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.width / 2f * 0.92f

            // Outer Ambient Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.25f), Color.Transparent),
                    center = center,
                    radius = maxRadius
                ),
                radius = maxRadius,
                center = center
            )

            // Outer segmented ring (Clockwise)
            rotate(degrees = rotationClockwise, pivot = center) {
                drawCircle(
                    color = primaryColor.copy(alpha = 0.7f),
                    radius = maxRadius * 0.88f,
                    center = center,
                    style = Stroke(
                        width = 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 15f, 10f, 15f), 0f),
                        cap = StrokeCap.Round
                    )
                )

                // 8 Outer tactical nodes
                for (i in 0 until 8) {
                    val angle = (i * (360f / 8)) * (Math.PI / 180f)
                    val nodeRadius = maxRadius * 0.94f
                    val nodeX = center.x + (nodeRadius * cos(angle)).toFloat()
                    val nodeY = center.y + (nodeRadius * sin(angle)).toFloat()
                    drawCircle(
                        color = glowColor,
                        radius = 4f,
                        center = Offset(nodeX, nodeY)
                    )
                }
            }

            // Middle ring with inverted rotation (Counter-Clockwise)
            rotate(degrees = rotationCounter, pivot = center) {
                drawCircle(
                    color = primaryColor.copy(alpha = 0.5f),
                    radius = maxRadius * 0.68f,
                    center = center,
                    style = Stroke(
                        width = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 20f), 0f)
                    )
                )

                // Copper induction coils (10 segments)
                for (i in 0 until 10) {
                    val angle = (i * (360f / 10)) * (Math.PI / 180f)
                    val rInner = maxRadius * 0.52f
                    val rOuter = maxRadius * 0.65f
                    val start = Offset(
                        center.x + (rInner * cos(angle)).toFloat(),
                        center.y + (rInner * sin(angle)).toFloat()
                    )
                    val end = Offset(
                        center.x + (rOuter * cos(angle)).toFloat(),
                        center.y + (rOuter * sin(angle)).toFloat()
                    )
                    drawLine(
                        color = AmberAlert.copy(alpha = 0.65f),
                        start = start,
                        end = end,
                        strokeWidth = 5f,
                        cap = StrokeCap.Round
                    )
                }
            }

            // Inner containment ring
            drawCircle(
                color = primaryColor,
                radius = maxRadius * 0.45f,
                center = center,
                style = Stroke(width = 2.5f)
            )

            // Inner pulsating Core (Reactor Heart)
            val currentCoreRadius = (maxRadius * 0.35f) * corePulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White,
                        primaryColor,
                        primaryColor.copy(alpha = 0.4f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = currentCoreRadius
                ),
                radius = currentCoreRadius,
                center = center
            )

            // Stark Triangle Aperture inside core
            val triangleSize = currentCoreRadius * 0.55f
            val trianglePath = Path().apply {
                moveTo(center.x, center.y - triangleSize)
                lineTo(center.x + (triangleSize * 0.866f), center.y + (triangleSize * 0.5f))
                lineTo(center.x - (triangleSize * 0.866f), center.y + (triangleSize * 0.5f))
                close()
            }
            drawPath(
                path = trianglePath,
                color = ObsidianBackground.copy(alpha = 0.85f)
            )
            drawPath(
                path = trianglePath,
                color = primaryColor,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )
        }
    }
}
