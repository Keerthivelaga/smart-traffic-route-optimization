package com.smarttraffic.app.ui.screens.analytics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smarttraffic.designsystem.components.GlassCard
import com.smarttraffic.designsystem.components.PremiumTopBar

@Composable
fun AnalyticsScreen(
    onBack: () -> Unit,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress = remember { Animatable(0f) }
    val trendColor = congestionColor(state.predictedCongestion)
    val distributionBackground = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(800))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PremiumTopBar(
            title = "Traffic Analytics",
            subtitle = "Rolling congestion intelligence",
            actions = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
        )

        GlassCard(modifier = Modifier.fillMaxWidth().height(220.dp)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Congestion Trend (Model)", style = MaterialTheme.typography.titleMedium)
                Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val points = state.congestionTrend.ifEmpty { listOf(state.predictedCongestion) }
                    val path = Path()
                    points.forEachIndexed { index, value ->
                        val denominator = (points.size - 1).coerceAtLeast(1)
                        val x = size.width * (index / denominator.toFloat())
                        val y = size.height * (1f - value * progress.value)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = trendColor,
                        style = Stroke(width = 10f, cap = StrokeCap.Round),
                    )
                }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Current ${(state.snapshot.congestionScore * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = congestionColor(state.snapshot.congestionScore),
                )
                Text(
                    text = "Predicted ${(state.predictedCongestion * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = congestionColor(state.predictedCongestion),
                )
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth().height(180.dp)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Speed Distribution", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    state.speedDistribution.forEach { value ->
                        Canvas(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp)) {
                            drawRoundRect(
                                color = distributionBackground,
                                size = Size(size.width, size.height),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f, 20f),
                            )
                            drawRoundRect(
                                color = congestionColor(1f - value),
                                topLeft = Offset(0f, size.height * (1f - value * progress.value)),
                                size = Size(size.width, size.height * value * progress.value),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f, 20f),
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = if (state.routeActive) {
                "Route: ${state.routeTitle.ifBlank { "${state.origin} -> ${state.destination}" }} | ETA ${state.etaMinutes} min | Progress ${(state.progress * 100).toInt()}% | Predicted congestion ${(state.predictedCongestion * 100).toInt()}%"
            } else {
                "No active route session yet. Start navigation to receive route-specific analytics."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
}

private fun congestionColor(score: Float): Color {
    val safe = score.coerceIn(0f, 1f)
    return when {
        safe < 0.34f -> Color(0xFF22C55E)
        safe < 0.67f -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }
}

