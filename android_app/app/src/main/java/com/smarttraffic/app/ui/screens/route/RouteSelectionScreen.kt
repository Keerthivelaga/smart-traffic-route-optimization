package com.smarttraffic.app.ui.screens.route

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smarttraffic.core_engine.domain.model.RoutingMode
import com.smarttraffic.core_engine.domain.model.WeatherSeverity
import com.smarttraffic.designsystem.components.GlassCard
import com.smarttraffic.designsystem.components.PremiumTopBar
import com.smarttraffic.designsystem.components.RouteOptionCard
import com.smarttraffic.designsystem.components.TrafficLoading

@Composable
fun RouteSelectionScreen(
    onStartNavigation: (origin: String, destination: String, mode: RoutingMode, routeId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: RouteSelectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var locationPermissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    LaunchedEffect(state.origin) {
        if (!state.origin.trim().equals("Current Location", ignoreCase = true)) return@LaunchedEffect
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PremiumTopBar(title = "Route Intelligence", subtitle = "Choose your AI routing mode")

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.origin,
                    onValueChange = viewModel::onOriginChanged,
                    label = { Text("Source") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                DropdownMenu(
                    expanded = state.originExpanded && state.originSuggestions.isNotEmpty(),
                    onDismissRequest = viewModel::dismissOriginSuggestions,
                    modifier = Modifier.fillMaxWidth(0.96f),
                    properties = PopupProperties(focusable = false),
                ) {
                    state.originSuggestions.forEach { suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion) },
                            onClick = { viewModel.selectOriginSuggestion(suggestion) },
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.destination,
                    onValueChange = viewModel::onDestinationChanged,
                    label = { Text("Destination") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                DropdownMenu(
                    expanded = state.destinationExpanded && state.destinationSuggestions.isNotEmpty(),
                    onDismissRequest = viewModel::dismissDestinationSuggestions,
                    modifier = Modifier.fillMaxWidth(0.96f),
                    properties = PopupProperties(focusable = false),
                ) {
                    state.destinationSuggestions.forEach { suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion) },
                            onClick = { viewModel.selectDestinationSuggestion(suggestion) },
                        )
                    }
                }
            }

            Button(
                onClick = viewModel::searchRoutes,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.origin.isNotBlank() && state.destination.isNotBlank() && !state.loading,
            ) {
                Text("Find Available Routes")
            }

            Text(
                text = "Routing Preference",
                style = MaterialTheme.typography.titleMedium,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(RoutingMode.entries.toList(), key = { it.name }) { mode ->
                    FilterChip(
                        selected = mode == state.mode,
                        onClick = { viewModel.selectMode(mode) },
                        label = {
                            Text(
                                text = mode.label(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }

            if (state.loading) {
                TrafficLoading(modifier = Modifier.padding(top = 28.dp))
            }
            state.error?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                state.routes.forEach { route ->
                    RouteOptionCard(
                        title = route.title,
                        etaMinutes = route.etaMinutes,
                        distanceKm = route.distanceKm,
                        score = route.confidence,
                        weatherRiskLabel = route.weather?.let {
                            "Weather risk ${(route.weatherRiskScore * 100).toInt()}% - ${it.severity.label()}"
                        },
                        weatherSummary = route.weather?.summary,
                        weatherMeta = route.weather?.let { weather ->
                            val precip = weather.maxPrecipitationProbabilityPct?.let { "$it%" } ?: "--"
                            val wind = weather.maxWindSpeedKph?.let { "${it.toInt()} km/h" } ?: "--"
                            val temp = weather.averageTemperatureC?.let { "${it.toInt()}C" } ?: "--"
                            "Temp $temp - Rain $precip - Wind $wind"
                        },
                        selected = route.id == state.selectedRouteId,
                        onTap = { viewModel.selectRoute(route.id) },
                    )
                }
            }

            state.routes.firstOrNull { it.id == state.selectedRouteId }?.weather?.let { weather ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Weather On Chosen Route",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Risk ${(state.routes.firstOrNull { it.id == state.selectedRouteId }?.weatherRiskScore?.times(100f)?.toInt() ?: 0)}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = weather.summary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        weather.checkpoints.forEach { checkpoint ->
                            val temp = checkpoint.temperatureC?.let { "${it.toInt()}C" } ?: "--"
                            val rain = checkpoint.precipitationProbabilityPct?.let { "${it}%" } ?: "--"
                            val wind = checkpoint.windSpeedKph?.let { "${it.toInt()} km/h" } ?: "--"
                            Text(
                                text = "${checkpoint.label}: ${checkpoint.condition} | Temp $temp | Rain $rain | Wind $wind",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            if (!state.weatherAdvisory.isNullOrBlank() && state.alternateRoutes.isNotEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Weather Advisory",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = state.weatherAdvisory.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        state.alternateRoutes.forEach { alternate ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = alternate.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "ETA ${alternate.etaMinutes} min | Weather ${(alternate.weatherRiskScore * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                                    )
                                }
                                OutlinedButton(onClick = { viewModel.selectRoute(alternate.id) }) {
                                    Text("Use")
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(
                onClick = {
                    val routeId = state.selectedRouteId ?: return@Button
                    onStartNavigation(
                        state.origin.trim(),
                        state.destination.trim(),
                        state.mode,
                        routeId,
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = state.selectedRouteId != null,
            ) {
                Text("Start Navigation")
            }
        }
    }
}

private fun WeatherSeverity.label(): String = when (this) {
    WeatherSeverity.LOW -> "Low"
    WeatherSeverity.MODERATE -> "Moderate"
    WeatherSeverity.HIGH -> "High"
    WeatherSeverity.SEVERE -> "Severe"
}

private fun RoutingMode.label(): String = when (this) {
    RoutingMode.FASTEST -> "Fastest"
    RoutingMode.FUEL_EFFICIENT -> "Fuel Efficient"
    RoutingMode.LOW_TRAFFIC -> "Low Traffic"
    RoutingMode.SCENIC -> "Scenic"
}

