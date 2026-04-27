package com.smarttraffic.app.ui.screens.leaderboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smarttraffic.core_engine.domain.model.LeaderboardEntry
import com.smarttraffic.designsystem.components.GlassCard
import com.smarttraffic.designsystem.components.PremiumTopBar
import kotlin.math.roundToInt

@Composable
fun LeaderboardScreen(
    onBack: () -> Unit,
    viewModel: LeaderboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF091A2F),
                        Color(0xFF0A2D4A),
                        MaterialTheme.colorScheme.background,
                    )
                )
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PremiumTopBar(
            title = "Leaderboard",
            subtitle = "Live ranking from validated reports + GPS quality",
            actions = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
        )

        if (state.loading) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                    Text("Loading live leaderboard...")
                }
            }
            return@Column
        }

        if (state.entries.isEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No leaderboard data yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Rankings appear after users submit validated reports or GPS contributions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    )
                }
            }
            return@Column
        }

        state.myEntry
            ?.takeIf { it.hasRealContribution() }
            ?.let { mine ->
                MyImpactCard(entry = mine)
            }

        if (state.topThree.isNotEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Top Drivers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.topThree.forEach { entry ->
                            PodiumItem(
                                modifier = Modifier.weight(1f),
                                entry = entry,
                                currentUserId = state.currentUserId,
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(state.entries, key = { it.userId }) { entry ->
                val progress by animateFloatAsState(
                    targetValue = (entry.score / 1800f).coerceIn(0f, 1f),
                    label = "rankProgress",
                )
                val accent = rankColor(entry.rank)
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "#${entry.rank}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    displayName(
                                        userId = entry.userId,
                                        currentUserId = state.currentUserId,
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Trust ${(entry.trustScore * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = accent,
                                )
                            }
                            Text(
                                "${entry.score.roundToInt()} pts",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = accent,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatTag(icon = Icons.Rounded.Verified, text = "${entry.verifiedReports} verified")
                            StatTag(icon = Icons.Rounded.GpsFixed, text = "${entry.gpsContributions} GPS")
                            StatTag(icon = Icons.Rounded.Bolt, text = "${entry.streakDays}d streak")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyImpactCard(entry: LeaderboardEntry) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your Impact", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatTag(icon = Icons.Rounded.MilitaryTech, text = "Rank #${entry.rank}")
                StatTag(icon = Icons.Rounded.Verified, text = "${entry.verifiedReports} verified")
                StatTag(icon = Icons.Rounded.GpsFixed, text = "${entry.gpsContributions} GPS")
            }
            Text(
                "${entry.score.roundToInt()} points | Trust ${(entry.trustScore * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF67E8F9),
            )
            Text(
                "Score updates in real time from report validation confidence and GPS ingest quality.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
            )
        }
    }
}

@Composable
private fun PodiumItem(
    modifier: Modifier = Modifier,
    entry: LeaderboardEntry,
    currentUserId: String?,
) {
    val accent = rankColor(entry.rank)
    val height = when (entry.rank) {
        1 -> 116.dp
        2 -> 96.dp
        else -> 84.dp
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.6f), accent.copy(alpha = 0.25f)))),
        )
        Text(
            text = displayName(userId = entry.userId, currentUserId = currentUserId),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
        )
        Text(
            text = "${entry.score.roundToInt()} pts",
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatTag(
    icon: ImageVector,
    text: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

private fun displayName(userId: String, currentUserId: String?): String {
    return if (currentUserId != null && userId == currentUserId) {
        "You"
    } else {
        "Driver ${userId.takeLast(4).uppercase()}"
    }
}

private fun rankColor(rank: Int): Color {
    return when (rank) {
        1 -> Color(0xFFFACC15)
        2 -> Color(0xFFCBD5E1)
        3 -> Color(0xFFFB923C)
        else -> Color(0xFF22D3EE)
    }
}

private fun LeaderboardEntry.hasRealContribution(): Boolean {
    return score > 0f || verifiedReports > 0 || gpsContributions > 0
}
