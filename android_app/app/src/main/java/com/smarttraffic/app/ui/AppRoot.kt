package com.smarttraffic.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smarttraffic.app.navigation.TrafficNavHost
import com.smarttraffic.designsystem.theme.SmartTrafficTheme

@Composable
fun AppRoot(
    viewModel: AppViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val fontScale = (state.textScalePct / 100f).coerceIn(0.85f, 1.4f)

    SmartTrafficTheme(highContrast = state.highContrast) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                density = density.density,
                fontScale = fontScale,
            )
        ) {
            TrafficNavHost(
                isCompromised = state.compromised,
                modifier = androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            )
        }
    }
}

