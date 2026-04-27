package com.smarttraffic.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun TrafficLoading(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "trafficLoading")
    val color = MaterialTheme.colorScheme.primary
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "progress",
    )

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.size(42.dp)) {
            drawArc(
                color = color,
                startAngle = progress.value,
                sweepAngle = 260f,
                useCenter = false,
                style = Stroke(width = 8f, cap = StrokeCap.Round),
            )
        }
    }
}

