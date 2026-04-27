package com.smarttraffic.core_engine.domain.repository

import com.smarttraffic.core_engine.domain.model.IncidentReport
import com.smarttraffic.core_engine.domain.model.LeaderboardEntry
import com.smarttraffic.core_engine.domain.model.NavigationRoute
import com.smarttraffic.core_engine.domain.model.PredictionInput
import com.smarttraffic.core_engine.domain.model.PredictionResult
import com.smarttraffic.core_engine.domain.model.RouteOption
import com.smarttraffic.core_engine.domain.model.RoutingMode
import com.smarttraffic.core_engine.domain.model.TrafficSnapshot
import com.smarttraffic.core_engine.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow

interface TrafficRepository {
    fun observeLiveTraffic(segmentId: String): Flow<TrafficSnapshot>
    suspend fun fetchLiveTraffic(segmentId: String): Result<TrafficSnapshot>
    suspend fun predict(input: PredictionInput): Result<PredictionResult>
    suspend fun submitGps(userId: String, samples: List<TripPoint>): Result<Unit>
}

interface RouteRepository {
    fun observeRoutes(origin: String, destination: String, mode: RoutingMode): Flow<List<RouteOption>>
    suspend fun refreshRoutes(origin: String, destination: String, mode: RoutingMode): Result<List<RouteOption>>
    suspend fun fetchNavigationRoute(
        origin: String,
        destination: String,
        mode: RoutingMode,
        selectedRouteId: String?,
    ): Result<NavigationRoute>
}

interface UserRepository {
    fun observeLeaderboard(): Flow<List<LeaderboardEntry>>
    suspend fun getProfile(userId: String): Result<UserProfile>
    suspend fun reportIncident(report: IncidentReport): Result<Unit>
}

data class TripPoint(
    val timestampIso: String,
    val latitude: Double,
    val longitude: Double,
    val speedKph: Float,
    val headingDeg: Float,
    val accuracyMeters: Float,
)

