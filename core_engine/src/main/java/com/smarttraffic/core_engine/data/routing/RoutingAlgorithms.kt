package com.smarttraffic.core_engine.data.routing

import java.util.PriorityQueue
import javax.inject.Inject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun interface EdgeCostEvaluator {
    fun evaluate(edge: DirectedRoadEdge, atEpochMs: Long): Double
}

data class RoutePath(
    val nodes: List<Int>,
    val edgeIds: List<String>,
    val totalCost: Double,
    val totalTravelSeconds: Double,
    val arrivalEpochMs: Long,
    val diagnostics: SearchDiagnostics = SearchDiagnostics(),
)

data class SearchDiagnostics(
    val algorithm: String = "",
    val expandedNodes: Int = 0,
    val frontierPeak: Int = 0,
)

interface HeuristicEstimator {
    fun estimateSeconds(fromNode: Int, toNode: Int): Double
}

class GreatCircleHeuristic(
    private val graph: DirectedRoadGraph,
    private val referenceSpeedKph: Double = 55.0,
) : HeuristicEstimator {
    override fun estimateSeconds(fromNode: Int, toNode: Int): Double {
        val from = graph.node(fromNode)?.point ?: return 0.0
        val to = graph.node(toNode)?.point ?: return 0.0
        val distanceMeters = haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        val speedMs = (referenceSpeedKph / 3.6).coerceAtLeast(0.1)
        return distanceMeters / speedMs
    }
}

