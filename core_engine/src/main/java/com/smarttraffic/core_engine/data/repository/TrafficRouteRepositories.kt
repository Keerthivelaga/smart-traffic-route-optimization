package com.smarttraffic.core_engine.data.repository

import com.smarttraffic.core_engine.data.location.DeviceLocationResolver
import com.smarttraffic.core_engine.data.local.RouteDao
import com.smarttraffic.core_engine.data.local.RouteOptionEntity
import com.smarttraffic.core_engine.data.local.TrafficDao
import com.smarttraffic.core_engine.data.local.TrafficSnapshotEntity
import com.smarttraffic.core_engine.data.remote.BackendApi
import com.smarttraffic.core_engine.data.remote.DirectionsRouteDto
import com.smarttraffic.core_engine.data.remote.DirectionsStepDto
import com.smarttraffic.core_engine.data.remote.GpsBatchDto
import com.smarttraffic.core_engine.data.remote.GpsPointDto
import com.smarttraffic.core_engine.data.remote.InferenceApi
import com.smarttraffic.core_engine.data.remote.InferencePredictRequestDto
import com.smarttraffic.core_engine.data.remote.OpenMeteoApi
import com.smarttraffic.core_engine.data.remote.OpenMeteoForecastDto
import com.smarttraffic.core_engine.data.remote.OpenMeteoHourlyDto
import com.smarttraffic.core_engine.data.remote.PredictionRequestDto
import com.smarttraffic.core_engine.data.remote.RouteDirectionsProvider
import com.smarttraffic.core_engine.data.remote.RouteDirectionsQuery
import com.smarttraffic.core_engine.di.IoDispatcher
import com.smarttraffic.core_engine.domain.model.GeoPoint
import com.smarttraffic.core_engine.domain.model.NavigationRoute
import com.smarttraffic.core_engine.domain.model.PredictionInput
import com.smarttraffic.core_engine.domain.model.PredictionResult
import com.smarttraffic.core_engine.domain.model.RouteWeatherCheckpoint
import com.smarttraffic.core_engine.domain.model.RouteWeatherSummary
import com.smarttraffic.core_engine.domain.model.RouteStep
import com.smarttraffic.core_engine.domain.model.RouteOption
import com.smarttraffic.core_engine.domain.model.RoutingMode
import com.smarttraffic.core_engine.domain.model.TrafficSnapshot
import com.smarttraffic.core_engine.domain.model.VoiceInstruction
import com.smarttraffic.core_engine.domain.model.WeatherSeverity
import com.smarttraffic.core_engine.domain.repository.RouteRepository
import com.smarttraffic.core_engine.domain.repository.TrafficRepository
import com.smarttraffic.core_engine.domain.repository.TripPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrafficRepositoryImpl @Inject constructor(
    private val backendApi: BackendApi,
    private val inferenceApi: InferenceApi,
    private val trafficDao: TrafficDao,
    private val leaderboardScoringEngine: LeaderboardScoringEngine,
    @IoDispatcher
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TrafficRepository {
    private data class CachedPrediction(val value: PredictionResult, val expiresAtMs: Long)
    private val predictionCache = ConcurrentHashMap<String, CachedPrediction>()

    override fun observeLiveTraffic(segmentId: String): Flow<TrafficSnapshot> {
        return trafficDao.observe(segmentId).map { entity ->
            entity?.toDomain() ?: TrafficSnapshot(
                segmentId = segmentId,
                congestionScore = 0f,
                confidence = 0f,
                avgSpeedKph = 0f,
                anomalyScore = 0f,
                timestampIso = "",
            )
        }
    }

    override suspend fun fetchLiveTraffic(segmentId: String): Result<TrafficSnapshot> = withContext(io) {
        runCatching {
            val response = backendApi.aggregate(segmentId)
            val dto = requireSuccessful(response) { "Aggregate request failed for $segmentId" }
            val snapshot = TrafficSnapshot(
                segmentId = dto.segmentId,
                congestionScore = dto.congestionScore,
                confidence = dto.confidence,
                avgSpeedKph = dto.avgSpeedKph,
                anomalyScore = dto.anomalyScore,
                timestampIso = dto.windowEnd,
            )
            trafficDao.upsert(snapshot.toEntity())
            snapshot
        }
    }

    override suspend fun predict(input: PredictionInput): Result<PredictionResult> = withContext(io) {
        runCatching {
            val cached = predictionCache[input.cacheKey]
            if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
                return@runCatching cached.value
            }

            val backendResult = backendApi.predict(
                PredictionRequestDto(
                    cacheKey = input.cacheKey,
                    segmentId = input.segmentId,
                    horizonMinutes = input.horizonMinutes,
                    features = input.features,
                )
            )

            if (backendResult.isSuccessful) {
                val value = requireSuccessful(backendResult) { "Backend prediction failed" }.prediction
                val result = PredictionResult(value = value, fallback = false)
                predictionCache[input.cacheKey] = CachedPrediction(result, System.currentTimeMillis() + 30_000)
                return@runCatching result
            }

            val nodeIdx = nodeIdFromSegment(input.segmentId)
            val sequence = List(12) { input.features }
            val infer = inferenceApi.predict(
                InferencePredictRequestDto(
                    cacheKey = input.cacheKey,
                    sequence = sequence,
                    nodeIdx = nodeIdx,
                )
            )
            val body = requireSuccessful(infer) { "Inference fallback failed" }
            val result = PredictionResult(value = body.prediction, fallback = body.fallback > 0f)
            predictionCache[input.cacheKey] = CachedPrediction(result, System.currentTimeMillis() + 30_000)
            result
        }
    }

    override suspend fun submitGps(userId: String, samples: List<TripPoint>): Result<Unit> = withContext(io) {
        runCatching {
            val request = GpsBatchDto(
                userId = userId,
                source = "mobile",
                points = samples.map {
                    GpsPointDto(
                        timestamp = it.timestampIso,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        speedKph = it.speedKph,
                        headingDeg = it.headingDeg,
                        accuracyM = it.accuracyMeters,
                    )
                },
            )
            val response = backendApi.ingestGps(request)
            val ingest = requireSuccessful(response) { "GPS ingest failed for user $userId" }
            val avgAccuracyMeters = samples.map { it.accuracyMeters }.average().toFloat().coerceAtLeast(1f)
            leaderboardScoringEngine.recordGpsContribution(
                userId = userId,
                accepted = ingest.accepted,
                rejected = ingest.rejected,
                anomalies = ingest.anomalies,
                avgAccuracyMeters = avgAccuracyMeters,
            )
            Unit
        }
    }

    private fun nodeIdFromSegment(segmentId: String): Int {
        return kotlin.math.abs(segmentId.hashCode()) % 1000
    }
}

