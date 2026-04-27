package com.smarttraffic.core_engine.data.routing

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class DefaultRouteCandidateGenerator @Inject constructor() : RouteCandidateGenerator {
    override fun generateCandidates(
        request: GraphSearchRequest,
        algorithms: RoutingAlgorithms,
        costEvaluator: EdgeCostEvaluator,
    ): List<RoutePath> {
        val candidates = listOfNotNull(
            algorithms.aStarShortestPath(request, costEvaluator = costEvaluator),
            algorithms.timeDependentDijkstra(request, costEvaluator = costEvaluator),
            algorithms.bidirectionalSearch(request, costEvaluator = costEvaluator),
        )
        if (candidates.isEmpty()) return emptyList()
        return candidates
            .distinctBy { path -> path.edgeIds.joinToString("|") }
            .sortedBy { path -> path.totalCost }
    }
}

class DefaultRouteRankingModel @Inject constructor() : RouteRankingModel {
    override fun rank(candidates: List<RouteObjectiveCandidate>, profile: RoutingProfile): List<RouteObjectiveCandidate> {
        if (candidates.isEmpty()) return emptyList()
        return candidates.sortedBy { candidate ->
            val etaWeight = when (profile.mode) {
                com.smarttraffic.core_engine.domain.model.RoutingMode.FASTEST -> 1.0
                com.smarttraffic.core_engine.domain.model.RoutingMode.FUEL_EFFICIENT -> 0.6
                com.smarttraffic.core_engine.domain.model.RoutingMode.LOW_TRAFFIC -> 0.8
                com.smarttraffic.core_engine.domain.model.RoutingMode.SCENIC -> 0.7
            }
            val riskWeight = 0.5 + (1f - profile.riskTolerance.coerceIn(0f, 1f)).toDouble()
            val fuelWeight = 0.5 + profile.fuelPriority.coerceIn(0f, 1f).toDouble()
            val reliabilityWeight = 0.4 + profile.reliabilityPriority.coerceIn(0f, 1f).toDouble()

            candidate.objective.etaSeconds * etaWeight +
                candidate.objective.riskScore * riskWeight +
                candidate.objective.fuelScore * fuelWeight +
                candidate.objective.reliabilityPenalty * reliabilityWeight
        }
    }
}

class DefaultPersonalizationLayer @Inject constructor() : PersonalizationLayer {
    override fun personalize(candidates: List<RouteObjectiveCandidate>, profile: RoutingProfile): List<RouteObjectiveCandidate> {
        if (candidates.isEmpty()) return emptyList()
        return candidates.map { candidate ->
            val reliabilityBoost = 1f - profile.reliabilityPriority.coerceIn(0f, 1f)
            val adjustedScore = candidate.compositeScore * (
                0.9 +
                    reliabilityBoost.toDouble() * candidate.objective.reliabilityPenalty
                )
            candidate.copy(compositeScore = adjustedScore)
        }
    }
}

class DefaultReliabilityScorer @Inject constructor(
    private val graph: DirectedRoadGraph,
) : ReliabilityScorer {
    override fun score(path: RoutePath): Float {
        if (path.edgeIds.isEmpty()) return 1f
        var supported = 0f
        var weighted = 0f
        path.edgeIds.forEach { edgeId ->
            val edge = graph.edge(edgeId) ?: return@forEach
            val coverage = edge.metadata.tags["coverage"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.65f
            val confidence = (1f - edge.dynamicWeights.uncertaintyPenalty.coerceAtLeast(0f) / 3f).coerceIn(0f, 1f)
            val score = (coverage * 0.55f + confidence * 0.45f).coerceIn(0f, 1f)
            val weight = edge.distanceMeters.coerceAtLeast(1f)
            supported += score * weight
            weighted += weight
        }
        if (weighted <= 0f) return 0.5f
        return (supported / weighted).coerceIn(0f, 1f)
    }
}

data class RouteCacheKey(
    val sourceNode: Int,
    val targetNode: Int,
    val departureBucket: Long,
    val mode: String,
)

class RouteCache(
    private val ttlMs: Long = 30_000L,
    private val maxEntries: Int = 4_096,
) {
    private data class Entry(val value: RoutePath, val expiresAtMs: Long)

    private val store = ConcurrentHashMap<RouteCacheKey, Entry>()

    fun get(key: RouteCacheKey): RoutePath? {
        val now = System.currentTimeMillis()
        val entry = store[key] ?: return null
        if (entry.expiresAtMs < now) {
            store.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: RouteCacheKey, path: RoutePath) {
        val now = System.currentTimeMillis()
        store[key] = Entry(path, now + ttlMs)
        if (store.size <= maxEntries) return
        val overflow = store.size - maxEntries
        store.entries
            .sortedBy { it.value.expiresAtMs }
            .take(overflow)
            .forEach { store.remove(it.key) }
    }

    fun clear() {
        store.clear()
    }
}

data class RecomputePlan(
    val impactedEdges: Set<String>,
    val canReusePrefix: Boolean,
)

class PartialRecomputationPlanner @Inject constructor() {
    fun plan(previous: RoutePath?, impactedEdges: Set<String>): RecomputePlan {
        if (previous == null || previous.edgeIds.isEmpty()) {
            return RecomputePlan(impactedEdges = impactedEdges, canReusePrefix = false)
        }
        val intersects = previous.edgeIds.any { edgeId -> edgeId in impactedEdges }
        return RecomputePlan(impactedEdges = impactedEdges, canReusePrefix = intersects)
    }
}

class GraphPruner @Inject constructor() {
    fun prune(graph: DirectedRoadGraph, request: GraphSearchRequest, maxHops: Int = 120): DirectedRoadGraph {
        if (maxHops < 1) return graph
        val frontier = ArrayDeque<Int>()
        val visited = HashSet<Int>()
        frontier.add(request.sourceNode)
        visited.add(request.sourceNode)

        var hops = 0
        while (frontier.isNotEmpty() && hops < maxHops) {
            val levelSize = frontier.size
            repeat(levelSize) {
                val node = frontier.removeFirst()
                graph.outgoingEdges(node).forEach { edge ->
                    if (visited.add(edge.toNode)) frontier.add(edge.toNode)
                }
                graph.incomingEdges(node).forEach { edge ->
                    if (visited.add(edge.fromNode)) frontier.add(edge.fromNode)
                }
            }
            hops += 1
        }

        if (request.targetNode !in visited) {
            return graph
        }

        val pruned = DirectedRoadGraph()
        visited.forEach { nodeId ->
            graph.node(nodeId)?.let(pruned::upsertNode)
        }
        graph.allEdges().forEach { edge ->
            if (edge.fromNode in visited && edge.toNode in visited) {
                pruned.upsertEdge(edge)
            }
        }
        return pruned
    }
}

class ParallelSearchExecutor @Inject constructor(
    private val parallelism: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2),
) {
    fun run(tasks: List<() -> RoutePath?>, timeoutMs: Long = 1500L): List<RoutePath> {
        if (tasks.isEmpty()) return emptyList()
        val executor = Executors.newFixedThreadPool(parallelism)
        return try {
            val futures: List<Future<RoutePath?>> = tasks.map { task ->
                executor.submit(Callable { task() })
            }
            futures.mapNotNull { future ->
                runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
