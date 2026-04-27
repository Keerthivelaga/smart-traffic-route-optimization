package com.smarttraffic.app.ui.screens.navigationmode

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.smarttraffic.designsystem.components.GlassCard
import com.smarttraffic.designsystem.components.PremiumTopBar
import java.util.Locale
import kotlin.math.abs

@Composable
fun NavigationModeScreen(
    onRouteOptions: () -> Unit,
    onAnalytics: () -> Unit,
    onReport: () -> Unit,
    onLeaderboard: () -> Unit,
    onExit: () -> Unit,
    viewModel: NavigationModeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by animateFloatAsState(targetValue = state.progress, label = "navProgress")
    val context = LocalContext.current
    var locationPermissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            viewModel.retryLiveTracking()
        }
    }

    val fallbackCenter = remember { LatLng(28.6139, 77.2090) }
    val routePoints = remember(state.routePoints) {
        state.routePoints.map { point -> LatLng(point.latitude, point.longitude) }
    }
    val stepPolylines = remember(state.steps) {
        state.steps.map { step ->
            step.polyline.map { point -> LatLng(point.latitude, point.longitude) }
        }
    }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(fallbackCenter, 12.5f)
    }
    val hasMapsKey = remember {
        runCatching {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val value = appInfo.metaData?.getString("com.google.android.geo.API_KEY")
            !value.isNullOrBlank()
        }.getOrDefault(false)
    }
    var mapLoaded by remember { mutableStateOf(false) }
    var cameraRetryTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (locationPermissionRequested) return@LaunchedEffect
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) return@LaunchedEffect
        locationPermissionRequested = true
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    LaunchedEffect(mapLoaded, routePoints, cameraRetryTrigger) {
        if (!mapLoaded) return@LaunchedEffect
        runCatching {
            if (routePoints.size >= 2) {
                val builder = LatLngBounds.builder()
                routePoints.forEach(builder::include)
                cameraPositionState.animate(
                    update = CameraUpdateFactory.newLatLngBounds(builder.build(), 180),
                    durationMs = 900,
                )
            } else if (routePoints.size == 1) {
                cameraPositionState.animate(
                    update = CameraUpdateFactory.newLatLngZoom(routePoints.first(), 15f),
                    durationMs = 800,
                )
            } else {
                cameraPositionState.animate(
                    update = CameraUpdateFactory.newLatLngZoom(fallbackCenter, 12.5f),
                    durationMs = 800,
                )
            }
        }.onFailure {
            val anchor = routePoints.firstOrNull() ?: fallbackCenter
            runCatching {
                cameraPositionState.animate(
                    update = CameraUpdateFactory.newLatLngZoom(anchor, 13.5f),
                    durationMs = 650,
                )
            }
            if (cameraRetryTrigger == 0 && routePoints.isNotEmpty()) {
                cameraRetryTrigger = 1
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                    )
                )
            ),
    ) {
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
                properties = MapProperties(isTrafficEnabled = true),
                uiSettings = MapUiSettings(
                    compassEnabled = true,
                    myLocationButtonEnabled = false,
                    zoomControlsEnabled = false,
                ),
                onMapLoaded = { mapLoaded = true },
            ) {
                val hasStepPolylines = stepPolylines.any { it.size >= 2 }
                if (routePoints.size >= 2) {
                    Polyline(
                        points = routePoints,
                        color = if (hasStepPolylines) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
                        } else {
                            congestionColor(state.congestionScore)
                        },
                        width = if (hasStepPolylines) 8f else 11f,
                        geodesic = true,
                    )
                }
                stepPolylines.forEachIndexed { idx, points ->
                    if (points.size >= 2) {
                        val stepScore = state.steps.getOrNull(idx)?.congestionScore ?: state.baselineCongestionScore
                        val score = blendCongestion(stepScore, state.predictedCongestionScore)
                        Polyline(
                            points = points,
                            color = congestionColor(score),
                            width = 12f,
                            geodesic = true,
                        )
                    }
                }

                routePoints.firstOrNull()?.let { start ->
                    Marker(
                        state = MarkerState(start),
                        title = "Start",
                        snippet = state.origin.ifBlank { "Route origin" },
                    )
                }
                routePoints.lastOrNull()?.let { end ->
                    Marker(
                        state = MarkerState(end),
                        title = "Destination",
                        snippet = state.destination.ifBlank { "Route destination" },
                    )
                }
                state.currentLocation?.let { live ->
                    Marker(
                        state = MarkerState(LatLng(live.latitude, live.longitude)),
                        title = "You",
                        snippet = "Live location",
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GlassCard {
                    Text("Map unavailable. Configure MAPS_API_KEY.")
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PremiumTopBar(
                title = "Navigation Mode",
                subtitle = "Google Maps live route guidance",
                actions = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Rounded.Close, contentDescription = "Exit")
                    }
                },
            )

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                when {
                    state.loading -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text("Loading route guidance...")
                        }
                    }

                    state.error != null -> {
                        Text(
                            text = state.error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = "Voice")
                                Text(
                                    text = state.current.text,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                            Text(
                                text = "${state.current.distanceMeters}m",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f))
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.routeTitle.isNotBlank()) {
                    Text(
                        text = state.routeTitle,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("ETA ${state.etaMinutes} min", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Distance ${String.format(Locale.US, "%.1f", state.distanceKm)} km",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val currentCongestionColor = congestionColor(state.congestionScore)
                    Text(
                        "Congestion ${(state.congestionScore * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = currentCongestionColor,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CongestionChip("Low", congestionColor(0.2f))
                        CongestionChip("Med", congestionColor(0.5f))
                        CongestionChip("High", congestionColor(0.85f))
                    }
                }
                state.predictedCongestionScore?.let { predicted ->
                    Text(
                        text = "Model Forecast ${(predicted * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = congestionColor(predicted),
                    )
                }
                state.weatherSummary?.let { summary ->
                    Text(
                        text = "Weather: $summary",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.weatherCheckpoints.take(3).forEach { checkpoint ->
                        Text(
                            text = formatWeatherCheckpoint(checkpoint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf(
                        NavShortcut("Routes", Icons.Rounded.Route, onRouteOptions),
                        NavShortcut("Analytics", Icons.Rounded.Analytics, onAnalytics),
                        NavShortcut("Report", Icons.Rounded.Report, onReport),
                        NavShortcut("Ranks", Icons.Rounded.EmojiEvents, onLeaderboard),
                    ).forEach { item ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { item.onClick.invoke() }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(imageVector = item.icon, contentDescription = item.label)
                            Text(text = item.label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                OutlinedButton(
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Exit Navigation")
                }
            }
        }
    }
}

@Composable
private fun CongestionChip(label: String, color: Color) {
    val textColor = if (color.luminance() > 0.55f) Color.Black else Color.White
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(color)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = textColor)
        }
    }
}

private fun congestionColor(score: Float): Color {
    val safe = score.coerceIn(0f, 1f)
    return when {
        safe < 0.34f -> Color(0xFF00A3FF)
        safe < 0.67f -> Color(0xFF7C4DFF)
        else -> Color(0xFFE6007A)
    }
}

private fun blendCongestion(stepCongestion: Float, predictedCongestion: Float?): Float {
    val step = stepCongestion.coerceIn(0f, 1f)
    val predicted = predictedCongestion?.coerceIn(0f, 1f) ?: return step
    return (step * 0.55f + predicted * 0.45f).coerceIn(0f, 1f)
}

private fun formatWeatherCheckpoint(checkpoint: com.smarttraffic.core_engine.domain.model.RouteWeatherCheckpoint): String {
    val temperature = checkpoint.temperatureC?.let { "${it.toInt()}C" } ?: "--"
    val rain = checkpoint.precipitationProbabilityPct?.let { "rain ${it}%" } ?: "rain --"
    val wind = checkpoint.windSpeedKph?.let { "wind ${it.toInt()} km/h" } ?: "wind --"
    return "${checkpoint.label}: ${checkpoint.condition} | $temperature | $rain | $wind"
}

private data class NavShortcut(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
)