class RoutingAlgorithms @Inject constructor(
    private val graph: DirectedRoadGraph,
) {
    private val defaultCost = EdgeCostEvaluator { edge, epochMs -> edge.travelTimeSecondsAt(epochMs).toDouble() }

    fun aStarShortestPath(
        request: GraphSearchRequest,
        heuristic: HeuristicEstimator = GreatCircleHeuristic(graph),
        costEvaluator: EdgeCostEvaluator = defaultCost,
    ): RoutePath? {
        data class NodeState(val nodeId: Int, val fScore: Double)
        val frontier = PriorityQueue<NodeState>(compareBy { it.fScore })
        val gScore = HashMap<Int, Double>()
        val bestArrival = HashMap<Int, Long>()
        val parentNode = HashMap<Int, Int>()
        val parentEdge = HashMap<Int, String>()
        var expanded = 0
        var frontierPeak = 0

        gScore[request.sourceNode] = 0.0
        bestArrival[request.sourceNode] = request.departureEpochMs
        frontier.add(NodeState(request.sourceNode, heuristic.estimateSeconds(request.sourceNode, request.targetNode)))

        while (frontier.isNotEmpty()) {
            frontierPeak = maxOf(frontierPeak, frontier.size)
            val current = frontier.poll()
            expanded += 1
            if (expanded > request.maxVisitedNodes) return null
            if (current.nodeId == request.targetNode) {
                return buildPath(
                    source = request.sourceNode,
                    target = request.targetNode,
                    departureEpochMs = request.departureEpochMs,
                    parentNode = parentNode,
                    parentEdge = parentEdge,
                    gScore = gScore,
                    diagnostics = SearchDiagnostics(
                        algorithm = "astar",
                        expandedNodes = expanded,
                        frontierPeak = frontierPeak,
                    ),
                )
            }

            val currentCost = gScore[current.nodeId] ?: continue
            val currentArrival = bestArrival[current.nodeId] ?: request.departureEpochMs
            graph.outgoingEdges(current.nodeId).forEach { edge ->
                if (edge.edgeId in request.blockedEdges) return@forEach
                val edgeCost = costEvaluator.evaluate(edge, currentArrival)
                val tentativeG = currentCost + edgeCost
                if (tentativeG < (gScore[edge.toNode] ?: Double.POSITIVE_INFINITY)) {
                    gScore[edge.toNode] = tentativeG
                    bestArrival[edge.toNode] = currentArrival + (edgeCost * 1000.0).toLong()
                    parentNode[edge.toNode] = current.nodeId
                    parentEdge[edge.toNode] = edge.edgeId
                    val estimate = heuristic.estimateSeconds(edge.toNode, request.targetNode)
                    frontier.add(NodeState(edge.toNode, tentativeG + estimate))
                }
            }
        }
        return null
    }

    fun timeDependentDijkstra(
        request: GraphSearchRequest,
        costEvaluator: EdgeCostEvaluator = defaultCost,
    ): RoutePath? {
        data class NodeState(val nodeId: Int, val cost: Double)
        val frontier = PriorityQueue<NodeState>(compareBy { it.cost })
        val dist = HashMap<Int, Double>()
        val arrival = HashMap<Int, Long>()
        val parentNode = HashMap<Int, Int>()
        val parentEdge = HashMap<Int, String>()
        var expanded = 0
        var frontierPeak = 0

        dist[request.sourceNode] = 0.0
        arrival[request.sourceNode] = request.departureEpochMs
        frontier.add(NodeState(request.sourceNode, 0.0))

        while (frontier.isNotEmpty()) {
            frontierPeak = maxOf(frontierPeak, frontier.size)
            val current = frontier.poll()
            expanded += 1
            if (expanded > request.maxVisitedNodes) return null
            if (current.cost > (dist[current.nodeId] ?: Double.POSITIVE_INFINITY)) continue
            if (current.nodeId == request.targetNode) {
                return buildPath(
                    source = request.sourceNode,
                    target = request.targetNode,
                    departureEpochMs = request.departureEpochMs,
                    parentNode = parentNode,
                    parentEdge = parentEdge,
                    gScore = dist,
                    diagnostics = SearchDiagnostics(
                        algorithm = "time_dependent_dijkstra",
                        expandedNodes = expanded,
                        frontierPeak = frontierPeak,
                    ),
                )
            }

            val currentArrival = arrival[current.nodeId] ?: request.departureEpochMs
            graph.outgoingEdges(current.nodeId).forEach { edge ->
                if (edge.edgeId in request.blockedEdges) return@forEach
                val edgeCost = costEvaluator.evaluate(edge, currentArrival)
                val nextCost = current.cost + edgeCost
                if (nextCost < (dist[edge.toNode] ?: Double.POSITIVE_INFINITY)) {
                    dist[edge.toNode] = nextCost
                    arrival[edge.toNode] = currentArrival + (edgeCost * 1000.0).toLong()
                    parentNode[edge.toNode] = current.nodeId
                    parentEdge[edge.toNode] = edge.edgeId
                    frontier.add(NodeState(edge.toNode, nextCost))
                }
            }
        }
        return null
    }

    fun bidirectionalSearch(
        request: GraphSearchRequest,
        costEvaluator: EdgeCostEvaluator = defaultCost,
    ): RoutePath? {
        data class NodeState(val nodeId: Int, val cost: Double)
        val forward = PriorityQueue<NodeState>(compareBy { it.cost })
        val backward = PriorityQueue<NodeState>(compareBy { it.cost })

        val fDist = HashMap<Int, Double>()
        val bDist = HashMap<Int, Double>()
        val fParentNode = HashMap<Int, Int>()
        val fParentEdge = HashMap<Int, String>()
        val bParentNode = HashMap<Int, Int>()
        val bParentEdge = HashMap<Int, String>()

        fDist[request.sourceNode] = 0.0
        bDist[request.targetNode] = 0.0
        forward.add(NodeState(request.sourceNode, 0.0))
        backward.add(NodeState(request.targetNode, 0.0))

        var bestCost = Double.POSITIVE_INFINITY
        var meetNode: Int? = null
        var expanded = 0
        var frontierPeak = 0

        while (forward.isNotEmpty() && backward.isNotEmpty()) {
            frontierPeak = maxOf(frontierPeak, forward.size + backward.size)
            if ((forward.peek().cost + backward.peek().cost) >= bestCost) break
            if (expanded > request.maxVisitedNodes) return null

            val expandForward = forward.peek().cost <= backward.peek().cost
            if (expandForward) {
                val current = forward.poll()
                if (current.cost > (fDist[current.nodeId] ?: Double.POSITIVE_INFINITY)) continue
                expanded += 1
                graph.outgoingEdges(current.nodeId).forEach { edge ->
                    if (edge.edgeId in request.blockedEdges) return@forEach
                    val edgeCost = costEvaluator.evaluate(edge, request.departureEpochMs)
                    val nextCost = current.cost + edgeCost
                    if (nextCost < (fDist[edge.toNode] ?: Double.POSITIVE_INFINITY)) {
                        fDist[edge.toNode] = nextCost
                        fParentNode[edge.toNode] = current.nodeId
                        fParentEdge[edge.toNode] = edge.edgeId
                        forward.add(NodeState(edge.toNode, nextCost))
                    }
                    val backwardCost = bDist[edge.toNode]
                    if (backwardCost != null && nextCost + backwardCost < bestCost) {
                        bestCost = nextCost + backwardCost
                        meetNode = edge.toNode
                    }
                }
            } else {
                val current = backward.poll()
                if (current.cost > (bDist[current.nodeId] ?: Double.POSITIVE_INFINITY)) continue
                expanded += 1
                graph.incomingEdges(current.nodeId).forEach { edge ->
                    if (edge.edgeId in request.blockedEdges) return@forEach
                    val edgeCost = costEvaluator.evaluate(edge, request.departureEpochMs)
                    val nextCost = current.cost + edgeCost
                    if (nextCost < (bDist[edge.fromNode] ?: Double.POSITIVE_INFINITY)) {
                        bDist[edge.fromNode] = nextCost
                        bParentNode[edge.fromNode] = current.nodeId
                        bParentEdge[edge.fromNode] = edge.edgeId
                        backward.add(NodeState(edge.fromNode, nextCost))
                    }
                    val forwardCost = fDist[edge.fromNode]
                    if (forwardCost != null && nextCost + forwardCost < bestCost) {
                        bestCost = nextCost + forwardCost
                        meetNode = edge.fromNode
                    }
                }
            }
        }

        val bridge = meetNode ?: return null
        val left = buildPath(
            source = request.sourceNode,
            target = bridge,
            departureEpochMs = request.departureEpochMs,
            parentNode = fParentNode,
            parentEdge = fParentEdge,
            gScore = fDist,
            diagnostics = SearchDiagnostics(
                algorithm = "bidirectional",
                expandedNodes = expanded,
                frontierPeak = frontierPeak,
            ),
        ) ?: return null

        val rightNodes = mutableListOf<Int>()
        val rightEdges = mutableListOf<String>()
        var cursor = bridge
        while (cursor != request.targetNode) {
            val nextNode = bParentNode[cursor] ?: return null
            val edgeId = bParentEdge[cursor] ?: return null
            rightEdges.add(edgeId)
            rightNodes.add(nextNode)
            cursor = nextNode
        }

        val allNodes = left.nodes + rightNodes
        val allEdges = left.edgeIds + rightEdges
        return materializePath(
            nodes = allNodes,
            edgeIds = allEdges,
            departureEpochMs = request.departureEpochMs,
            diagnostics = left.diagnostics,
            costEvaluator = costEvaluator,
        )
    }

    fun incrementalReroute(
        previousPath: RoutePath,
        request: GraphSearchRequest,
        impactedEdges: Set<String>,
        costEvaluator: EdgeCostEvaluator = defaultCost,
    ): RoutePath? {
        if (impactedEdges.isEmpty()) return previousPath
        val impactedIndex = previousPath.edgeIds.indexOfFirst { edgeId -> edgeId in impactedEdges }
        if (impactedIndex < 0) return previousPath
        if (impactedIndex >= previousPath.nodes.lastIndex) return previousPath

        val prefixNodes = previousPath.nodes.take(impactedIndex + 1)
        val prefixEdges = previousPath.edgeIds.take(impactedIndex)
        val prefixPath = materializePath(
            nodes = prefixNodes,
            edgeIds = prefixEdges,
            departureEpochMs = request.departureEpochMs,
            diagnostics = SearchDiagnostics(algorithm = "incremental_prefix"),
            costEvaluator = costEvaluator,
        ) ?: return null

        val rerouteFrom = prefixNodes.last()
        val suffix = timeDependentDijkstra(
            request = request.copy(
                sourceNode = rerouteFrom,
                departureEpochMs = prefixPath.arrivalEpochMs,
            ),
            costEvaluator = costEvaluator,
        ) ?: return null

        val mergedNodes = prefixNodes.dropLast(1) + suffix.nodes
        val mergedEdges = prefixEdges + suffix.edgeIds
        return materializePath(
            nodes = mergedNodes,
            edgeIds = mergedEdges,
            departureEpochMs = request.departureEpochMs,
            diagnostics = SearchDiagnostics(
                algorithm = "incremental_reroute",
                expandedNodes = suffix.diagnostics.expandedNodes,
                frontierPeak = suffix.diagnostics.frontierPeak,
            ),
            costEvaluator = costEvaluator,
        )
    }

    private fun buildPath(
        source: Int,
        target: Int,
        departureEpochMs: Long,
        parentNode: Map<Int, Int>,
        parentEdge: Map<Int, String>,
        gScore: Map<Int, Double>,
        diagnostics: SearchDiagnostics,
    ): RoutePath? {
        if (source == target) {
            return RoutePath(
                nodes = listOf(source),
                edgeIds = emptyList(),
                totalCost = 0.0,
                totalTravelSeconds = 0.0,
                arrivalEpochMs = departureEpochMs,
                diagnostics = diagnostics,
            )
        }
        val nodesReversed = mutableListOf<Int>()
        val edgesReversed = mutableListOf<String>()
        var cursor = target
        while (cursor != source) {
            val prev = parentNode[cursor] ?: return null
            val edgeId = parentEdge[cursor] ?: return null
            nodesReversed.add(cursor)
            edgesReversed.add(edgeId)
            cursor = prev
        }
        nodesReversed.add(source)
        val nodes = nodesReversed.asReversed()
        val edges = edgesReversed.asReversed()
        val total = gScore[target] ?: return null
        return RoutePath(
            nodes = nodes,
            edgeIds = edges,
            totalCost = total,
            totalTravelSeconds = total,
            arrivalEpochMs = departureEpochMs + (total * 1000.0).toLong(),
            diagnostics = diagnostics,
        )
    }

    private fun materializePath(
        nodes: List<Int>,
        edgeIds: List<String>,
        departureEpochMs: Long,
        diagnostics: SearchDiagnostics,
        costEvaluator: EdgeCostEvaluator,
    ): RoutePath? {
        if (nodes.isEmpty()) return null
        if (nodes.size == 1) {
            return RoutePath(
                nodes = nodes,
                edgeIds = emptyList(),
                totalCost = 0.0,
                totalTravelSeconds = 0.0,
                arrivalEpochMs = departureEpochMs,
                diagnostics = diagnostics,
            )
        }
        if (edgeIds.size != nodes.size - 1) return null

        var currentEpoch = departureEpochMs
        var total = 0.0
        edgeIds.forEach { edgeId ->
            val edge = graph.edge(edgeId) ?: return null
            val cost = costEvaluator.evaluate(edge, currentEpoch)
            total += cost
            currentEpoch += (cost * 1000.0).toLong()
        }

        return RoutePath(
            nodes = nodes,
            edgeIds = edgeIds,
            totalCost = total,
            totalTravelSeconds = total,
            arrivalEpochMs = currentEpoch,
            diagnostics = diagnostics,
        )
    }
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
    return 2.0 * r * asin(sqrt(a))
}
