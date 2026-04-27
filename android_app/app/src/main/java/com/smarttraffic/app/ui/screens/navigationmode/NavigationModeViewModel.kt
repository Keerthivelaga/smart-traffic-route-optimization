package com.smarttraffic.app.ui.screens.navigationmode

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.app.navigation.Destination
import com.smarttraffic.app.session.NavigationSessionStore
import com.smarttraffic.app.voice.VoiceNavigator
import com.smarttraffic.core_engine.data.location.DeviceLocationTracker
import com.smarttraffic.core_engine.domain.model.GeoPoint
import com.smarttraffic.core_engine.domain.model.PredictionInput
import com.smarttraffic.core_engine.domain.model.RouteStep
import com.smarttraffic.core_engine.domain.model.RouteWeatherCheckpoint
import com.smarttraffic.core_engine.domain.model.RoutingMode
import com.smarttraffic.core_engine.domain.model.TrafficSnapshot
import com.smarttraffic.core_engine.domain.model.VoiceInstruction
import com.smarttraffic.core_engine.domain.model.WeatherSeverity
import com.smarttraffic.core_engine.domain.usecase.FetchPredictionUseCase
import com.smarttraffic.core_engine.domain.usecase.GetRoutesUseCase
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

@HiltViewModel
class NavigationModeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getRoutesUseCase: GetRoutesUseCase,
    private val refreshTrafficUseCase: RefreshTrafficUseCase,
    private val fetchPredictionUseCase: FetchPredictionUseCase,
    private val locationTracker: DeviceLocationTracker,
    private val navigationSessionStore: NavigationSessionStore,
    private val voiceNavigator: VoiceNavigator,
) : ViewModel() {

    private val originArg: String = savedStateHandle.get<String>(Destination.NavigationMode.ARG_ORIGIN).orEmpty()
    private val destinationArg: String = savedStateHandle.get<String>(Destination.NavigationMode.ARG_DESTINATION).orEmpty()
    private val modeArg: String = savedStateHandle.get<String>(Destination.NavigationMode.ARG_MODE).orEmpty()
    private val routeIdArg: String? = savedStateHandle.get<String>(Destination.NavigationMode.ARG_ROUTE_ID)

    private val _state = MutableStateFlow(NavigationModeUiState())
    val state: StateFlow<NavigationModeUiState> = _state.asStateFlow()

    private var locationJob: Job? = null
    private var predictionJob: Job? = null
    private var weatherRerouteJob: Job? = null
    private var announcedStepIndex = -1
    private var activeSegmentId: String = DEFAULT_SEGMENT_ID
    private var latestSnapshot: TrafficSnapshot? = null

    init {
        voiceNavigator.initialize()
        loadNavigationRoute()
    }

    private fun loadNavigationRoute() {
        val origin = originArg.trim()
        val destination = destinationArg.trim()
        val mode = modeArg.toRoutingModeOrDefault()
        if (origin.isBlank() || destination.isBlank()) {
            _state.update {
                it.copy(
                    loading = false,
                    error = "Missing route details. Select a route before starting navigation.",
                    mode = mode,
                )
            }
            return
        }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    origin = origin,
                    destination = destination,
                    mode = mode,
                )
            }

            getRoutesUseCase.navigationRoute(
                origin = origin,
                destination = destination,
                mode = mode,
                selectedRouteId = routeIdArg,
            ).onSuccess { navigationRoute ->
                val steps = navigationRoute.steps
                val routePoints = navigationRoute.polyline.ifEmpty {
                    steps.flatMap { it.polyline }
                }
                val instructions = navigationRoute.instructions.ifEmpty {
                    listOf(VoiceInstruction(text = "Continue to destination", distanceMeters = 0))
                }
                val currentInstruction = steps.firstOrNull()?.instruction ?: instructions.first()
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        routeTitle = navigationRoute.title,
                        etaMinutes = navigationRoute.etaMinutes,
                        baseEtaMinutes = navigationRoute.etaMinutes,
                        distanceKm = navigationRoute.distanceKm,
                        routePoints = routePoints,
                        instructions = instructions,
                        steps = steps,
                        current = currentInstruction,
                        progress = 0f,
                        baselineCongestionScore = navigationRoute.congestionScore,
                        predictedCongestionScore = null,
                        congestionScore = navigationRoute.congestionScore,
                        currentStepIndex = 0,
                        weatherSummary = navigationRoute.weather?.summary,
                        weatherSeverity = navigationRoute.weather?.severity,
                        weatherCheckpoints = navigationRoute.weather?.checkpoints.orEmpty(),
                    )
                }

                navigationSessionStore.beginRoute(
                    route = navigationRoute,
                    origin = origin,
                    destination = destination,
                    mode = mode,
                )
                activeSegmentId = navigationSessionStore.state.value.segmentId.ifBlank { DEFAULT_SEGMENT_ID }
                speakInstructionIfNeeded(0, currentInstruction)
                startLiveTracking()
                startPredictionPolling(activeSegmentId)
                startWeatherMonitoring()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "Unable to load route navigation details",
                    )
                }
            }
        }
    }

    private fun startLiveTracking() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationTracker.observeLocation().collectLatest { result ->
                result.onSuccess { currentLocation ->
                    advanceWithLocation(currentLocation)
                }.onFailure { error ->
                    _state.update {
                        it.copy(
                            error = error.message ?: "Live location unavailable for navigation guidance",
                        )
                    }
                }
            }
        }
    }

    fun retryLiveTracking() {
        if (_state.value.loading) return
        _state.update { it.copy(error = null) }
        startLiveTracking()
    }

    private fun startPredictionPolling(segmentId: String) {
        predictionJob?.cancel()
        predictionJob = viewModelScope.launch {
            while (true) {
                refreshTrafficUseCase(segmentId)
                    .onSuccess { snapshot ->
                        latestSnapshot = snapshot
                    }
                    .onFailure {
                        latestSnapshot = buildSeedSnapshot(segmentId, _state.value)
                    }

                val current = _state.value
                val snapshot = latestSnapshot ?: buildSeedSnapshot(segmentId, current)
                val predicted = fetchPredictionUseCase(
                    PredictionInput(
                        cacheKey = "navigation_${segmentId}_${System.currentTimeMillis() / 7000}",
                        segmentId = segmentId,
                        horizonMinutes = 15,
                        features = listOf(
                            snapshot.congestionScore.coerceIn(0f, 1f),
                            (snapshot.avgSpeedKph / 120f).coerceIn(0f, 1f),
                            current.progress.coerceIn(0f, 1f),
                            current.baselineCongestionScore.coerceIn(0f, 1f),
                        ),
                    )
                ).getOrNull()?.value?.coerceIn(0f, 1f)

                if (predicted != null) {
                    _state.update {
                        it.copy(
                            predictedCongestionScore = predicted,
                            congestionScore = predicted,
                        )
                    }
                    updateSessionCongestion(predicted)
                }

                delay(PREDICTION_POLL_INTERVAL_MS)
            }
        }
    }
    private fun startWeatherMonitoring() {
        weatherRerouteJob?.cancel()

        weatherRerouteJob = viewModelScope.launch {

            while (true) {

                val current = _state.value

                // Only check when navigation is active
                if (current.origin.isNotBlank() && current.destination.isNotBlank()) {

                    // If severe weather detected on current route
                    if (current.weatherSeverity == WeatherSeverity.HIGH ||
                        current.weatherSeverity == WeatherSeverity.SEVERE
                    ) {

                        getRoutesUseCase.navigationRoute(
                            origin = current.origin,
                            destination = current.destination,
                            mode = current.mode,
                            selectedRouteId = null
                        ).onSuccess { newRoute ->

                            val newWeather = newRoute.weather?.severity

                            if (newWeather == WeatherSeverity.LOW ||
                                newWeather == WeatherSeverity.MODERATE
                            ) {

                                val steps = newRoute.steps
                                val routePoints = newRoute.polyline.ifEmpty {
                                    steps.flatMap { it.polyline }
                                }

                                _state.update {
                                    it.copy(
                                        routeTitle = newRoute.title,
                                        etaMinutes = newRoute.etaMinutes,
                                        distanceKm = newRoute.distanceKm,
                                        routePoints = routePoints,
                                        steps = steps,
                                        weatherSummary = newRoute.weather?.summary,
                                        weatherSeverity = newRoute.weather?.severity,
                                        weatherCheckpoints = newRoute.weather?.checkpoints.orEmpty()
                                    )
                                }

                                voiceNavigator.speak(
                                    "Weather conditions ahead are severe. Switching to a safer route."
                                )
                            }
                        }
                    }
                }

                // Check every 1 minute
                delay(60_000)           }
        }
    }

    private fun advanceWithLocation(currentLocation: GeoPoint) {
        val current = _state.value
        val steps = current.steps
        if (steps.isEmpty()) {
            val effectiveCongestion = current.predictedCongestionScore ?: current.baselineCongestionScore
            navigationSessionStore.updateNavigation(
                currentLocation = currentLocation,
                etaMinutes = current.etaMinutes,
                progress = current.progress,
                congestionScore = effectiveCongestion,
            )
            _state.update {
                it.copy(
                    currentLocation = currentLocation,
                    congestionScore = effectiveCongestion,
                )
            }
            return
        }

        var index = current.currentStepIndex.coerceIn(0, steps.lastIndex)
        while (index < steps.lastIndex) {
            val target = steps[index].end ?: break
            val distanceToTarget = distanceMeters(currentLocation, target)
            if (distanceToTarget <= STEP_REACHED_METERS) {
                index += 1
            } else {
                break
            }
        }

        val step = steps[index]
        val nextDistanceMeters = step.end?.let { distanceMeters(currentLocation, it).toInt() }
            ?: step.instruction.distanceMeters
        val stepDistance = step.instruction.distanceMeters.coerceAtLeast(1)
        val withinStepProgress = (1f - (nextDistanceMeters.toFloat() / stepDistance.toFloat())).coerceIn(0f, 1f)
        val progress = ((index + withinStepProgress) / steps.size.toFloat()).coerceIn(0f, 1f)
        val etaMinutes = kotlin.math.ceil(current.baseEtaMinutes * (1f - progress).toDouble()).toInt().coerceAtLeast(0)
        val remainingCongestion = steps.drop(index).map { it.congestionScore }
        val baselineCongestion = if (remainingCongestion.isNotEmpty()) {
            remainingCongestion.average().toFloat()
        } else {
            current.baselineCongestionScore
        }.coerceIn(0f, 1f)
        val effectiveCongestion = current.predictedCongestionScore ?: baselineCongestion

        val nextInstruction = step.instruction.copy(distanceMeters = nextDistanceMeters.coerceAtLeast(0))
        _state.update {
            it.copy(
                currentLocation = currentLocation,
                current = nextInstruction,
                currentStepIndex = index,
                progress = progress,
                etaMinutes = etaMinutes,
                baselineCongestionScore = baselineCongestion,
                congestionScore = effectiveCongestion,
            )
        }

        speakInstructionIfNeeded(index, step.instruction)
        navigationSessionStore.updateNavigation(
            currentLocation = currentLocation,
            etaMinutes = etaMinutes,
            progress = progress,
            congestionScore = effectiveCongestion,
        )
    }

    private fun updateSessionCongestion(predictedCongestion: Float) {
        val current = _state.value
        navigationSessionStore.updateNavigation(
            currentLocation = current.currentLocation,
            etaMinutes = current.etaMinutes,
            progress = current.progress,
            congestionScore = predictedCongestion.coerceIn(0f, 1f),
        )
    }

    private fun buildSeedSnapshot(segmentId: String, state: NavigationModeUiState): TrafficSnapshot {
        val baseline = state.baselineCongestionScore.coerceIn(0f, 1f)
        return TrafficSnapshot(
            segmentId = segmentId,
            congestionScore = baseline,
            confidence = 0.72f,
            avgSpeedKph = (76f - baseline * 48f).coerceIn(8f, 95f),
            anomalyScore = (baseline * 0.3f).coerceIn(0.03f, 0.8f),
            timestampIso = Instant.now().toString(),
        )
    }

    private fun speakInstructionIfNeeded(stepIndex: Int, instruction: VoiceInstruction) {
        if (stepIndex == announcedStepIndex) return
        announcedStepIndex = stepIndex
        voiceNavigator.speak(instruction.text)
    }

    override fun onCleared() {
        locationJob?.cancel()
        predictionJob?.cancel()
        weatherRerouteJob?.cancel()
        navigationSessionStore.markNavigationStopped()
        voiceNavigator.shutdown()
        super.onCleared()
    }

    private companion object {
        const val STEP_REACHED_METERS = 35.0
        const val PREDICTION_POLL_INTERVAL_MS = 15_000L
        const val DEFAULT_SEGMENT_ID = "seg_0001"
    }
}

