package com.smarttraffic.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = TrafficColorTokens.Teal60,
    onPrimary = TrafficColorTokens.Night0,
    secondary = TrafficColorTokens.Cyan50,
    onSecondary = TrafficColorTokens.Night0,
    tertiary = TrafficColorTokens.Amber50,
    background = TrafficColorTokens.Night0,
    surface = TrafficColorTokens.SurfaceDark,
    onSurface = TrafficColorTokens.White90,
    error = TrafficColorTokens.Red45,
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = TrafficColorTokens.Teal40,
    onPrimary = TrafficColorTokens.Night0,
    secondary = TrafficColorTokens.Cyan50,
    onSecondary = TrafficColorTokens.Night0,
    tertiary = TrafficColorTokens.Amber50,
    background = TrafficColorTokens.SurfaceLight,
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = TrafficColorTokens.Night20,
    error = TrafficColorTokens.Red45,
)

@Composable
fun SmartTrafficTheme(
    darkTheme: Boolean = false,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val adjustedScheme = if (highContrast) {
        scheme.copy(
            primary = TrafficColorTokens.HighContrastYellow,
            onPrimary = TrafficColorTokens.Night0,
        )
    } else {
        scheme
    }

    CompositionLocalProvider(LocalTrafficSpacing provides TrafficSpacing()) {
        MaterialTheme(
            colorScheme = adjustedScheme,
            typography = TrafficTypography,
            content = content,
        )
    }
}

