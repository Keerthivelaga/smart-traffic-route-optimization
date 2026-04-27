package com.smarttraffic.core_engine.data.routing

import com.smarttraffic.core_engine.domain.model.RoutingMode
import javax.inject.Inject
import kotlin.math.ln


data class EdgePredictionSignal(
    val edgeId: String,
    val congestionProbability: Float,
    val confidence: Float,
    val severityIndex: Float,
)

interface TrafficPredictionIntegration {
    fun predict(edgeIds: Set<String>, departureEpochMs: Long): Map<String, EdgePredictionSignal>
}

class NoOpTrafficPredictionIntegration @Inject constructor() : TrafficPredictionIntegration {
    override fun predict(edgeIds: Set<String>, departureEpochMs: Long): Map<String, EdgePredictionSignal> = emptyMap()
}

fun interface TrafficPenaltyModel {
    fun penalty(prediction: EdgePredictionSignal?): Float
}

fun interface IncidentPenaltyModel {
    fun penalty(edge: DirectedRoadEdge): Float
}

fun interface WeatherPenaltyModel {
    fun penalty(edge: DirectedRoadEdge): Float
}

fun interface TurnCostModel {
    fun penalty(edge: DirectedRoadEdge): Float
}

fun interface RoadClassWeighting {
    fun weight(roadClass: RoadClass): Float
}

class DefaultTrafficPenaltyModel @Inject constructor() : TrafficPenaltyModel {
    override fun penalty(prediction: EdgePredictionSignal?): Float {
        if (prediction == null) return 0f
        val base = prediction.congestionProbability.coerceIn(0f, 1f)
        val certainty = prediction.confidence.coerceIn(0f, 1f)
        return (base * (0.4f + certainty * 0.8f)).coerceAtMost(1.6f)
    }
}

