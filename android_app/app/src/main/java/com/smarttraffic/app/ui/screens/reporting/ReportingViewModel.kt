package com.smarttraffic.app.ui.screens.reporting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.app.session.NavigationSessionStore
import com.smarttraffic.core_engine.data.location.DeviceLocationResolver
import com.smarttraffic.core_engine.domain.model.IncidentReport
import com.smarttraffic.core_engine.domain.usecase.ReportIncidentUseCase
import com.smarttraffic.core_engine.security.SecureTokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ReportingViewModel @Inject constructor(
    private val reportIncidentUseCase: ReportIncidentUseCase,
    private val locationResolver: DeviceLocationResolver,
    private val navigationSessionStore: NavigationSessionStore,
    private val secureTokenStore: SecureTokenStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportingUiState())
    val state: StateFlow<ReportingUiState> = _state.asStateFlow()

    init {
        refreshAuthState()
    }

    fun refreshAuthState() {
        val authenticated = !secureTokenStore.currentUserId().isNullOrBlank()
        _state.update {
            it.copy(
                isAuthenticated = authenticated,
                result = if (authenticated) it.result else LOGIN_REQUIRED_MESSAGE,
            )
        }
    }

    fun updateType(value: String) {
        _state.update { it.copy(type = value, result = null) }
    }

    fun updateSeverity(value: Float) {
        _state.update { it.copy(severity = value.toInt().coerceIn(1, 5), result = null) }
    }

    fun updateMessage(value: String) {
        _state.update { it.copy(message = value, result = null) }
    }

    fun updateUseCurrentLocation(enabled: Boolean) {
        _state.update { it.copy(useCurrentLocation = enabled, result = null) }
    }

    fun updateLatitude(value: String) {
        _state.update { it.copy(latitude = value, result = null) }
    }

    fun updateLongitude(value: String) {
        _state.update { it.copy(longitude = value, result = null) }
    }

    fun submit() {
        val current = _state.value
        if (!current.isAuthenticated) {
            _state.update { it.copy(submitting = false, result = LOGIN_REQUIRED_MESSAGE) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, result = null) }
            val coordinates = resolveReportCoordinates(current)
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            result = error.message ?: "Unable to resolve report coordinates",
                        )
                    }
                }
                .getOrNull() ?: return@launch

            val segmentId = navigationSessionStore.state.value.segmentId.ifBlank { DEFAULT_SEGMENT_ID }
            val payload = IncidentReport(
                reportId = UUID.randomUUID().toString(),
                segmentId = segmentId,
                type = current.type.lowercase().replace(" ", "_"),
                severity = current.severity,
                latitude = coordinates.first,
                longitude = coordinates.second,
                message = current.message.trim(),
            )

            val result = reportIncidentUseCase(payload)
            _state.update {
                it.copy(
                    submitting = false,
                    result = if (result.isSuccess) {
                        "Report submitted from ${"%.5f".format(coordinates.first)}, ${"%.5f".format(coordinates.second)}."
                    } else {
                        result.exceptionOrNull()?.message ?: "Submission failed"
                    },
                )
            }
        }
    }

    private suspend fun resolveReportCoordinates(state: ReportingUiState): Result<Pair<Double, Double>> {
        if (state.useCurrentLocation) {
            return locationResolver.resolveCurrentLocationLatLng().mapCatching { latLng ->
                val parts = latLng.split(",")
                require(parts.size == 2) { "Invalid location format returned by resolver." }
                val lat = parts[0].trim().toDouble()
                val lng = parts[1].trim().toDouble()
                lat to lng
            }
        }

        return runCatching {
            val latitude = state.latitude.trim().toDouble()
            val longitude = state.longitude.trim().toDouble()
            require(latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                "Latitude/longitude values are out of valid range."
            }
            latitude to longitude
        }
    }

    private companion object {
        const val DEFAULT_SEGMENT_ID = "seg_0001"
        const val LOGIN_REQUIRED_MESSAGE = "Please login to report incidents."
    }
}

data class ReportingUiState(
    val isAuthenticated: Boolean = false,
    val type: String = REPORT_TYPES.first(),
    val severity: Int = 3,
    val message: String = "",
    val useCurrentLocation: Boolean = true,
    val latitude: String = "",
    val longitude: String = "",
    val submitting: Boolean = false,
    val result: String? = null,
)

val REPORT_TYPES = listOf(
    "Accident",
    "Vehicle Breakdown",
    "Roadblock",
    "Pothole",
    "Construction",
    "Flooding",
    "Signal Failure",
    "Lane Closure",
    "Hazard",
    "Police Checkpoint",
)

