package com.smarttraffic.designsystem.motion

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

object TrafficMotion {
    val express = tween<Float>(durationMillis = 280, easing = FastOutSlowInEasing)
    val emphasis = tween<Float>(durationMillis = 480, easing = Easing { fraction -> (fraction * fraction * (3 - 2 * fraction)) })
    val bounce = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}