private inline fun <T> requireSuccessful(response: Response<T>, lazyMessage: () -> String): T {
    if (!response.isSuccessful) {
        throw IllegalStateException("${lazyMessage()} [HTTP ${response.code()}]")
    }
    return response.body() ?: throw IllegalStateException("${lazyMessage()} [empty_body]")
}

@Singleton
class RouteRepositoryImpl @Inject constructor(
    private val routeDao: RouteDao,
    private val routeDirectionsProvider: RouteDirectionsProvider,
    private val openMeteoApi: OpenMeteoApi,
    private val locationResolver: DeviceLocationResolver,
    @IoDispatcher
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RouteRepository {
    private data class RouteCandidate(
        val option: RouteOption,
        val directionsRoute: DirectionsRouteDto,
    )

    private data class CachedRouteDetails(
        val routeId: String,
        val origin: String,
        val destination: String,
        val mode: RoutingMode,
        val option: RouteOption,
        val directionsRoute: DirectionsRouteDto,
        val cachedAtMs: Long,
    )

    private data class WeatherObservation(
        val observationTimeIso: String?,
        val temperatureC: Float?,
        val precipitationMm: Float?,
        val precipitationProbabilityPct: Int?,
        val weatherCode: Int?,
        val windSpeedKph: Float?,
        val condition: String,
    )

    private data class WeatherCheckpointTarget(
        val label: String,
        val point: GeoPoint,
        val etaOffsetMinutes: Int,
    )

    private data class CachedWeatherObservation(
        val value: WeatherObservation,
        val fetchedAtMs: Long,
    )

    private val selectedRouteCache = ConcurrentHashMap<String, CachedRouteDetails>()
    private val weatherCache = ConcurrentHashMap<String, CachedWeatherObservation>()

    override fun observeRoutes(origin: String, destination: String, mode: RoutingMode): Flow<List<RouteOption>> {
        return routeDao.observe(mode.name).map { rows ->
            val nowMs = System.currentTimeMillis()
            rows.map { row ->
                val persisted = row.toDomain()
                val cached = selectedRouteCache[persisted.id]
                    ?.takeIf { nowMs - it.cachedAtMs <= SELECTED_ROUTE_CACHE_TTL_MS }
                    ?.option
                if (cached != null) {
                    persisted.copy(
                        weather = cached.weather,
                        weatherRiskScore = cached.weatherRiskScore,
                    )
                } else {
                    persisted
                }
            }.sortedWith(mode.routeComparator())
        }
    }

    override suspend fun refreshRoutes(origin: String, destination: String, mode: RoutingMode): Result<List<RouteOption>> = withContext(io) {
        runCatching {
            val routeCandidates = resolveRouteCandidates(origin, destination, mode)
            val generated = routeCandidates.map { it.option }

            require(generated.isNotEmpty()) {
                "No routes found between source and destination"
            }

            val nowMs = System.currentTimeMillis()
            routeCandidates.forEach { candidate ->
                selectedRouteCache[candidate.option.id] = CachedRouteDetails(
                    routeId = candidate.option.id,
                    origin = origin.trim(),
                    destination = destination.trim(),
                    mode = mode,
                    option = candidate.option,
                    directionsRoute = candidate.directionsRoute,
                    cachedAtMs = nowMs,
                )
            }
            trimSelectedRouteCache(nowMs)
            routeDao.clearMode(mode.name)
            routeDao.upsertAll(generated.map { it.toEntity(mode) })
            generated
        }
    }

    override suspend fun fetchNavigationRoute(
        origin: String,
        destination: String,
        mode: RoutingMode,
        selectedRouteId: String?,
    ): Result<NavigationRoute> = withContext(io) {
        runCatching {
            val normalizedOrigin = origin.trim()
            val normalizedDestination = destination.trim()
            val selectedId = selectedRouteId?.takeIf { it.isNotBlank() }
            val nowMs = System.currentTimeMillis()

            val cached = selectedId
                ?.let { selectedRouteCache[it] }
                ?.takeIf { details ->
                    details.mode == mode &&
                        details.origin.equals(normalizedOrigin, ignoreCase = true) &&
                        details.destination.equals(normalizedDestination, ignoreCase = true) &&
                        nowMs - details.cachedAtMs <= SELECTED_ROUTE_CACHE_TTL_MS
                }
            if (cached != null) {
                return@runCatching cached.directionsRoute.toNavigationRoute(cached.option)
            }

            val candidates = resolveRouteCandidates(origin, destination, mode)
            require(candidates.isNotEmpty()) {
                "No routes found between source and destination"
            }
            val selected = selectedId
                ?.let { routeId -> candidates.firstOrNull { it.option.id == routeId } }
                ?: candidates.first()
            selectedRouteCache[selected.option.id] = CachedRouteDetails(
                routeId = selected.option.id,
                origin = normalizedOrigin,
                destination = normalizedDestination,
                mode = mode,
                option = selected.option,
                directionsRoute = selected.directionsRoute,
                cachedAtMs = nowMs,
            )
            trimSelectedRouteCache(nowMs)
            selected.directionsRoute.toNavigationRoute(selected.option)
        }
    }

    private suspend fun resolveRouteCandidates(
        origin: String,
        destination: String,
        mode: RoutingMode,
    ): List<RouteCandidate> {
        val normalizedOrigin = normalizeWaypoint(origin)
        val normalizedDestination = normalizeWaypoint(destination)
        require(normalizedOrigin.isNotBlank() && normalizedDestination.isNotBlank()) {
            "Source and destination are required"
        }

        val response = routeDirectionsProvider.fetchDirections(
            RouteDirectionsQuery(
                origin = normalizedOrigin,
                destination = normalizedDestination,
                trafficModel = mode.toTrafficModel(),
                avoid = mode.toAvoidParam(),
            )
        )
        val payload = requireSuccessful(response) { "Directions request failed" }
        if (payload.status != "OK") {
            val message = payload.errorMessage?.takeIf { it.isNotBlank() }
                ?: "Directions API status ${payload.status}"
            throw IllegalStateException(message)
        }

        val routeComparator = mode.routeComparator()
        val baseCandidates = payload.routes
            .mapIndexedNotNull { index, directionsRoute ->
                directionsRoute.toRouteOption(
                    index = index,
                    mode = mode,
                    origin = normalizedOrigin,
                    destination = normalizedDestination,
                )?.let { mapped ->
                    RouteCandidate(
                        option = mapped,
                        directionsRoute = directionsRoute,
                    )
                }
            }
        val enrichedCandidates = coroutineScope {
            baseCandidates.map { candidate ->
                async {
                    val weatherSummary = resolveRouteWeather(
                        route = candidate.directionsRoute,
                        routeEtaMinutes = candidate.option.etaMinutes,
                    )
                    val weatherRiskScore = weatherSummary?.riskScore ?: 0f
                    val adjustedConfidence = (
                        candidate.option.confidence - weatherRiskScore * WEATHER_CONFIDENCE_PENALTY
                        ).coerceIn(0.2f, 0.99f)
                    candidate.copy(
                        option = candidate.option.copy(
                            confidence = adjustedConfidence,
                            weather = weatherSummary,
                            weatherRiskScore = weatherRiskScore,
                        )
                    )
                }
            }.map { deferred -> deferred.await() }
        }

        val sortedCandidates = enrichedCandidates
            .sortedWith { left, right -> routeComparator.compare(left.option, right.option) }
        val primary = sortedCandidates.take(MAX_ROUTE_OPTIONS)
        return includeSaferAlternativeIfNeeded(
            sortedCandidates = sortedCandidates,
            topCandidates = primary,
            routeComparator = routeComparator,
        )
    }

    private fun includeSaferAlternativeIfNeeded(
        sortedCandidates: List<RouteCandidate>,
        topCandidates: List<RouteCandidate>,
        routeComparator: Comparator<RouteOption>,
    ): List<RouteCandidate> {
        if (topCandidates.isEmpty()) return emptyList()
        if (sortedCandidates.size <= topCandidates.size) return topCandidates

        val highestRiskTop = topCandidates.maxByOrNull { it.option.weatherRiskScore } ?: return topCandidates
        if (highestRiskTop.option.weatherRiskScore < EXTREME_WEATHER_THRESHOLD) {
            return topCandidates
        }

        val bestOutside = sortedCandidates
            .drop(topCandidates.size)
            .minByOrNull { it.option.weatherRiskScore }
            ?: return topCandidates
        val improvement = highestRiskTop.option.weatherRiskScore - bestOutside.option.weatherRiskScore
        if (improvement < MIN_ALT_WEATHER_IMPROVEMENT) {
            return topCandidates
        }

        val replaced = topCandidates
            .toMutableList()
            .apply {
                remove(highestRiskTop)
                add(bestOutside)
            }
        return replaced
            .sortedWith { left, right -> routeComparator.compare(left.option, right.option) }
            .take(MAX_ROUTE_OPTIONS)
    }

    private suspend fun normalizeWaypoint(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.equals(CURRENT_LOCATION_ALIAS, ignoreCase = true)) {
            locationResolver.resolveCurrentLocationLatLng()
                .getOrElse { error ->
                    throw IllegalStateException(
                        error.message ?: "Unable to resolve current location.",
                        error,
                    )
                }
        } else {
            trimmed
        }
    }

    private fun DirectionsRouteDto.toRouteOption(
        index: Int,
        mode: RoutingMode,
        origin: String,
        destination: String,
    ): RouteOption? {
        val leg = legs.firstOrNull() ?: return null
        val distanceMeters = leg.distance?.value ?: return null
        val baseDurationSeconds = leg.duration?.value ?: return null
        val trafficDurationSeconds = leg.durationInTraffic?.value ?: baseDurationSeconds
        if (distanceMeters <= 0 || baseDurationSeconds <= 0 || trafficDurationSeconds <= 0) {
            return null
        }

        val delayRatio = ((trafficDurationSeconds - baseDurationSeconds).toFloat() / baseDurationSeconds.toFloat())
            .coerceAtLeast(0f)
        val etaMinutes = maxOf(1, ceil(trafficDurationSeconds / 60.0).toInt())
        val distanceKm = distanceMeters / 1000f
        val confidence = (0.97f - delayRatio * 0.6f - index * 0.04f).coerceIn(0.2f, 0.99f)
        val routeSummary = summary?.trim().orEmpty()
        val title = if (routeSummary.isNotBlank()) {
            "Via $routeSummary"
        } else {
            "${mode.routePrefix()} ${index + 1}: ${compactLocation(origin)} to ${compactLocation(destination)}"
        }
        val idSeed = "${mode.name}|$origin|$destination|$routeSummary|$distanceMeters|$trafficDurationSeconds|$index"

        return RouteOption(
            id = UUID.nameUUIDFromBytes(idSeed.toByteArray()).toString(),
            title = title,
            etaMinutes = etaMinutes,
            distanceKm = distanceKm,
            confidence = confidence,
            mode = mode,
        )
    }

    private fun DirectionsRouteDto.toNavigationRoute(option: RouteOption): NavigationRoute {
        val stepDtos = legs.firstOrNull()?.steps.orEmpty()
        val routeSteps = stepDtos.toRouteSteps()
        val instructions = routeSteps
            .map { it.instruction }
            .ifEmpty { listOf(VoiceInstruction(text = "Continue to destination", distanceMeters = 0)) }
        val polylinePoints = decodePolyline(overviewPolyline?.points.orEmpty())
            .ifEmpty { routeSteps.flatMap { it.polyline }.dedupeNeighborPoints() }
            .ifEmpty { stepDtos.toFallbackPolyline() }
        val congestionScore = if (routeSteps.isNotEmpty()) {
            routeSteps.map { it.congestionScore }.average().toFloat()
        } else {
            estimateRouteCongestionFromDuration()
        }

        return NavigationRoute(
            routeId = option.id,
            title = option.title,
            etaMinutes = option.etaMinutes,
            distanceKm = option.distanceKm,
            polyline = polylinePoints,
            instructions = instructions,
            steps = routeSteps,
            congestionScore = congestionScore.coerceIn(0f, 1f),
            weather = option.weather,
        )
    }

    private fun DirectionsStepDto.toVoiceInstruction(): VoiceInstruction? {
        val distanceMeters = distance?.value?.coerceAtLeast(0) ?: 0
        val text = htmlInstructions.orEmpty().toPlainInstruction()
        if (text.isBlank() && distanceMeters == 0) return null
        return VoiceInstruction(
            text = if (text.isBlank()) "Continue straight" else text,
            distanceMeters = distanceMeters,
        )
    }

    private fun String.toPlainInstruction(): String {
        return this
            .replace(HTML_TAG_REGEX, " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun List<DirectionsStepDto>.toFallbackPolyline(): List<GeoPoint> {
        if (isEmpty()) return emptyList()
        val points = mutableListOf<GeoPoint>()
        forEach { step ->
            step.startLocation.toGeoPointOrNull()?.let { point ->
                if (points.lastOrNull() != point) points.add(point)
            }
        }
        lastOrNull()?.endLocation.toGeoPointOrNull()?.let { finalPoint ->
            if (points.lastOrNull() != finalPoint) points.add(finalPoint)
        }
        return points
    }

    private fun List<DirectionsStepDto>.toRouteSteps(): List<RouteStep> {
        if (isEmpty()) return emptyList()

        val stepMetrics = map { step ->
            val distanceMeters = step.distance?.value?.coerceAtLeast(0) ?: 0
            val stepPolyline = decodePolyline(step.polyline?.points.orEmpty())
                .ifEmpty {
                    val fallback = mutableListOf<GeoPoint>()
                    step.startLocation.toGeoPointOrNull()?.let(fallback::add)
                    step.endLocation.toGeoPointOrNull()?.let { point ->
                        if (fallback.lastOrNull() != point) fallback.add(point)
                    }
                    fallback
                }
                .dedupeNeighborPoints()
            val segmentDistanceMeters = if (distanceMeters > 0) {
                distanceMeters.toFloat()
            } else {
                stepPolyline.estimatedDistanceMeters()
            }
            val segmentDurationSeconds = (step.duration?.value?.toFloat() ?: 0f).coerceAtLeast(1f)
            Triple(step, segmentDistanceMeters, segmentDurationSeconds)
        }

        val speeds = stepMetrics.map { metric ->
            val distance = metric.second
            val duration = metric.third
            if (distance <= 1f) 0f else distance / duration.coerceAtLeast(1f)
        }
        val minSpeed = speeds.minOrNull() ?: 0f
        val maxSpeed = speeds.maxOrNull() ?: 0f
        val speedSpan = (maxSpeed - minSpeed).coerceAtLeast(0.0001f)

        return stepMetrics.mapIndexedNotNull { index, metric ->
            val step = metric.first
            val distanceMeters = (step.distance?.value?.coerceAtLeast(0) ?: metric.second.toInt()).coerceAtLeast(0)
            val instruction = step.toVoiceInstruction() ?: VoiceInstruction(
                text = "Continue straight",
                distanceMeters = distanceMeters,
            )
            val speed = speeds.getOrNull(index) ?: 0f
            val congestion = (1f - ((speed - minSpeed) / speedSpan)).coerceIn(0f, 1f)
            val polyline = decodePolyline(step.polyline?.points.orEmpty())
                .ifEmpty {
                    listOfNotNull(
                        step.startLocation.toGeoPointOrNull(),
                        step.endLocation.toGeoPointOrNull(),
                    )
                }
                .dedupeNeighborPoints()

            RouteStep(
                instruction = instruction,
                start = step.startLocation.toGeoPointOrNull(),
                end = step.endLocation.toGeoPointOrNull(),
                polyline = polyline,
                congestionScore = congestion,
            )
        }
    }

    private fun decodePolyline(encoded: String): List<GeoPoint> {
        if (encoded.isBlank()) return emptyList()
        val points = mutableListOf<GeoPoint>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            var shift = 0
            var result = 0
            var byte: Int
            do {
                if (index >= encoded.length) return points
                byte = encoded[index++].code - 63
                result = result or ((byte and 0x1f) shl shift)
                shift += 5
            } while (byte >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

            shift = 0
            result = 0
            do {
                if (index >= encoded.length) return points
                byte = encoded[index++].code - 63
                result = result or ((byte and 0x1f) shl shift)
                shift += 5
            } while (byte >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

            points.add(
                GeoPoint(
                    latitude = lat / POLYLINE_PRECISION,
                    longitude = lng / POLYLINE_PRECISION,
                )
            )
        }
        return points
    }

    private fun DirectionsRouteDto.estimateRouteCongestionFromDuration(): Float {
        val leg = legs.firstOrNull() ?: return 0.5f
        val baseDuration = leg.duration?.value?.coerceAtLeast(1) ?: 1
        val trafficDuration = leg.durationInTraffic?.value?.coerceAtLeast(baseDuration) ?: baseDuration
        val delayRatio = ((trafficDuration - baseDuration).toFloat() / baseDuration.toFloat()).coerceAtLeast(0f)
        return (delayRatio / 0.8f).coerceIn(0f, 1f)
    }

    private fun RoutingMode.toTrafficModel(): String = when (this) {
        RoutingMode.FASTEST -> "best_guess"
        RoutingMode.FUEL_EFFICIENT -> "best_guess"
        RoutingMode.LOW_TRAFFIC -> "pessimistic"
        RoutingMode.SCENIC -> "best_guess"
    }

    private fun RoutingMode.toAvoidParam(): String? = when (this) {
        RoutingMode.FASTEST -> null
        RoutingMode.FUEL_EFFICIENT -> "tolls"
        RoutingMode.LOW_TRAFFIC -> null
        RoutingMode.SCENIC -> "highways"
    }

    private fun RoutingMode.routeComparator(): Comparator<RouteOption> = when (this) {
        RoutingMode.FASTEST -> compareBy<RouteOption> { it.etaMinutes }
            .thenBy { it.weatherRiskScore }
            .thenBy { it.distanceKm }
        RoutingMode.FUEL_EFFICIENT -> compareBy<RouteOption> { it.distanceKm }
            .thenBy { it.weatherRiskScore }
            .thenBy { it.etaMinutes }
        RoutingMode.LOW_TRAFFIC -> compareBy<RouteOption> { it.weatherRiskScore }
            .thenByDescending { it.confidence }
            .thenBy { it.etaMinutes }
        RoutingMode.SCENIC -> compareBy<RouteOption> { it.weatherRiskScore }
            .thenByDescending { it.distanceKm }
            .thenBy { it.etaMinutes }
    }

    private fun RoutingMode.routePrefix(): String = when (this) {
        RoutingMode.FASTEST -> "Fast Route"
        RoutingMode.FUEL_EFFICIENT -> "Eco Route"
        RoutingMode.LOW_TRAFFIC -> "Low-Traffic Route"
        RoutingMode.SCENIC -> "Scenic Route"
    }

    private fun compactLocation(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length <= 18) return trimmed
        return trimmed.take(15).trimEnd() + "..."
    }

    private suspend fun resolveRouteWeather(
        route: DirectionsRouteDto,
        routeEtaMinutes: Int,
    ): RouteWeatherSummary? {
        val checkpoints = buildWeatherCheckpointTargets(
            route = route,
            routeEtaMinutes = routeEtaMinutes,
        )
        if (checkpoints.isEmpty()) return null

        val resolvedCheckpoints = coroutineScope {
            checkpoints.map { checkpoint ->
                async {
                    fetchWeatherObservation(
                        point = checkpoint.point,
                        etaOffsetMinutes = checkpoint.etaOffsetMinutes,
                    )
                        .getOrNull()
                        ?.toCheckpoint(label = checkpoint.label)
                }
            }.mapNotNull { deferred -> deferred.await() }
        }
        if (resolvedCheckpoints.isEmpty()) return null
        return resolvedCheckpoints.toSummary(routeEtaMinutes = routeEtaMinutes)
    }

    private fun buildWeatherCheckpointTargets(
        route: DirectionsRouteDto,
        routeEtaMinutes: Int,
    ): List<WeatherCheckpointTarget> {
        val polyline = decodePolyline(route.overviewPolyline?.points.orEmpty())
            .ifEmpty { route.legs.firstOrNull()?.steps.orEmpty().toFallbackPolyline() }
            .dedupeNeighborPoints()
        if (polyline.isEmpty()) return emptyList()

        val cumulativeDistances = FloatArray(polyline.size)
        for (idx in 1 until polyline.size) {
            cumulativeDistances[idx] = cumulativeDistances[idx - 1] + haversineMeters(polyline[idx - 1], polyline[idx])
        }
        val totalDistanceMeters = cumulativeDistances.lastOrNull()?.coerceAtLeast(1f) ?: 1f
        val estimatedCount = ((totalDistanceMeters / WEATHER_SAMPLE_EVERY_METERS).toInt() + 1)
            .coerceIn(MIN_WEATHER_CHECKPOINTS, MAX_WEATHER_CHECKPOINTS)
        val divisor = (estimatedCount - 1).coerceAtLeast(1)

        val targets = (0 until estimatedCount).map { idx ->
            val fraction = idx.toFloat() / divisor.toFloat()
            val targetDistance = totalDistanceMeters * fraction
            val pointIdx = cumulativeDistances.indexOfFirst { it >= targetDistance }
                .let { resolved -> if (resolved >= 0) resolved else polyline.lastIndex }
            val label = when (idx) {
                0 -> "Start"
                estimatedCount - 1 -> "Destination"
                else -> "Route ${(fraction * 100f).roundToInt()}%"
            }
            WeatherCheckpointTarget(
                label = label,
                point = polyline[pointIdx],
                etaOffsetMinutes = (routeEtaMinutes.coerceAtLeast(1) * fraction).roundToInt().coerceAtLeast(0),
            )
        }

        return targets
            .distinctBy { target ->
                val timeBucket = target.etaOffsetMinutes / WEATHER_FORECAST_BUCKET_MINUTES
                "${weatherCacheKey(target.point)}|$timeBucket"
            }
    }

    private suspend fun fetchWeatherObservation(
        point: GeoPoint,
        etaOffsetMinutes: Int,
    ): Result<WeatherObservation> {
        val nowMs = System.currentTimeMillis()
        val targetEpochMs = nowMs + etaOffsetMinutes.coerceAtLeast(0) * MINUTE_MS
        val forecastBucket = targetEpochMs / WEATHER_FORECAST_BUCKET_MS
        val cacheKey = "${weatherCacheKey(point)}|$forecastBucket"
        val cached = weatherCache[cacheKey]
        if (cached != null && nowMs - cached.fetchedAtMs <= WEATHER_CACHE_TTL_MS) {
            return Result.success(cached.value)
        }

        var lastError: Throwable? = null
        repeat(WEATHER_RETRY_COUNT) { attempt ->
            val result = runCatching {
                val forecastDays = ((etaOffsetMinutes.coerceAtLeast(0) / MINUTES_PER_DAY) + 1)
                    .coerceIn(1, MAX_WEATHER_FORECAST_DAYS)
                val response = openMeteoApi.getCurrentWeather(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    forecastDays = forecastDays,
                )
                if (!response.isSuccessful) {
                    val code = response.code()
                    if (code in RETRIABLE_HTTP_CODES) {
                        throw IOException("Weather API temporary failure [HTTP $code]")
                    }
                    throw IllegalStateException("Weather API request failed [HTTP $code]")
                }

                val body = response.body()
                    ?: throw IllegalStateException("Weather API returned empty response")
                if (body.error == true) {
                    throw IllegalStateException(body.reason?.takeIf { it.isNotBlank() } ?: "Weather API returned an error")
                }

                body.resolveObservationFor(targetEpochMs)
                    ?: throw IllegalStateException("Weather API response missing usable weather data")
            }

            if (result.isSuccess) {
                val observation = result.getOrThrow()
                weatherCache[cacheKey] = CachedWeatherObservation(
                    value = observation,
                    fetchedAtMs = System.currentTimeMillis(),
                )
                trimWeatherCache(nowMs = System.currentTimeMillis())
                return Result.success(observation)
            }

            lastError = result.exceptionOrNull()
            if (!isRetriableWeatherError(lastError)) {
                return Result.failure(lastError ?: IllegalStateException("Weather request failed"))
            }
            if (attempt < WEATHER_RETRY_COUNT - 1 && attempt < WEATHER_RETRY_DELAYS_MS.size) {
                delay(WEATHER_RETRY_DELAYS_MS[attempt])
            }
        }

        return Result.failure(lastError ?: IllegalStateException("Weather request failed"))
    }

    private fun OpenMeteoForecastDto.resolveObservationFor(targetEpochMs: Long): WeatherObservation? {
        val zoneId = timezone
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { ZoneId.of(raw) }.getOrNull() }
            ?: ZoneOffset.UTC

        val hourlyObservation = hourly?.resolveNearestObservation(
            targetEpochMs = targetEpochMs,
            zoneId = zoneId,
        )
        if (hourlyObservation != null) {
            return hourlyObservation
        }

        val currentWeather = current ?: return null
        return WeatherObservation(
            observationTimeIso = currentWeather.time,
            temperatureC = currentWeather.temperature2m?.toFloat(),
            precipitationMm = currentWeather.precipitation?.toFloat(),
            precipitationProbabilityPct = currentWeather.precipitationProbability?.toInt()?.coerceIn(0, 100),
            weatherCode = currentWeather.weatherCode,
            windSpeedKph = currentWeather.windSpeed10m?.toFloat(),
            condition = weatherCodeDescription(currentWeather.weatherCode),
        )
    }

    private fun OpenMeteoHourlyDto.resolveNearestObservation(
        targetEpochMs: Long,
        zoneId: ZoneId,
    ): WeatherObservation? {
        val times = time.orEmpty()
        if (times.isEmpty()) return null

        var nearestIdx = -1
        var nearestDeltaMs = Long.MAX_VALUE
        times.forEachIndexed { idx, rawTime ->
            val epochMs = parseOpenMeteoTimeEpochMs(
                raw = rawTime,
                zoneId = zoneId,
            ) ?: return@forEachIndexed
            val delta = abs(targetEpochMs - epochMs)
            if (delta < nearestDeltaMs) {
                nearestDeltaMs = delta
                nearestIdx = idx
            }
        }

        if (nearestIdx < 0 || nearestDeltaMs > MAX_HOURLY_LOOKUP_DRIFT_MS) {
            return null
        }

        val resolvedCode = weatherCode.valueAt(nearestIdx)
        return WeatherObservation(
            observationTimeIso = times.valueAt(nearestIdx),
            temperatureC = temperature2m.valueAt(nearestIdx)?.toFloat(),
            precipitationMm = precipitation.valueAt(nearestIdx)?.toFloat(),
            precipitationProbabilityPct = precipitationProbability.valueAt(nearestIdx)?.toInt()?.coerceIn(0, 100),
            weatherCode = resolvedCode,
            windSpeedKph = windSpeed10m.valueAt(nearestIdx)?.toFloat(),
            condition = weatherCodeDescription(resolvedCode),
        )
    }

    private fun parseOpenMeteoTimeEpochMs(raw: String, zoneId: ZoneId): Long? {
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).atZone(zoneId).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun WeatherObservation.toCheckpoint(label: String): RouteWeatherCheckpoint {
        return RouteWeatherCheckpoint(
            label = label,
            condition = condition,
            weatherCode = weatherCode,
            observationTimeIso = observationTimeIso,
            temperatureC = temperatureC,
            precipitationMm = precipitationMm,
            precipitationProbabilityPct = precipitationProbabilityPct,
            windSpeedKph = windSpeedKph,
        )
    }

    private fun List<RouteWeatherCheckpoint>.toSummary(routeEtaMinutes: Int): RouteWeatherSummary {
        val boundedCheckpoints = if (isNotEmpty()) this else emptyList()
        val risks = boundedCheckpoints.map { checkpointRisk(it) }
        val maxRisk = risks.maxOrNull() ?: 0f
        val averageRisk = if (risks.isNotEmpty()) {
            risks.average().toFloat()
        } else {
            0f
        }
        val severeCheckpointRatio = if (risks.isNotEmpty()) {
            risks.count { it >= HIGH_WEATHER_RISK_THRESHOLD }.toFloat() / risks.size.toFloat()
        } else {
            0f
        }
        val routeExposureFactor = (
            1f + (routeEtaMinutes.coerceAtLeast(1).toFloat() / 60f) * WEATHER_EXPOSURE_WEIGHT
            ).coerceIn(1f, MAX_WEATHER_EXPOSURE_FACTOR)
        val blendedRisk = (
            maxRisk * WEATHER_MAX_RISK_WEIGHT +
                averageRisk * WEATHER_AVERAGE_RISK_WEIGHT +
                severeCheckpointRatio * WEATHER_SEVERE_RATIO_WEIGHT
            ) * routeExposureFactor
        val finalRisk = blendedRisk.coerceIn(0f, 1f)
        val worstIndex = risks.indices.maxByOrNull { idx -> risks[idx] } ?: 0
        val worst = boundedCheckpoints.getOrNull(worstIndex)
        val severity = finalRisk.toWeatherSeverity()
        val summary = buildWeatherSummaryText(
            severity = severity,
            worstCheckpoint = worst,
            routeEtaMinutes = routeEtaMinutes,
        )

        val avgTemperature = boundedCheckpoints
            .mapNotNull { it.temperatureC }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()
        val maxPrecipitationProbability = boundedCheckpoints
            .mapNotNull { it.precipitationProbabilityPct }
            .maxOrNull()
        val maxWindSpeed = boundedCheckpoints
            .mapNotNull { it.windSpeedKph }
            .maxOrNull()

        return RouteWeatherSummary(
            summary = summary,
            severity = severity,
            riskScore = finalRisk,
            checkpoints = boundedCheckpoints,
            averageTemperatureC = avgTemperature,
            maxPrecipitationProbabilityPct = maxPrecipitationProbability,
            maxWindSpeedKph = maxWindSpeed,
        )
    }

    private fun checkpointRisk(checkpoint: RouteWeatherCheckpoint): Float {
        val codeRisk = weatherCodeRisk(checkpoint.weatherCode)
        val precipProbabilityRisk = ((checkpoint.precipitationProbabilityPct ?: 0) / 100f).coerceIn(0f, 1f) * 0.9f
        val precipitationAmountRisk = ((checkpoint.precipitationMm ?: 0f) / 8f).coerceIn(0f, 1f) * 0.95f
        val windRisk = ((checkpoint.windSpeedKph ?: 0f) / 80f).coerceIn(0f, 1f) * 0.85f
        return maxOf(codeRisk, precipProbabilityRisk, precipitationAmountRisk, windRisk).coerceIn(0f, 1f)
    }

    private fun weatherCodeDescription(code: Int?): String {
        return when (code) {
            0 -> "Clear"
            1, 2 -> "Mostly clear"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63 -> "Rain"
            65 -> "Heavy rain"
            66, 67 -> "Freezing rain"
            71, 73 -> "Snow"
            75 -> "Heavy snow"
            77 -> "Snow grains"
            80, 81 -> "Rain showers"
            82 -> "Heavy showers"
            85 -> "Snow showers"
            86 -> "Heavy snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Severe thunderstorm"
            else -> "Variable weather"
        }
    }

    private fun weatherCodeRisk(code: Int?): Float {
        return when (code) {
            null -> 0.2f
            0 -> 0.08f
            1, 2 -> 0.14f
            3 -> 0.22f
            45, 48 -> 0.58f
            51, 53, 55, 56, 57 -> 0.5f
            61, 63, 66, 67, 71, 73, 77, 80, 81, 85 -> 0.65f
            65, 75, 82, 86 -> 0.82f
            95 -> 0.92f
            96, 99 -> 0.98f
            else -> 0.4f
        }
    }

    private fun buildWeatherSummaryText(
        severity: WeatherSeverity,
        worstCheckpoint: RouteWeatherCheckpoint?,
        routeEtaMinutes: Int,
    ): String {
        val label = worstCheckpoint?.label?.lowercase()?.takeIf { it.isNotBlank() } ?: "this route"
        val condition = worstCheckpoint?.condition ?: "weather"
        val precipProbability = worstCheckpoint?.precipitationProbabilityPct ?: 0
        val windSpeed = worstCheckpoint?.windSpeedKph ?: 0f
        val longExposure = routeEtaMinutes >= 45

        return when {
            severity == WeatherSeverity.SEVERE ->
                "Severe $condition expected near $label. Prefer an alternate route."
            severity == WeatherSeverity.HIGH && precipProbability >= 60 ->
                if (longExposure) "Heavy precipitation risk near $label with prolonged exposure." else "Heavy precipitation risk near $label."
            severity == WeatherSeverity.HIGH && windSpeed >= 45f ->
                if (longExposure) "Strong winds expected near $label for a long segment." else "Strong winds expected near $label."
            severity == WeatherSeverity.HIGH ->
                if (longExposure) "Adverse $condition likely along a long stretch of this route." else "Adverse $condition likely along the route."
            severity == WeatherSeverity.MODERATE ->
                if (longExposure) "Moderate $condition expected with sustained exposure." else "Moderate $condition expected on parts of this route."
            else -> "Mostly stable weather along this route."
        }
    }

    private fun Float.toWeatherSeverity(): WeatherSeverity {
        return when {
            this >= 0.86f -> WeatherSeverity.SEVERE
            this >= 0.66f -> WeatherSeverity.HIGH
            this >= 0.38f -> WeatherSeverity.MODERATE
            else -> WeatherSeverity.LOW
        }
    }

    private fun weatherCacheKey(point: GeoPoint): String {
        val lat = round(point.latitude * WEATHER_COORD_PRECISION) / WEATHER_COORD_PRECISION
        val lon = round(point.longitude * WEATHER_COORD_PRECISION) / WEATHER_COORD_PRECISION
        return "$lat,$lon"
    }

    private fun trimWeatherCache(nowMs: Long) {
        weatherCache.entries.removeIf { nowMs - it.value.fetchedAtMs > WEATHER_CACHE_TTL_MS }
        if (weatherCache.size <= MAX_WEATHER_CACHE_SIZE) return
        val overflow = weatherCache.size - MAX_WEATHER_CACHE_SIZE
        weatherCache.entries
            .sortedBy { it.value.fetchedAtMs }
            .take(overflow)
            .forEach { weatherCache.remove(it.key) }
    }

    private fun isRetriableWeatherError(error: Throwable?): Boolean {
        return when (error) {
            is UnknownHostException,
            is SocketTimeoutException,
            is ConnectException,
            is IOException -> true
            else -> false
        }
    }

    private companion object {
        const val MAX_ROUTE_OPTIONS = 3
        const val EXTREME_WEATHER_THRESHOLD = 0.66f
        const val MIN_ALT_WEATHER_IMPROVEMENT = 0.12f
        const val CURRENT_LOCATION_ALIAS = "Current Location"
        const val POLYLINE_PRECISION = 100000.0
        const val MAX_SELECTED_ROUTE_CACHE_SIZE = 80
        const val SELECTED_ROUTE_CACHE_TTL_MS = 10 * 60 * 1000L
        const val WEATHER_CONFIDENCE_PENALTY = 0.35f
        const val MIN_WEATHER_CHECKPOINTS = 4
        const val MAX_WEATHER_CHECKPOINTS = 8
        const val WEATHER_SAMPLE_EVERY_METERS = 3_500f
        const val WEATHER_FORECAST_BUCKET_MINUTES = 60
        const val WEATHER_FORECAST_BUCKET_MS = WEATHER_FORECAST_BUCKET_MINUTES * 60_000L
        const val MAX_WEATHER_FORECAST_DAYS = 3
        const val MAX_HOURLY_LOOKUP_DRIFT_MS = 8 * 60 * 60 * 1000L
        const val WEATHER_MAX_RISK_WEIGHT = 0.50f
        const val WEATHER_AVERAGE_RISK_WEIGHT = 0.32f
        const val WEATHER_SEVERE_RATIO_WEIGHT = 0.18f
        const val WEATHER_EXPOSURE_WEIGHT = 0.14f
        const val MAX_WEATHER_EXPOSURE_FACTOR = 1.35f
        const val HIGH_WEATHER_RISK_THRESHOLD = 0.66f
        const val WEATHER_COORD_PRECISION = 1000.0
        const val WEATHER_CACHE_TTL_MS = 10 * 60 * 1000L
        const val MAX_WEATHER_CACHE_SIZE = 400
        const val WEATHER_RETRY_COUNT = 3
        const val MINUTE_MS = 60_000L
        const val MINUTES_PER_DAY = 24 * 60
        val WEATHER_RETRY_DELAYS_MS = longArrayOf(250L, 700L)
        val RETRIABLE_HTTP_CODES = setOf(408, 429, 500, 502, 503, 504)
        val HTML_TAG_REGEX = Regex("<[^>]+>")
        val WHITESPACE_REGEX = Regex("\\s+")
    }

    private fun trimSelectedRouteCache(nowMs: Long) {
        selectedRouteCache.entries.removeIf { nowMs - it.value.cachedAtMs > SELECTED_ROUTE_CACHE_TTL_MS }
        if (selectedRouteCache.size <= MAX_SELECTED_ROUTE_CACHE_SIZE) return
        val overflow = selectedRouteCache.size - MAX_SELECTED_ROUTE_CACHE_SIZE
        selectedRouteCache.values
            .sortedBy { it.cachedAtMs }
            .take(overflow)
            .forEach { selectedRouteCache.remove(it.routeId) }
    }
}

