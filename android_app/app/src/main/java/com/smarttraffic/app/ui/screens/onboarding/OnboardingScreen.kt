package com.smarttraffic.app.ui.screens.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch

// ─── Data ────────────────────────────────────────────────────────────────────

private data class OnboardingSlide(
    val icon: ImageVector,
    val tag: String,
    val title: String,
    val body: String
)

private val slides = listOf(
    OnboardingSlide(
        icon = Icons.Rounded.LocationOn,
        tag = "REAL-TIME",
        title = "Live Pulse",
        body = "See city traffic flow in real time with AI-powered predictive overlays."
    ),
    OnboardingSlide(
        icon = Icons.Rounded.Navigation,
        tag = "ROUTES",
        title = "Smart Navigation",
        body = "Switch between fastest, eco-friendly and low-traffic routes instantly."
    ),
    OnboardingSlide(
        icon = Icons.Rounded.Warning,
        tag = "COMMUNITY",
        title = "Safety Alerts",
        body = "Report incidents and help drivers avoid dangerous road conditions."
    )
)

// ─── Map Background ───────────────────────────────────────────────────────────

/**
 * Draws a stylised city-map grid entirely on Canvas:
 *  • horizontal + vertical road lines
 *  • diagonal arterial roads
 *  • intersection dots
 *  • three animated pulse-pin locations
 *  • an animated dashed route line connecting the pins
 */
