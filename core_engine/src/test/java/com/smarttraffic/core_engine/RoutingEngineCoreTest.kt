package com.smarttraffic.core_engine

import com.smarttraffic.core_engine.data.routing.CostFunctionBuilder
import com.smarttraffic.core_engine.data.routing.DefaultIncidentPenaltyModel
import com.smarttraffic.core_engine.data.routing.DefaultPersonalizationLayer
import com.smarttraffic.core_engine.data.routing.DefaultReliabilityScorer
import com.smarttraffic.core_engine.data.routing.DefaultRoadClassWeighting
import com.smarttraffic.core_engine.data.routing.DefaultRouteCandidateGenerator
import com.smarttraffic.core_engine.data.routing.DefaultRouteRankingModel
import com.smarttraffic.core_engine.data.routing.DefaultTrafficPenaltyModel
import com.smarttraffic.core_engine.data.routing.DefaultTurnCostModel
import com.smarttraffic.core_engine.data.routing.DefaultWeatherPenaltyModel
import com.smarttraffic.core_engine.data.routing.DirectedRoadEdge
import com.smarttraffic.core_engine.data.routing.DirectedRoadGraph
import com.smarttraffic.core_engine.data.routing.DirectedRoadNode
import com.smarttraffic.core_engine.data.routing.EdgeMetadata
import com.smarttraffic.core_engine.data.routing.GraphSearchRequest
import com.smarttraffic.core_engine.data.routing.NoOpTrafficPredictionIntegration
import com.smarttraffic.core_engine.data.routing.OptimizationRequest
import com.smarttraffic.core_engine.data.routing.ParetoRouteOptimizationEngine
import com.smarttraffic.core_engine.data.routing.RoadClass
import com.smarttraffic.core_engine.data.routing.RouteCache
import com.smarttraffic.core_engine.data.routing.RouteCacheKey
import com.smarttraffic.core_engine.data.routing.RoutingAlgorithms
import com.smarttraffic.core_engine.data.routing.RoutingProfile
import com.smarttraffic.core_engine.data.routing.UncertaintyAwareScorer
import com.smarttraffic.core_engine.domain.model.GeoPoint
import com.smarttraffic.core_engine.domain.model.RoutingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingEngineCoreTest {

    @Test
    fun aStar_prefersLowerCostPath() {
        val graph = sampleGraph()
        val algorithms = RoutingAlgorithms(graph)

        val path = algorithms.aStarShortestPath(
            GraphSearchRequest(
                sourceNode = 1,
                targetNode = 3,
                departureEpochMs = 1_700_000_000_000,
            )
        )

        assertNotNull(path)
        assertEquals(listOf("e12", "e23"), path?.edgeIds)
    }

    @Test
    fun optimizer_returnsCandidateList() {
        val graph = sampleGraph()
        val algorithms = RoutingAlgorithms(graph)
        val optimizer = ParetoRouteOptimizationEngine(
            graph = graph,
            algorithms = algorithms,
            candidateGenerator = DefaultRouteCandidateGenerator(),
            rankingModel = DefaultRouteRankingModel(),
            personalizationLayer = DefaultPersonalizationLayer(),
            reliabilityScorer = DefaultReliabilityScorer(graph),
            uncertaintyAwareScorer = UncertaintyAwareScorer(),
            costFunctionBuilder = CostFunctionBuilder(
                predictionIntegration = NoOpTrafficPredictionIntegration(),
                trafficPenaltyModel = DefaultTrafficPenaltyModel(),
                incidentPenaltyModel = DefaultIncidentPenaltyModel(),
                weatherPenaltyModel = DefaultWeatherPenaltyModel(),
                turnCostModel = DefaultTurnCostModel(),
                roadClassWeighting = DefaultRoadClassWeighting(),
            ),
        )

        val results = optimizer.optimize(
            OptimizationRequest(
                searchRequest = GraphSearchRequest(
                    sourceNode = 1,
                    targetNode = 3,
                    departureEpochMs = 1_700_000_000_000,
                ),
                profile = RoutingProfile(
                    mode = RoutingMode.FASTEST,
                    riskTolerance = 0.4f,
                    fuelPriority = 0.4f,
                    reliabilityPriority = 0.6f,
                ),
            )
        )

        assertTrue(results.isNotEmpty())
        assertTrue(results.first().path.edgeIds.isNotEmpty())
    }

    @Test
    fun routeCache_expiresEntries() {
        val cache = RouteCache(ttlMs = 20, maxEntries = 10)
        val key = RouteCacheKey(sourceNode = 1, targetNode = 2, departureBucket = 10, mode = "FASTEST")
        val cachedPath = RoutingAlgorithms(sampleGraph()).aStarShortestPath(
            GraphSearchRequest(sourceNode = 1, targetNode = 3, departureEpochMs = 1_700_000_000_000)
        )

        assertNotNull(cachedPath)
        cache.put(key, cachedPath!!)
        assertNotNull(cache.get(key))
        Thread.sleep(30)
        assertEquals(null, cache.get(key))
    }

    private fun sampleGraph(): DirectedRoadGraph {
        val graph = DirectedRoadGraph()
        graph.upsertNode(DirectedRoadNode(nodeId = 1, point = GeoPoint(12.0, 77.0)))
        graph.upsertNode(DirectedRoadNode(nodeId = 2, point = GeoPoint(12.001, 77.002)))
        graph.upsertNode(DirectedRoadNode(nodeId = 3, point = GeoPoint(12.002, 77.004)))

        graph.upsertEdge(
            DirectedRoadEdge(
                edgeId = "e12",
                fromNode = 1,
                toNode = 2,
                baseTravelTimeSeconds = 8f,
                distanceMeters = 300f,
                roadClass = RoadClass.PRIMARY,
                metadata = EdgeMetadata(
                    speedLimitKph = 50f,
                    lanes = 2,
                    toll = false,
                    bridge = false,
                    tunnel = false,
                    schoolZone = false,
                ),
            )
        )
        graph.upsertEdge(
            DirectedRoadEdge(
                edgeId = "e23",
                fromNode = 2,
                toNode = 3,
                baseTravelTimeSeconds = 8f,
                distanceMeters = 350f,
                roadClass = RoadClass.PRIMARY,
                metadata = EdgeMetadata(
                    speedLimitKph = 50f,
                    lanes = 2,
                    toll = false,
                    bridge = false,
                    tunnel = false,
                    schoolZone = false,
                ),
            )
        )
        graph.upsertEdge(
            DirectedRoadEdge(
                edgeId = "e13",
                fromNode = 1,
                toNode = 3,
                baseTravelTimeSeconds = 28f,
                distanceMeters = 900f,
                roadClass = RoadClass.SECONDARY,
                metadata = EdgeMetadata(
                    speedLimitKph = 40f,
                    lanes = 1,
                    toll = false,
                    bridge = false,
                    tunnel = false,
                    schoolZone = false,
                ),
            )
        )
        return graph
    }
}
