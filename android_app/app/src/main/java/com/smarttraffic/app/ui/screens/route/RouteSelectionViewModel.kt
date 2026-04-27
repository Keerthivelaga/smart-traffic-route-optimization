package com.smarttraffic.app.ui.screens.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.app.location.LocationSuggestionProvider
import com.smarttraffic.core_engine.domain.model.RouteOption
import com.smarttraffic.core_engine.domain.model.RoutingMode
import com.smarttraffic.core_engine.domain.model.WeatherSeverity
import com.smarttraffic.core_engine.domain.usecase.GetRoutesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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
class RouteSelectionViewModel @Inject constructor(
    private val getRoutesUseCase: GetRoutesUseCase,
    private val locationSuggestionProvider: LocationSuggestionProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(RouteSelectionUiState())
    val state: StateFlow<RouteSelectionUiState> = _state.asStateFlow()
    private var observeJob: Job? = null
    private var refreshJob: Job? = null
    private var originSuggestionJob: Job? = null
    private var destinationSuggestionJob: Job? = null

    init {
        observeRoutes(mode = RoutingMode.FASTEST, origin = DEFAULT_ORIGIN, destination = DEFAULT_DESTINATION)
        refresh(mode = RoutingMode.FASTEST, origin = DEFAULT_ORIGIN, destination = DEFAULT_DESTINATION)
    }

    fun selectMode(mode: RoutingMode) {
        if (mode == _state.value.mode) return
        val origin = _state.value.origin.trim()
        val destination = _state.value.destination.trim()
        _state.update { it.copy(mode = mode, loading = true, error = null, originExpanded = false, destinationExpanded = false) }
        observeRoutes(mode = mode, origin = origin, destination = destination)
        refresh(mode = mode, origin = origin, destination = destination)
    }

    fun onOriginChanged(value: String) {
        _state.update { it.copy(origin = value, error = null) }
        requestOriginSuggestions(query = value)
    }

    fun onDestinationChanged(value: String) {
        _state.update { it.copy(destination = value, error = null) }
        requestDestinationSuggestions(query = value)
    }

    fun showOriginSuggestions() {
        _state.update { it.copy(originExpanded = it.originSuggestions.isNotEmpty()) }
    }

    fun dismissOriginSuggestions() {
        _state.update { it.copy(originExpanded = false) }
    }

    fun showDestinationSuggestions() {
        _state.update { it.copy(destinationExpanded = it.destinationSuggestions.isNotEmpty()) }
    }

    fun dismissDestinationSuggestions() {
        _state.update { it.copy(destinationExpanded = false) }
    }

    fun selectOriginSuggestion(value: String) {
        originSuggestionJob?.cancel()
        _state.update {
            it.copy(
                origin = value,
                originSuggestions = emptyList(),
                originExpanded = false,
                error = null,
            )
        }
    }

    fun selectDestinationSuggestion(value: String) {
        destinationSuggestionJob?.cancel()
        _state.update {
            it.copy(
                destination = value,
                destinationSuggestions = emptyList(),
                destinationExpanded = false,
                error = null,
            )
        }
    }

    fun searchRoutes() {
        originSuggestionJob?.cancel()
        destinationSuggestionJob?.cancel()
        val origin = _state.value.origin.trim()
        val destination = _state.value.destination.trim()
        if (origin.isBlank() || destination.isBlank()) {
            _state.update { it.copy(error = "Source and destination are required") }
            return
        }

        val mode = _state.value.mode
        _state.update { it.copy(loading = true, error = null, originExpanded = false, destinationExpanded = false) }
        observeRoutes(mode = mode, origin = origin, destination = destination)
        refresh(mode = mode, origin = origin, destination = destination)
    }

    fun selectRoute(routeId: String) {
        _state.update { current ->
            current.withWeatherAdvisory(
                selectedRouteId = routeId,
                routes = current.routes,
            )
        }
    }

    private fun observeRoutes(mode: RoutingMode, origin: String, destination: String) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            getRoutesUseCase.observe(origin, destination, mode).collectLatest { routes ->
                _state.update { current ->
                    val selected = current.selectedRouteId
                    val fallbackId = routes.firstOrNull()?.id
                    val resolvedSelectedRouteId = when {
                        fallbackId == null -> null
                        selected == null -> fallbackId
                        routes.any { it.id == selected } -> selected
                        else -> fallbackId
                    }
                    current.withWeatherAdvisory(
                        selectedRouteId = resolvedSelectedRouteId,
                        routes = routes,
                    ).copy(
                        loading = false,
                    )
                }
            }
        }
    }

    private fun refresh(mode: RoutingMode, origin: String, destination: String) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            getRoutesUseCase.refresh(origin, destination, mode)
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            loading = false,
                            routes = emptyList(),
                            selectedRouteId = null,
                            alternateRoutes = emptyList(),
                            weatherAdvisory = null,
                            error = err.message ?: "Route refresh failed",
                        )
                    }
                }
        }
    }

    private companion object {
        const val DEFAULT_ORIGIN = "Current Location"
        const val DEFAULT_DESTINATION = "Connaught Place"
        const val AUTOCOMPLETE_DEBOUNCE_MS = 250L
        const val EXTREME_WEATHER_RISK_THRESHOLD = 0.66f
        const val ALTERNATE_ROUTE_MIN_RISK_IMPROVEMENT = 0.10f
        const val MAX_ALTERNATE_ROUTES = 2
    }

    private fun RouteSelectionUiState.withWeatherAdvisory(
        selectedRouteId: String?,
        routes: List<RouteOption>,
    ): RouteSelectionUiState {
        val selected = selectedRouteId?.let { routeId -> routes.firstOrNull { it.id == routeId } }
        if (selected == null) {
            return copy(
                routes = routes,
                selectedRouteId = selectedRouteId,
                weatherAdvisory = null,
                alternateRoutes = emptyList(),
            )
        }

        val selectedSeverity = selected.weather?.severity
        val selectedRisk = selected.weatherRiskScore.coerceIn(0f, 1f)
        val isExtreme = selectedSeverity == WeatherSeverity.HIGH ||
            selectedSeverity == WeatherSeverity.SEVERE ||
            selectedRisk >= EXTREME_WEATHER_RISK_THRESHOLD
        if (!isExtreme) {
            return copy(
                routes = routes,
                selectedRouteId = selectedRouteId,
                weatherAdvisory = null,
                alternateRoutes = emptyList(),
            )
        }

        val sortedAlternatives = routes
            .filter { it.id != selected.id }
            .sortedWith(
                compareBy<RouteOption> { it.weatherRiskScore }
                    .thenBy { it.etaMinutes }
            )
        val saferAlternatives = sortedAlternatives
            .filter { candidate ->
                val riskImprovement = selectedRisk - candidate.weatherRiskScore
                val severityImprovement = (selectedSeverity?.ordinal ?: WeatherSeverity.HIGH.ordinal) >
                    (candidate.weather?.severity?.ordinal ?: WeatherSeverity.LOW.ordinal)
                riskImprovement >= ALTERNATE_ROUTE_MIN_RISK_IMPROVEMENT || severityImprovement
            }
            .ifEmpty { sortedAlternatives }
            .take(MAX_ALTERNATE_ROUTES)

        val advisory = when (selectedSeverity) {
            WeatherSeverity.SEVERE -> "Severe weather expected on this route. Switch to a safer alternate route."
            WeatherSeverity.HIGH -> "High weather risk on this route. Alternate routes are recommended."
            else -> "Weather risk is elevated on this route. Consider alternate options."
        }

        return copy(
            routes = routes,
            selectedRouteId = selectedRouteId,
            weatherAdvisory = advisory,
            alternateRoutes = saferAlternatives,
        )
    }

    private fun requestOriginSuggestions(query: String) {
        originSuggestionJob?.cancel()
        if (query.trim().isBlank()) {
            _state.update { it.copy(originSuggestions = emptyList(), originExpanded = false) }
            return
        }

        originSuggestionJob = viewModelScope.launch {
            delay(AUTOCOMPLETE_DEBOUNCE_MS)
            val result = locationSuggestionProvider.suggestLocations(query = query, exclude = _state.value.destination)
            if (query != _state.value.origin) return@launch

            result
                .onSuccess { suggestions ->
                    _state.update {
                        it.copy(
                            originSuggestions = suggestions,
                            originExpanded = suggestions.isNotEmpty(),
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            originSuggestions = emptyList(),
                            originExpanded = false,
                            error = "Location suggestions unavailable. Check Places API.",
                        )
                    }
                }
        }
    }

    private fun requestDestinationSuggestions(query: String) {
        destinationSuggestionJob?.cancel()
        if (query.trim().isBlank()) {
            _state.update { it.copy(destinationSuggestions = emptyList(), destinationExpanded = false) }
            return
        }

        destinationSuggestionJob = viewModelScope.launch {
            delay(AUTOCOMPLETE_DEBOUNCE_MS)
            val result = locationSuggestionProvider.suggestLocations(query = query, exclude = _state.value.origin)
            if (query != _state.value.destination) return@launch

            result
                .onSuccess { suggestions ->
                    _state.update {
                        it.copy(
                            destinationSuggestions = suggestions,
                            destinationExpanded = suggestions.isNotEmpty(),
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            destinationSuggestions = emptyList(),
                            destinationExpanded = false,
                            error = "Location suggestions unavailable. Check Places API.",
                        )
                    }
                }
        }
    }
}

data class RouteSelectionUiState(
    val loading: Boolean = false,
    val mode: RoutingMode = RoutingMode.FASTEST,
    val origin: String = "Current Location",
    val destination: String = "Connaught Place",
    val originSuggestions: List<String> = emptyList(),
    val destinationSuggestions: List<String> = emptyList(),
    val originExpanded: Boolean = false,
    val destinationExpanded: Boolean = false,
    val routes: List<RouteOption> = emptyList(),
    val selectedRouteId: String? = null,
    val weatherAdvisory: String? = null,
    val alternateRoutes: List<RouteOption> = emptyList(),
    val error: String? = null,
)

