package com.smarttraffic.app.ui.screens.reporting

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smarttraffic.designsystem.components.GlassCard
import com.smarttraffic.designsystem.components.PremiumTopBar

@Composable
fun ReportingScreen(
    onBack: () -> Unit,
    onLoginRequired: () -> Unit = {},
    viewModel: ReportingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var locationPermissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    LaunchedEffect(Unit) {
        viewModel.refreshAuthState()
    }

    LaunchedEffect(state.useCurrentLocation, state.isAuthenticated) {
        if (!state.isAuthenticated) return@LaunchedEffect
        if (!state.useCurrentLocation) return@LaunchedEffect
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
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)))
            .padding(16.dp)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PremiumTopBar(
            title = "Incident Reporting",
            subtitle = "Consensus-validated safety feed",
            actions = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
        )
        GlassCard {
            if (!state.isAuthenticated) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Please login to report incidents.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Reporting is available only for logged-in users so reports remain trusted and traceable.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onLoginRequired,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Login / Sign up")
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Incident Type", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        items(REPORT_TYPES, key = { it }) { type ->
                            FilterChip(
                                selected = state.type == type,
                                onClick = { viewModel.updateType(type) },
                                label = { Text(type) },
                            )
                        }
                    }
                    Text("Severity: ${state.severity}")
                    Slider(
                        value = state.severity.toFloat(),
                        valueRange = 1f..5f,
                        onValueChange = viewModel::updateSeverity,
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Use Current Location", style = MaterialTheme.typography.titleSmall)
                        Switch(
                            checked = state.useCurrentLocation,
                            onCheckedChange = viewModel::updateUseCurrentLocation,
                        )
                        if (!state.useCurrentLocation) {
                            OutlinedTextField(
                                value = state.latitude,
                                onValueChange = viewModel::updateLatitude,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Latitude") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = state.longitude,
                                onValueChange = viewModel::updateLongitude,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Longitude") },
                                singleLine = true,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.message,
                        onValueChange = viewModel::updateMessage,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Message") },
                        minLines = 3,
                    )
                    Button(
                        onClick = viewModel::submit,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.submitting,
                    ) {
                        Text(if (state.submitting) "Submitting..." else "Submit Report")
                    }
                    state.result?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