data class NavigationModeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val origin: String = "",
    val destination: String = "",
    val mode: RoutingMode = RoutingMode.FASTEST,
    val routeTitle: String = "",
    val etaMinutes: Int = 0,
    val baseEtaMinutes: Int = 0,
    val distanceKm: Float = 0f,
    val routePoints: List<GeoPoint> = emptyList(),
    val instructions: List<VoiceInstruction> = emptyList(),
    val steps: List<RouteStep> = emptyList(),
    val current: VoiceInstruction = VoiceInstruction("Preparing route...", 0),
    val currentStepIndex: Int = 0,
    val currentLocation: GeoPoint? = null,
    val baselineCongestionScore: Float = 0f,
    val predictedCongestionScore: Float? = null,
    val congestionScore: Float = 0f,
    val progress: Float = 0f,
    val weatherSummary: String? = null,
    val weatherSeverity: WeatherSeverity? = null,
    val weatherCheckpoints: List<RouteWeatherCheckpoint> = emptyList(),
)

private fun String.toRoutingModeOrDefault(): RoutingMode {
    return RoutingMode.entries.firstOrNull { it.name == this } ?: RoutingMode.FASTEST
}

private fun distanceMeters(start: GeoPoint, end: GeoPoint): Double {
    val earthRadiusMeters = 6_371_000.0
    val dLat = Math.toRadians(end.latitude - start.latitude)
    val dLon = Math.toRadians(end.longitude - start.longitude)
    val lat1 = Math.toRadians(start.latitude)
    val lat2 = Math.toRadians(end.latitude)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2) *
        kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return earthRadiusMeters * c
}
