package com.smarttraffic.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun MorphingFab(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetScale by animateFloatAsState(
        targetValue = if (expanded) 1.08f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "fabScale",
    )
    val press = remember { Animatable(1f) }

    Box(
        modifier = modifier
            .scale(targetScale * press.value)
            .size(if (expanded) 72.dp else 60.dp)
            .background(
                brush = Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                ),
                shape = CircleShape,
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        press.animateTo(0.92f)
                        tryAwaitRelease()
                        press.animateTo(1f)
                    },
                    onTap = { onClick() },
                )
            },
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = "Action",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .size(28.dp)
                .align(androidx.compose.ui.Alignment.Center),
        )
    }

    LaunchedEffect(expanded) {
        press.animateTo(1f)
    }
}

