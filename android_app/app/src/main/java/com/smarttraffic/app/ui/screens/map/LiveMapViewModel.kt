package com.smarttraffic.app.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.app.session.NavigationSessionStore
import com.smarttraffic.core_engine.core.NetworkMonitor
import com.smarttraffic.core_engine.data.location.DeviceLocationResolver
import com.smarttraffic.core_engine.domain.model.PredictionInput
import com.smarttraffic.core_engine.domain.model.TrafficSnapshot
import com.smarttraffic.core_engine.domain.repository.TripPoint
import com.smarttraffic.core_engine.domain.usecase.FetchPredictionUseCase
import com.smarttraffic.core_engine.domain.usecase.ObserveTrafficUseCase
import com.smarttraffic.core_engine.domain.usecase.RefreshTrafficUseCase
import com.smarttraffic.core_engine.domain.usecase.SubmitGpsUseCase
import com.smarttraffic.core_engine.security.SecureTokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@HiltViewModel
class LiveMapViewModel @Inject constructor(
    private val observeTrafficUseCase: ObserveTrafficUseCase,
    private val refreshTrafficUseCase: RefreshTrafficUseCase,
    private val fetchPredictionUseCase: FetchPredictionUseCase,
    private val submitGpsUseCase: SubmitGpsUseCase,
    private val locationResolver: DeviceLocationResolver,
    private val navigationSessionStore: NavigationSessionStore,
    private val networkMonitor: NetworkMonitor,
    private val secureTokenStore: SecureTokenStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveMapUiState())
    val state: StateFlow<LiveMapUiState> = _state.asStateFlow()

    private var pollingJob: Job? = null
    private var refreshAndUploadJob: Job? = null
    private var lastGpsUploadEpochMs: Long = 0L
    private var activeSegmentId: String = DEFAULT_SEGMENT_ID
    private var previousUpload: UploadPoint? = null

    init {
        observeConnection()
        observeRouteSession()
        startTrafficStreaming(activeSegmentId)
    }

    fun startTrafficStreaming(segmentId: String = DEFAULT_SEGMENT_ID) {
        pollingJob?.cancel()
        refreshAndUploadJob?.cancel()
        pollingJob = viewModelScope.launch {
            observeTrafficUseCase(segmentId).collectLatest { snapshot ->
                if (snapshot.isPlaceholder()) return@collectLatest
                _state.value = _state.value.copy(snapshot = snapshot)
            }
        }

        refreshAndUploadJob = viewModelScope.launch {
            while (true) {
                refreshTraffic(segmentId)
                refreshPrediction(segmentId)
                val now = System.currentTimeMillis()
                if (now - lastGpsUploadEpochMs >= GPS_UPLOAD_INTERVAL_MS) {
                    uploadDeviceGps()
                    lastGpsUploadEpochMs = now
                }
                delay(PREDICTION_REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshTraffic(segmentId: String) {
        refreshTrafficUseCase(segmentId)
            .onSuccess { snapshot ->
                _state.value = _state.value.copy(snapshot = snapshot)
            }
            .onFailure {
                _state.value = _state.value.copy(snapshot = seedSnapshotFromSession(segmentId))
            }
    }

    private suspend fun refreshPrediction(segmentId: String) {
        val snapshot = _state.value.snapshot
        val session = navigationSessionStore.state.value
        val features = buildList {
            add(snapshot.congestionScore.coerceIn(0f, 1f))
            add((snapshot.avgSpeedKph / 120f).coerceIn(0f, 1f))
            add(snapshot.confidence.coerceIn(0f, 1f))
            add(snapshot.anomalyScore.coerceIn(0f, 1f))
            add(session.progress.coerceIn(0f, 1f))
            add(session.congestionScore.coerceIn(0f, 1f))
        }
        fetchPredictionUseCase(
            PredictionInput(
                cacheKey = "map_${segmentId}_${System.currentTimeMillis() / 5000}",
                segmentId = segmentId,
                horizonMinutes = 15,
                features = features,
            )
        ).onSuccess { result ->
            _state.value = _state.value.copy(predictedCongestion = result.value)
        }
    }

    private fun seedSnapshotFromSession(segmentId: String): TrafficSnapshot {
        val session = navigationSessionStore.state.value
        val currentSnapshot = _state.value.snapshot
        val congestion = when {
            session.active -> session.congestionScore.coerceIn(0f, 1f)
            else -> currentSnapshot.congestionScore.coerceIn(0f, 1f)
        }
        val avgSpeed = (75f - congestion * 48f).coerceIn(8f, 95f)
        val confidence = if (session.active) 0.78f else 0.54f
        val anomaly = (congestion * 0.35f).coerceIn(0.03f, 0.8f)
        return TrafficSnapshot(
            segmentId = segmentId,
            congestionScore = congestion,
            confidence = confidence,
            avgSpeedKph = avgSpeed,
            anomalyScore = anomaly,
            timestampIso = Instant.now().toString(),
        )
    }

    private suspend fun uploadDeviceGps() {
        val currentUserId = secureTokenStore.currentUserId()
        if (currentUserId.isNullOrBlank()) {
            updateCloudSyncStatus(
                state = CloudSyncState.REQUIRES_LOGIN,
                message = "Login with email to sync live location to cloud.",
            )
            return
        }

        if (!_state.value.isOnline) {
            updateCloudSyncStatus(
                state = CloudSyncState.ERROR,
                message = "Offline. Live location will sync when connection returns.",
            )
            return
        }

        updateCloudSyncStatus(
            state = CloudSyncState.UPLOADING,
            message = "Uploading live location...",
        )

        val latLng = locationResolver.resolveCurrentLocationLatLng().getOrElse { error ->
            updateCloudSyncStatus(
                state = CloudSyncState.LOCATION_UNAVAILABLE,
                message = error.message ?: "Unable to read current location on this device.",
            )
            return
        }

        val parts = latLng.split(",")
        if (parts.size != 2) {
            updateCloudSyncStatus(
                state = CloudSyncState.ERROR,
                message = "Invalid location payload generated by device resolver.",
            )
            return
        }

        val latitude = parts[0].trim().toDoubleOrNull()
        val longitude = parts[1].trim().toDoubleOrNull()
        if (latitude == null || longitude == null) {
            updateCloudSyncStatus(
                state = CloudSyncState.ERROR,
                message = "Unable to parse current coordinates.",
            )
            return
        }

        val nowMs = System.currentTimeMillis()
        val speedKph = previousUpload?.let { previous ->
            val distanceMeters = haversineMeters(previous.latitude, previous.longitude, latitude, longitude)
            val deltaHours = (nowMs - previous.timestampMs).coerceAtLeast(1L) / 3_600_000f
            (distanceMeters / 1000f / deltaHours).coerceIn(0f, 140f)
        } ?: _state.value.snapshot.avgSpeedKph.coerceAtLeast(0f)
        val heading = previousUpload?.let { previous ->
            bearingDegrees(previous.latitude, previous.longitude, latitude, longitude)
        } ?: 0f

        val nowIso = Instant.now().toString()
        val points = listOf(
            TripPoint(
                timestampIso = nowIso,
                latitude = latitude,
                longitude = longitude,
                speedKph = speedKph,
                headingDeg = heading,
                accuracyMeters = DEFAULT_ACCURACY_METERS,
            ),
        )

        submitGpsUseCase(userId = currentUserId, points = points)
            .onSuccess {
                previousUpload = UploadPoint(
                    latitude = latitude,
                    longitude = longitude,
                    timestampMs = nowMs,
                )
                updateCloudSyncStatus(
                    state = CloudSyncState.SYNCED,
                    message = "Live location synced to cloud.",
                    syncEpochMs = nowMs,
                )
            }
            .onFailure { error ->
                updateCloudSyncStatus(
                    state = CloudSyncState.ERROR,
                    message = error.message ?: "Failed to upload live location.",
                )
            }
    }

    private fun updateCloudSyncStatus(
        state: CloudSyncState,
        message: String,
        syncEpochMs: Long = _state.value.lastCloudSyncEpochMs,
    ) {
        _state.value = _state.value.copy(
            cloudSyncState = state,
            cloudSyncMessage = message,
            lastCloudSyncEpochMs = syncEpochMs,
        )
    }

    private fun observeConnection() {
        viewModelScope.launch {
            networkMonitor.observeOnline().collectLatest { online ->
                val previous = _state.value
                _state.value = previous.copy(isOnline = online)
                if (!online) {
                    updateCloudSyncStatus(
                        state = CloudSyncState.ERROR,
                        message = "Offline. Cloud sync paused.",
                    )
                } else if (previous.cloudSyncState == CloudSyncState.ERROR && previous.cloudSyncMessage.contains("Offline")) {
                    updateCloudSyncStatus(
                        state = CloudSyncState.IDLE,
                        message = "Online. Live sync will resume automatically.",
                    )
                }
            }
        }
    }

    private fun observeRouteSession() {
        viewModelScope.launch {
            navigationSessionStore.state.collectLatest { session ->
                val segmentId = session.segmentId.ifBlank { DEFAULT_SEGMENT_ID }
                if (segmentId != activeSegmentId) {
                    activeSegmentId = segmentId
                    startTrafficStreaming(activeSegmentId)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        refreshAndUploadJob?.cancel()
    }

    private data class UploadPoint(
        val latitude: Double,
        val longitude: Double,
        val timestampMs: Long,
    )

    private companion object {
        const val PREDICTION_REFRESH_INTERVAL_MS = 15_000L
        const val GPS_UPLOAD_INTERVAL_MS = 90_000L
        const val DEFAULT_SEGMENT_ID = "seg_0001"
        const val DEFAULT_ACCURACY_METERS = 10f
    }
}

enum class CloudSyncState {
    IDLE,
    UPLOADING,
    SYNCED,
    REQUIRES_LOGIN,
    LOCATION_UNAVAILABLE,
    ERROR,
}

data class LiveMapUiState(
    val isOnline: Boolean = true,
    val snapshot: TrafficSnapshot = TrafficSnapshot(
        segmentId = "seg_0001",
        congestionScore = 0.42f,
        confidence = 0.89f,
        avgSpeedKph = 39f,
        anomalyScore = 0.11f,
        timestampIso = "",
    ),
    val predictedCongestion: Float = 0.48f,
    val cloudSyncState: CloudSyncState = CloudSyncState.IDLE,
    val cloudSyncMessage: String = "Cloud sync idle.",
    val lastCloudSyncEpochMs: Long = 0L,
)

private fun TrafficSnapshot.isPlaceholder(): Boolean {
    return timestampIso.isBlank() && confidence <= 0f && avgSpeedKph <= 0f
}

private fun haversineMeters(
    startLat: Double,
    startLon: Double,
    endLat: Double,
    endLon: Double,
): Float {
    val earthRadiusMeters = 6_371_000.0
    val dLat = Math.toRadians(endLat - startLat)
    val dLon = Math.toRadians(endLon - startLon)
    val lat1 = Math.toRadians(startLat)
    val lat2 = Math.toRadians(endLat)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2) *
        kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return (earthRadiusMeters * c).toFloat()
}

private fun bearingDegrees(
    startLat: Double,
    startLon: Double,
    endLat: Double,
    endLon: Double,
): Float {
    val lat1 = Math.toRadians(startLat)
    val lat2 = Math.toRadians(endLat)
    val dLon = Math.toRadians(endLon - startLon)
    val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2)
    val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
        kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLon)
    val bearing = Math.toDegrees(kotlin.math.atan2(y, x))
    val normalized = (bearing + 360.0) % 360.0
    return normalized.toFloat()
}
