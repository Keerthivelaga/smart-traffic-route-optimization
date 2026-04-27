package com.smarttraffic.app.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.app.session.NavigationSessionStore
import com.smarttraffic.core_engine.domain.model.PredictionInput
import com.smarttraffic.core_engine.domain.model.TrafficSnapshot
import com.smarttraffic.core_engine.domain.usecase.FetchPredictionUseCase
import com.smarttraffic.core_engine.domain.usecase.ObserveTrafficUseCase
import com.smarttraffic.core_engine.domain.usecase.RefreshTrafficUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_TREND_POINTS = 30

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val navigationSessionStore: NavigationSessionStore,
    private val observeTrafficUseCase: ObserveTrafficUseCase,
    private val refreshTrafficUseCase: RefreshTrafficUseCase,
    private val fetchPredictionUseCase: FetchPredictionUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(AnalyticsUiState())
    val state: StateFlow<AnalyticsUiState> = _state.asStateFlow()

    private var trafficJob: Job? = null
    private var predictionJob: Job? = null
    private var trackedSegmentId: String? = null

    init {
        observeRouteSession()
    }

    private fun observeRouteSession() {
        viewModelScope.launch {
            navigationSessionStore.state.collectLatest { session ->
                _state.update { current ->
                    current.copy(
                        routeActive = session.active,
                        routeTitle = session.routeTitle,
                        origin = session.origin,
                        destination = session.destination,
                        etaMinutes = session.etaMinutes,
                        progress = session.progress,
                        congestionTrend = session.congestionTimeline.ifEmpty { current.congestionTrend },
                        speedDistribution = session.congestionTimeline
                            .takeLast(6)
                            .map { score -> ((70f - score * 42f).coerceAtLeast(12f) / 70f).coerceIn(0.1f, 1f) }
                            .ifEmpty { current.speedDistribution },
                    )
                }

                if (session.segmentId.isNotBlank() && session.segmentId != trackedSegmentId) {
                    trackedSegmentId = session.segmentId
                    observeLiveTraffic(session.segmentId)
                    startPredictionPolling(session.segmentId)
                }
            }
        }
    }

    private fun observeLiveTraffic(segmentId: String) {
        trafficJob?.cancel()
        trafficJob = viewModelScope.launch {
            observeTrafficUseCase(segmentId).collectLatest { snapshot ->
                if (snapshot.isPlaceholder()) return@collectLatest
                _state.update { current ->
                    current.copy(snapshot = snapshot)
                }
            }
        }
    }

    private fun startPredictionPolling(segmentId: String) {
        predictionJob?.cancel()
        predictionJob = viewModelScope.launch {
            while (true) {
                refreshTrafficUseCase(segmentId)
                    .onSuccess { snapshot ->
                        _state.update { current -> current.copy(snapshot = snapshot) }
                    }
                    .onFailure {
                        val seededSnapshot = seedSnapshotFromSession(segmentId)
                        _state.update { current -> current.copy(snapshot = seededSnapshot) }
                    }

                val routeState = _state.value
                val features = listOf(
                    routeState.snapshot.congestionScore,
                    routeState.snapshot.avgSpeedKph / 120f,
                    routeState.progress,
                    routeState.congestionTrend.lastOrNull() ?: routeState.snapshot.congestionScore,
                )
                fetchPredictionUseCase(
                    PredictionInput(
                        cacheKey = "analytics_${segmentId}_${System.currentTimeMillis() / 10000}",
                        segmentId = segmentId,
                        horizonMinutes = 15,
                        features = features,
                    )
                ).onSuccess { prediction ->
                    val predicted = prediction.value.coerceIn(0f, 1f)
                    _state.update { current ->
                        current.copy(
                            predictedCongestion = predicted,
                            congestionTrend = current.congestionTrend.appendTrendPoint(predicted),
                            snapshot = current.snapshot.copy(congestionScore = predicted),
                        )
                    }
                }
                delay(PREDICTION_POLL_INTERVAL_MS)
            }
        }
    }

    private fun seedSnapshotFromSession(segmentId: String): TrafficSnapshot {
        val session = navigationSessionStore.state.value
        val currentSnapshot = _state.value.snapshot
        val congestion = when {
            session.active -> session.congestionScore.coerceIn(0f, 1f)
            else -> currentSnapshot.congestionScore.coerceIn(0f, 1f)
        }
        val avgSpeed = (74f - congestion * 46f).coerceIn(10f, 95f)
        val confidence = if (session.active) 0.76f else 0.55f
        val anomaly = (congestion * 0.33f).coerceIn(0.03f, 0.75f)
        return TrafficSnapshot(
            segmentId = segmentId,
            congestionScore = congestion,
            confidence = confidence,
            avgSpeedKph = avgSpeed,
            anomalyScore = anomaly,
            timestampIso = Instant.now().toString(),
        )
    }

    override fun onCleared() {
        trafficJob?.cancel()
        predictionJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val PREDICTION_POLL_INTERVAL_MS = 15_000L
    }
}

private fun TrafficSnapshot.isPlaceholder(): Boolean {
    return timestampIso.isBlank() && confidence <= 0f && avgSpeedKph <= 0f
}

private fun List<Float>.appendTrendPoint(value: Float): List<Float> {
    val next = (this + value.coerceIn(0f, 1f)).takeLast(MAX_TREND_POINTS)
    return next.ifEmpty { listOf(value.coerceIn(0f, 1f)) }
}

data class AnalyticsUiState(
    val routeActive: Boolean = false,
    val routeTitle: String = "",
    val origin: String = "",
    val destination: String = "",
    val etaMinutes: Int = 0,
    val progress: Float = 0f,
    val snapshot: TrafficSnapshot = TrafficSnapshot(
        segmentId = "seg_0001",
        congestionScore = 0.42f,
        confidence = 0.87f,
        avgSpeedKph = 39f,
        anomalyScore = 0.09f,
        timestampIso = "",
    ),
    val predictedCongestion: Float = 0.45f,
    val congestionTrend: List<Float> = listOf(0.33f, 0.38f, 0.43f, 0.5f, 0.47f, 0.41f),
    val speedDistribution: List<Float> = listOf(0.24f, 0.38f, 0.42f, 0.57f, 0.48f, 0.32f),
)
