package com.smarttraffic.app.ui.screens.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.smarttraffic.designsystem.components.GlassCard
import com.smarttraffic.designsystem.components.HeatLegend
import com.smarttraffic.designsystem.components.PremiumTopBar
import android.content.pm.PackageManager
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun LiveMapScreen(
    onRouteOptions: () -> Unit,
    onAnalytics: () -> Unit,
    onReport: () -> Unit,
    onLeaderboard: () -> Unit,
    onProfile: () -> Unit,
    viewModel: LiveMapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val center = LatLng(28.6139, 77.2090)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(center, 12.5f)
    }
    val overlayAlpha = remember { Animatable(0f) }
    val hasMapsKey = remember {
        runCatching {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val value = appInfo.metaData?.getString("com.google.android.geo.API_KEY")
            !value.isNullOrBlank()
        }.getOrDefault(false)
    }

    LaunchedEffect(Unit) {
        delay(250)
        overlayAlpha.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasMapsKey) {
            GoogleMap(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(cameraPositionState) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type != PointerEventType.Scroll) continue
                                val deltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: continue
                                if (deltaY == 0f) continue
                                val current = cameraPositionState.position
                                val zoomStep = (abs(deltaY) / 90f).coerceIn(0.15f, 1.2f)
                                val nextZoom = if (deltaY < 0f) {
                                    current.zoom + zoomStep
                                } else {
                                    current.zoom - zoomStep
                                }.coerceIn(3f, 21f)
                                cameraPositionState.position = CameraPosition.fromLatLngZoom(current.target, nextZoom)
                            }
                        }
                    },
                cameraPositionState = cameraPositionState,
                properties = MapProperties(
                    isTrafficEnabled = true,
                    mapStyleOptions = null,
                ),
                uiSettings = MapUiSettings(
                    compassEnabled = true,
                    myLocationButtonEnabled = false,
                    zoomControlsEnabled = false,
                ),
            ) {
                Marker(
                    state = MarkerState(position = center),
                    title = "Map Center",
                    snippet = "Google traffic layer enabled",
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF12161E)),
                contentAlignment = Alignment.Center,
            ) {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Map unavailable", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Google Maps API key is missing. Add MAPS_API_KEY in your Gradle properties.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.24f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.3f),
                        ),
                    ),
                )
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 148.dp)
                .alpha(overlayAlpha.value),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PremiumTopBar(
                title = if (state.isOnline) "Live Grid" else "Offline Cache",
                subtitle = "Congestion ${(state.snapshot.congestionScore * 100).toInt()}% - Prediction ${(state.predictedCongestion * 100).toInt()}%",
                actions = {
                    IconButton(onClick = onProfile) {
                        Icon(Icons.Rounded.Person, contentDescription = "Profile")
                    }
                },
            )

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRouteOptions() },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Set Source & Destination",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Tap to enter route points and view options",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        )
                    }
                    Icon(Icons.Rounded.Route, contentDescription = "Open route planner")
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeatLegend()
                        Text(
                            text = "Avg speed ${state.snapshot.avgSpeedKph.toInt()} km/h - Confidence ${(state.snapshot.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = cloudSyncCaption(state),
                            style = MaterialTheme.typography.bodySmall,
                            color = cloudSyncColor(state.cloudSyncState),
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassCard {
                Column {
                    IconButton(
                        onClick = {
                            val current = cameraPositionState.position
                            val zoom = (current.zoom + 1f).coerceAtMost(21f)
                            cameraPositionState.position = CameraPosition.fromLatLngZoom(current.target, zoom)
                        },
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Zoom in")
                    }
                    IconButton(
                        onClick = {
                            val current = cameraPositionState.position
                            val zoom = (current.zoom - 1f).coerceAtLeast(3f)
                            cameraPositionState.position = CameraPosition.fromLatLngZoom(current.target, zoom)
                        },
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Zoom out")
                    }
                }
            }
        }

        GlassCard(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf(
                    Triple("Routes", Icons.Rounded.Route, onRouteOptions),
                    Triple("Analytics", Icons.Rounded.Analytics, onAnalytics),
                    Triple("Report", Icons.Rounded.Report, onReport),
                    Triple("Ranks", Icons.Rounded.EmojiEvents, onLeaderboard),
                ).forEach { item ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { item.third.invoke() }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(imageVector = item.second, contentDescription = item.first)
                        Text(text = item.first, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}


private fun cloudSyncCaption(state: LiveMapUiState): String {
    val suffix = if (state.lastCloudSyncEpochMs > 0L) {
        val ageMinutes = ((System.currentTimeMillis() - state.lastCloudSyncEpochMs).coerceAtLeast(0L) / 60_000L).toInt()
        when {
            ageMinutes <= 0 -> " Last sync just now."
            ageMinutes == 1 -> " Last sync 1 min ago."
            else -> " Last sync ${ageMinutes} min ago."
        }
    } else {
        ""
    }
    return state.cloudSyncMessage + suffix
}

private fun cloudSyncColor(state: CloudSyncState): Color {
    return when (state) {
        CloudSyncState.SYNCED -> Color(0xFF22C55E)
        CloudSyncState.UPLOADING -> Color(0xFF38BDF8)
        CloudSyncState.REQUIRES_LOGIN -> Color(0xFFF59E0B)
        CloudSyncState.LOCATION_UNAVAILABLE -> Color(0xFFF97316)
        CloudSyncState.ERROR -> Color(0xFFEF4444)
        CloudSyncState.IDLE -> Color(0xFF93C5FD)
    }
}