private fun com.smarttraffic.core_engine.data.remote.DirectionsLocationDto?.toGeoPointOrNull(): GeoPoint? {
    val latitude = this?.lat ?: return null
    val longitude = this.lng ?: return null
    return GeoPoint(latitude = latitude, longitude = longitude)
}

private fun List<GeoPoint>.dedupeNeighborPoints(): List<GeoPoint> {
    if (isEmpty()) return emptyList()
    val result = ArrayList<GeoPoint>(size)
    for (point in this) {
        if (result.lastOrNull() != point) result.add(point)
    }
    return result
}

private fun List<GeoPoint>.estimatedDistanceMeters(): Float {
    if (size < 2) return 0f
    var total = 0f
    for (idx in 0 until size - 1) {
        total += haversineMeters(this[idx], this[idx + 1])
    }
    return total
}

private fun haversineMeters(start: GeoPoint, end: GeoPoint): Float {
    val earthRadiusMeters = 6_371_000.0
    val dLat = Math.toRadians(end.latitude - start.latitude)
    val dLon = Math.toRadians(end.longitude - start.longitude)
    val lat1 = Math.toRadians(start.latitude)
    val lat2 = Math.toRadians(end.latitude)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2) *
        kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return (earthRadiusMeters * c).toFloat()
}