@Composable
private fun MapBackground(
    gridColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mapAnim")

    // Pulse ring expansion (0 → 1, then restart)
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = EaseOutCubic),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseRadius"
    )

    // Slow scroll gives the map a "live" feeling
    val roadScroll by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "roadScroll"
    )

    Box(
        modifier = modifier.drawBehind {

            val w = size.width
            val h = size.height
            val cellW = w / 6f
            val cellH = h / 10f
            val scrollOff = (roadScroll * cellW) % cellW

            // ── Vertical road lines ─────────────────────────────────────
            for (col in -1..7) {
                drawLine(
                    color = gridColor,
                    start = Offset(col * cellW + scrollOff, 0f),
                    end   = Offset(col * cellW + scrollOff, h),
                    strokeWidth = 1.2.dp.toPx()
                )
            }

            // ── Horizontal road lines ───────────────────────────────────
            for (row in 0..11) {
                drawLine(
                    color = gridColor,
                    start = Offset(0f, row * cellH),
                    end   = Offset(w,  row * cellH),
                    strokeWidth = 1.2.dp.toPx()
                )
            }


            drawLine(
                color = gridColor.copy(alpha = gridColor.alpha * 2f),
                start = Offset(0f, h * 0.3f),
                end   = Offset(w,  h * 0.7f),
                strokeWidth = 1.8.dp.toPx()
            )
            drawLine(
                color = gridColor.copy(alpha = gridColor.alpha * 1.5f),
                start = Offset(w * 0.18f, 0f),
                end   = Offset(w * 0.82f, h),
                strokeWidth = 1.6.dp.toPx()
            )

            // ── Intersection dots ───────────────────────────────────────
            for (col in 0..6) {
                for (row in 0..10) {
                    drawCircle(
                        color  = gridColor.copy(alpha = gridColor.alpha * 1.5f),
                        radius = 2.dp.toPx(),
                        center = Offset(col * cellW + scrollOff, row * cellH)
                    )
                }
            }

            // ── Pin locations ───────────────────────────────────────────
            val pins = listOf(
                Offset(w * 0.22f, h * 0.25f),
                Offset(w * 0.65f, h * 0.50f),
                Offset(w * 0.40f, h * 0.75f)
            )

            // Animated dashed route between pins
            val routePath = Path().apply {
                moveTo(pins[0].x, pins[0].y)
                cubicTo(
                    pins[0].x + cellW,       pins[0].y + cellH * 1.5f,
                    pins[1].x - cellW,       pins[1].y - cellH,
                    pins[1].x,               pins[1].y
                )
                cubicTo(
                    pins[1].x + cellW * 0.5f, pins[1].y + cellH,
                    pins[2].x - cellW * 0.5f, pins[2].y - cellH,
                    pins[2].x,               pins[2].y
                )
            }
            drawPath(
                path  = routePath,
                color = accentColor.copy(alpha = 0.50f),
                style = Stroke(
                    width      = 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        intervals = floatArrayOf(14f, 9f),
                        phase     = roadScroll * 46f
                    )
                )
            )

            // Pulse rings + static dots on each pin
            pins.forEachIndexed { i, pin ->
                val staggered = (pulseRadius + i * 0.34f) % 1f
                val maxR = 24.dp.toPx()

                drawCircle(
                    color  = accentColor.copy(alpha = (1f - staggered) * 0.30f),
                    radius = maxR * staggered,
                    center = pin
                )
                drawCircle(
                    color  = accentColor.copy(alpha = (1f - staggered) * 0.12f),
                    radius = maxR * staggered * 1.7f,
                    center = pin
                )
                // Pin dot
                drawCircle(
                    color  = accentColor.copy(alpha = 0.80f),
                    radius = 4.5.dp.toPx(),
                    center = pin
                )
                // White centre
                drawCircle(
                    color  = Color.White.copy(alpha = 0.9f),
                    radius = 2.dp.toPx(),
                    center = pin
                )
            }
        }
    )
}

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {

    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope      = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == slides.lastIndex

    val accent   = MaterialTheme.colorScheme.primary
    val surface  = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(surface)
    ) {

        // ── Map canvas — upper portion ────────────────────────────────────
        MapBackground(
            gridColor   = accent.copy(alpha = 0.09f),
            accentColor = accent,
            modifier    = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.56f)
                .align(Alignment.TopCenter)
        )

        // ── Fade surface up from bottom over the map ──────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.36f to Color.Transparent,
                            0.58f to surface.copy(alpha = 0.80f),
                            0.68f to surface
                        )
                    )
                )
        )

        // ── Soft primary tint orb centred over map ────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.10f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.50f, size.height * 0.20f),
                            radius = size.width * 0.70f
                        ),
                        radius = size.width * 0.70f,
                        center = Offset(size.width * 0.50f, size.height * 0.20f)
                    )
                }
        )

        // ── UI content ────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 56.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // Wordmark + Skip
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    text        = "SMARTNAVIGATION",
                    style       = MaterialTheme.typography.headlineSmall,
                    fontWeight  = FontWeight.ExtraBold,
                    letterSpacing = 3.sp,
                    color       = onSurface
                )
                TextButton(onClick = onContinue) {
                    Text(
                        text  = "Skip",
                        color = onSurface.copy(alpha = 0.40f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // ── Slide cards ───────────────────────────────────────────────
            HorizontalPager(
                state     = pagerState,
                modifier  = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 24.dp),
                pageSpacing = 16.dp
            ) { page ->
                SlideCard(slide = slides[page], accent = accent)
            }

            // ── Page indicators + CTA ─────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    slides.indices.forEach { index ->
                        val selected = pagerState.currentPage == index
                        val dotWidth by animateDpAsState(
                            targetValue    = if (selected) 28.dp else 6.dp,
                            animationSpec  = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness    = Spring.StiffnessMedium
                            ),
                            label = "dotWidth"
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .height(6.dp)
                                .width(dotWidth)
                                .clip(CircleShape)
                                .background(
                                    if (selected) accent
                                    else onSurface.copy(alpha = 0.20f)
                                )
                        )
                    }
                }

                Button(
                    onClick = {
                        if (isLastPage) onContinue()
                        else scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape  = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor   = surface
                    )
                ) {
                    AnimatedContent(
                        targetState  = if (isLastPage) "Start Navigation" else "Next",
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                        label        = "buttonLabel"
                    ) { label ->
                        Text(
                            text       = label,
                            fontWeight = FontWeight.Bold,
                            style      = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}

// ─── Slide Card ───────────────────────────────────────────────────────────────

@Composable
private fun SlideCard(
    slide: OnboardingSlide,
    accent: Color
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        onSurface.copy(alpha = 0.07f),
                        onSurface.copy(alpha = 0.03f)
                    ),
                    start = Offset(0f, 0f),
                    end   = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
            .drawBehind {
                drawRoundRect(
                    brush        = Brush.linearGradient(
                        colors = listOf(
                            onSurface.copy(alpha = 0.16f),
                            onSurface.copy(alpha = 0.04f)
                        )
                    ),
                    cornerRadius = CornerRadius(28.dp.toPx()),
                    style        = Stroke(1.dp.toPx())
                )
            }
    ) {
        // Inner accent glow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.10f),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2f, size.height * 0.30f),
                            radius = size.width * 0.55f
                        ),
                        radius = size.width * 0.55f,
                        center = Offset(size.width / 2f, size.height * 0.30f)
                    )
                }
        )

        Column(
            modifier              = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement   = Arrangement.Center,
            horizontalAlignment   = Alignment.Start
        ) {

            // Tag chip
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(accent.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text          = slide.tag,
                    color         = accent,
                    style         = MaterialTheme.typography.labelSmall,
                    fontWeight    = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Icon with glow ring
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.12f))
                )
                Icon(
                    imageVector     = slide.icon,
                    contentDescription = slide.title,
                    tint            = accent,
                    modifier        = Modifier.size(38.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text       = slide.title,
                style      = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                color      = onSurface,
                lineHeight = 40.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text       = slide.body,
                style      = MaterialTheme.typography.bodyLarge,
                color      = onSurface.copy(alpha = 0.60f),
                lineHeight = 26.sp
            )
        }
    }
}