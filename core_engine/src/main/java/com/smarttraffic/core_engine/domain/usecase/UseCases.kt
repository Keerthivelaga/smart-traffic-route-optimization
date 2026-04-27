package com.smarttraffic.core_engine.domain.usecase

import com.smarttraffic.core_engine.domain.model.IncidentReport
import com.smarttraffic.core_engine.domain.model.PredictionInput
import com.smarttraffic.core_engine.domain.model.RoutingMode
import com.smarttraffic.core_engine.domain.model.RouteOption
import com.smarttraffic.core_engine.domain.model.TrafficSnapshot
import com.smarttraffic.core_engine.domain.repository.RouteRepository
import com.smarttraffic.core_engine.domain.repository.TrafficRepository
import com.smarttraffic.core_engine.domain.repository.TripPoint
import com.smarttraffic.core_engine.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow

class ObserveTrafficUseCase(private val repository: TrafficRepository) {
    operator fun invoke(segmentId: String): Flow<TrafficSnapshot> = repository.observeLiveTraffic(segmentId)
}

class RefreshTrafficUseCase(private val repository: TrafficRepository) {
    suspend operator fun invoke(segmentId: String) = repository.fetchLiveTraffic(segmentId)
}

class FetchPredictionUseCase(private val repository: TrafficRepository) {
    suspend operator fun invoke(input: PredictionInput) = repository.predict(input)
}

class SubmitGpsUseCase(private val repository: TrafficRepository) {
    suspend operator fun invoke(userId: String, points: List<TripPoint>) = repository.submitGps(userId, points)
}

class GetRoutesUseCase(private val repository: RouteRepository) {
    fun observe(origin: String, destination: String, mode: RoutingMode): Flow<List<RouteOption>> {
        return repository.observeRoutes(origin, destination, mode)
    }

    suspend fun refresh(origin: String, destination: String, mode: RoutingMode) =
        repository.refreshRoutes(origin, destination, mode)

    suspend fun navigationRoute(
        origin: String,
        destination: String,
        mode: RoutingMode,
        selectedRouteId: String?,
    ) = repository.fetchNavigationRoute(
        origin = origin,
        destination = destination,
        mode = mode,
        selectedRouteId = selectedRouteId,
    )
}

class ReportIncidentUseCase(private val repository: UserRepository) {
    suspend operator fun invoke(report: IncidentReport) = repository.reportIncident(report)
}

class ObserveLeaderboardUseCase(private val repository: UserRepository) {
    operator fun invoke() = repository.observeLeaderboard()
}

class GetProfileUseCase(private val repository: UserRepository) {
    suspend operator fun invoke(userId: String) = repository.getProfile(userId)
}