private fun <T> List<T>?.valueAt(index: Int): T? {
    val values = this ?: return null
    return values.getOrNull(index)
}


private fun TrafficSnapshotEntity.toDomain() = TrafficSnapshot(
    segmentId = segmentId,
    congestionScore = congestionScore,
    confidence = confidence,
    avgSpeedKph = avgSpeedKph,
    anomalyScore = anomalyScore,
    timestampIso = timestampIso,
)

private fun TrafficSnapshot.toEntity() = TrafficSnapshotEntity(
    segmentId = segmentId,
    congestionScore = congestionScore,
    confidence = confidence,
    avgSpeedKph = avgSpeedKph,
    anomalyScore = anomalyScore,
    timestampIso = timestampIso,
    updatedAtEpochMs = System.currentTimeMillis(),
)

private fun RouteOptionEntity.toDomain() = RouteOption(
    id = id,
    title = title,
    etaMinutes = etaMinutes,
    distanceKm = distanceKm,
    confidence = confidence,
    mode = RoutingMode.valueOf(mode),
)

private fun RouteOption.toEntity(routeMode: RoutingMode) = RouteOptionEntity(
    id = id,
    title = title,
    etaMinutes = etaMinutes,
    distanceKm = distanceKm,
    confidence = confidence,
    mode = routeMode.name,
    updatedAtEpochMs = System.currentTimeMillis(),
)