class DefaultIncidentPenaltyModel @Inject constructor() : IncidentPenaltyModel {
    override fun penalty(edge: DirectedRoadEdge): Float {
        val incidentSeverity = edge.metadata.tags["incident_severity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
        return (edge.dynamicWeights.incidentPenalty + incidentSeverity * 0.9f).coerceAtLeast(0f)
    }
}

class DefaultWeatherPenaltyModel @Inject constructor() : WeatherPenaltyModel {
    override fun penalty(edge: DirectedRoadEdge): Float {
        val weatherRisk = edge.metadata.tags["weather_risk"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
        return (edge.dynamicWeights.weatherPenalty + weatherRisk * 0.7f).coerceAtLeast(0f)
    }
}

class DefaultTurnCostModel @Inject constructor() : TurnCostModel {
    override fun penalty(edge: DirectedRoadEdge): Float {
        return when (edge.turnType) {
            null, TurnType.STRAIGHT -> 0f
            TurnType.SLIGHT_LEFT, TurnType.SLIGHT_RIGHT -> 0.06f
            TurnType.LEFT, TurnType.RIGHT -> 0.14f
            TurnType.SHARP_LEFT, TurnType.SHARP_RIGHT -> 0.2f
            TurnType.U_TURN -> 0.35f
        }
    }
}

class DefaultRoadClassWeighting @Inject constructor() : RoadClassWeighting {
    override fun weight(roadClass: RoadClass): Float {
        return when (roadClass) {
            RoadClass.MOTORWAY -> 0.86f
            RoadClass.TRUNK -> 0.9f
            RoadClass.PRIMARY -> 0.95f
            RoadClass.SECONDARY -> 1.0f
            RoadClass.TERTIARY -> 1.06f
            RoadClass.LOCAL -> 1.13f
        }
    }
}

class CostFunctionBuilder @Inject constructor(
    private val predictionIntegration: TrafficPredictionIntegration,
    private val trafficPenaltyModel: TrafficPenaltyModel,
    private val incidentPenaltyModel: IncidentPenaltyModel,
    private val weatherPenaltyModel: WeatherPenaltyModel,
    private val turnCostModel: TurnCostModel,
    private val roadClassWeighting: RoadClassWeighting,
) {
    fun build(request: GraphSearchRequest, graph: DirectedRoadGraph): EdgeCostEvaluator {
        val predictions = predictionIntegration.predict(
            edgeIds = graph.allEdges().map { it.edgeId }.toSet(),
            departureEpochMs = request.departureEpochMs,
        )
        return EdgeCostEvaluator { edge, atEpochMs ->
            val predictionPenalty = trafficPenaltyModel.penalty(predictions[edge.edgeId])
            val incidentPenalty = incidentPenaltyModel.penalty(edge)
            val weatherPenalty = weatherPenaltyModel.penalty(edge)
            val turnPenalty = turnCostModel.penalty(edge)
            val classWeight = roadClassWeighting.weight(edge.roadClass).toDouble()
            val base = edge.travelTimeSecondsAt(atEpochMs).toDouble()
            base * classWeight * (
                1.0 +
                    predictionPenalty.toDouble() +
                    incidentPenalty.toDouble() +
                    weatherPenalty.toDouble() +
                    turnPenalty.toDouble()
                )
        }
    }
}

interface PredictiveWeightAdjuster {
    fun adjust(
        graph: DirectedRoadGraph,
        predictions: Map<String, EdgePredictionSignal>,
    ): List<EdgeWeightPatch>
}

class DefaultPredictiveWeightAdjuster @Inject constructor() : PredictiveWeightAdjuster {
    override fun adjust(
        graph: DirectedRoadGraph,
        predictions: Map<String, EdgePredictionSignal>,
    ): List<EdgeWeightPatch> {
        if (predictions.isEmpty()) return emptyList()
        return graph.allEdges().mapNotNull { edge ->
            val prediction = predictions[edge.edgeId] ?: return@mapNotNull null
            val congestion = prediction.congestionProbability.coerceIn(0f, 1f)
            val confidence = prediction.confidence.coerceIn(0f, 1f)
            val severity = prediction.severityIndex.coerceIn(0f, 1f)
            val adjusted = edge.dynamicWeights.copy(
                predictiveMultiplier = (1f + congestion * (0.35f + confidence * 0.45f)).coerceAtLeast(0.05f),
                uncertaintyPenalty = (1f - confidence) * (1f + severity),
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            EdgeWeightPatch(edgeId = edge.edgeId, dynamicWeights = adjusted)
        }
    }
}

class UncertaintyAwareScorer @Inject constructor() {
    fun score(path: RoutePath, reliability: Float, uncertainty: Float): Double {
        val riskPenalty = uncertainty.coerceIn(0f, 1f) * 0.8
        val reliabilityBoost = reliability.coerceIn(0f, 1f) * 0.4
        return path.totalCost * (1.0 + riskPenalty - reliabilityBoost)
    }
}

data class ObjectiveVector(
    val etaSeconds: Double,
    val riskScore: Double,
    val fuelScore: Double,
    val reliabilityPenalty: Double,
)

data class RouteObjectiveCandidate(
    val path: RoutePath,
    val objective: ObjectiveVector,
    val compositeScore: Double,
)

data class RoutingProfile(
    val mode: RoutingMode,
    val riskTolerance: Float = 0.5f,
    val fuelPriority: Float = 0.5f,
    val reliabilityPriority: Float = 0.5f,
)

data class OptimizationRequest(
    val searchRequest: GraphSearchRequest,
    val profile: RoutingProfile,
)

interface RouteCandidateGenerator {
    fun generateCandidates(
        request: GraphSearchRequest,
        algorithms: RoutingAlgorithms,
        costEvaluator: EdgeCostEvaluator,
    ): List<RoutePath>
}

interface RouteRankingModel {
    fun rank(candidates: List<RouteObjectiveCandidate>, profile: RoutingProfile): List<RouteObjectiveCandidate>
}

interface PersonalizationLayer {
    fun personalize(candidates: List<RouteObjectiveCandidate>, profile: RoutingProfile): List<RouteObjectiveCandidate>
}

interface ReliabilityScorer {
    fun score(path: RoutePath): Float
}

interface RouteOptimizationEngine {
    fun optimize(request: OptimizationRequest): List<RouteObjectiveCandidate>
}

class ParetoRouteOptimizationEngine @Inject constructor(
    private val graph: DirectedRoadGraph,
    private val algorithms: RoutingAlgorithms,
    private val candidateGenerator: RouteCandidateGenerator,
    private val rankingModel: RouteRankingModel,
    private val personalizationLayer: PersonalizationLayer,
    private val reliabilityScorer: ReliabilityScorer,
    private val uncertaintyAwareScorer: UncertaintyAwareScorer,
    private val costFunctionBuilder: CostFunctionBuilder,
) : RouteOptimizationEngine {

    override fun optimize(request: OptimizationRequest): List<RouteObjectiveCandidate> {
        val costEvaluator = costFunctionBuilder.build(request.searchRequest, graph)
        val generated = candidateGenerator.generateCandidates(request.searchRequest, algorithms, costEvaluator)
        if (generated.isEmpty()) return emptyList()

        val enriched = generated.map { path ->
            val reliability = reliabilityScorer.score(path)
            val uncertainty = (1.0 - reliability).coerceIn(0.0, 1.0)
            val composite = uncertaintyAwareScorer.score(path, reliability, uncertainty.toFloat())
            val objective = ObjectiveVector(
                etaSeconds = path.totalTravelSeconds,
                riskScore = uncertainty,
                fuelScore = estimateFuelScore(path),
                reliabilityPenalty = 1.0 - reliability,
            )
            RouteObjectiveCandidate(path = path, objective = objective, compositeScore = composite)
        }

        val pareto = paretoFrontier(enriched)
        val personalized = personalizationLayer.personalize(pareto, request.profile)
        return rankingModel.rank(personalized, request.profile)
    }

    private fun estimateFuelScore(path: RoutePath): Double {
        val averageEdgeLength = if (path.edgeIds.isNotEmpty()) {
            path.edgeIds.mapNotNull { edgeId -> graph.edge(edgeId)?.distanceMeters?.toDouble() }.average()
        } else {
            0.0
        }
        return ln(1.0 + averageEdgeLength.coerceAtLeast(0.0) / 120.0 + path.totalTravelSeconds / 60.0)
    }

    private fun paretoFrontier(candidates: List<RouteObjectiveCandidate>): List<RouteObjectiveCandidate> {
        if (candidates.size <= 1) return candidates
        return candidates.filter { candidate ->
            candidates.none { other ->
                other !== candidate && dominates(other.objective, candidate.objective)
            }
        }
    }

    private fun dominates(left: ObjectiveVector, right: ObjectiveVector): Boolean {
        val betterOrEqual = left.etaSeconds <= right.etaSeconds &&
            left.riskScore <= right.riskScore &&
            left.fuelScore <= right.fuelScore &&
            left.reliabilityPenalty <= right.reliabilityPenalty
        val strictlyBetter = left.etaSeconds < right.etaSeconds ||
            left.riskScore < right.riskScore ||
            left.fuelScore < right.fuelScore ||
            left.reliabilityPenalty < right.reliabilityPenalty
        return betterOrEqual && strictlyBetter
    }
}
