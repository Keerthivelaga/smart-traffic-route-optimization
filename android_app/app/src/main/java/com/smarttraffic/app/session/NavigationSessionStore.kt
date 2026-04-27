package com.smarttraffic.app.session

import com.smarttraffic.core_engine.domain.model.GeoPoint
import com.smarttraffic.core_engine.domain.model.NavigationRoute
import com.smarttraffic.core_engine.domain.model.RouteStep
import com.smarttraffic.core_engine.domain.model.RoutingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class NavigationSessionStore @Inject constructor() {
    private val _state = MutableStateFlow(NavigationSessionState())
    val state: StateFlow<NavigationSessionState> = _state.asStateFlow()

    fun beginRoute(
        route: NavigationRoute,
        origin: String,
        destination: String,
        mode: RoutingMode,
    ) {
        _state.value = NavigationSessionState(
            active = true,
            routeId = route.routeId,
            routeTitle = route.title,
            origin = origin,
            destination = destination,
            mode = mode,
            etaMinutes = route.etaMinutes,
            distanceKm = route.distanceKm,
            routePoints = route.polyline,
            steps = route.steps,
            congestionScore = route.congestionScore,
            congestionTimeline = route.steps.map { it.congestionScore }.take(MAX_TIMELINE_POINTS),
            segmentId = route.routeId.toSegmentId(),
        )
    }

    fun updateNavigation(
        currentLocation: GeoPoint?,
        etaMinutes: Int,
        progress: Float,
        congestionScore: Float,
    ) {
        val previous = _state.value
        if (!previous.active) return

        val nextTimeline = ArrayList(previous.congestionTimeline)
        if (nextTimeline.isEmpty() || kotlin.math.abs(nextTimeline.last() - congestionScore) >= TIMELINE_DELTA_THRESHOLD) {
            nextTimeline.add(congestionScore.coerceIn(0f, 1f))
        }
        while (nextTimeline.size > MAX_TIMELINE_POINTS) {
            nextTimeline.removeAt(0)
        }

        _state.value = previous.copy(
            currentLocation = currentLocation,
            etaMinutes = etaMinutes.coerceAtLeast(0),
            progress = progress.coerceIn(0f, 1f),
            congestionScore = congestionScore.coerceIn(0f, 1f),
            congestionTimeline = nextTimeline,
            lastUpdatedEpochMs = System.currentTimeMillis(),
        )
    }

    fun markNavigationStopped() {
        val current = _state.value
        if (!current.active) return
        _state.value = current.copy(active = false)
    }

    private fun String.toSegmentId(): String {
        val normalized = filter { it.isLetterOrDigit() }.take(12).ifBlank { "route" }
        return "seg_$normalized"
    }

    private companion object {
        const val MAX_TIMELINE_POINTS = 40
        const val TIMELINE_DELTA_THRESHOLD = 0.02f
    }
}

data class NavigationSessionState(
    val active: Boolean = false,
    val routeId: String = "",
    val routeTitle: String = "",
    val origin: String = "",
    val destination: String = "",
    val mode: RoutingMode = RoutingMode.FASTEST,
    val segmentId: String = "seg_0001",
    val etaMinutes: Int = 0,
    val distanceKm: Float = 0f,
    val progress: Float = 0f,
    val congestionScore: Float = 0f,
    val congestionTimeline: List<Float> = emptyList(),
    val routePoints: List<GeoPoint> = emptyList(),
    val steps: List<RouteStep> = emptyList(),
    val currentLocation: GeoPoint? = null,
    val lastUpdatedEpochMs: Long = 0L,
)
