package com.smarttraffic.core_engine.data.routing

import com.smarttraffic.core_engine.domain.model.GeoPoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import kotlin.concurrent.read
import kotlin.concurrent.write

enum class RoadClass {
    MOTORWAY,
    TRUNK,
    PRIMARY,
    SECONDARY,
    TERTIARY,
    LOCAL,
}

enum class TurnType {
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    U_TURN,
}

data class DirectedRoadNode(
    val nodeId: Int,
    val point: GeoPoint?,
    val metadata: Map<String, String> = emptyMap(),
)

data class TimeWindowWeight(
    val fromMinuteOfDay: Int,
    val toMinuteOfDay: Int,
    val multiplier: Float,
)

data class TimeDependentEdgeWeights(
    val windows: List<TimeWindowWeight> = emptyList(),
    val defaultMultiplier: Float = 1f,
) {
    fun multiplierAt(epochMs: Long): Float {
        if (windows.isEmpty()) return defaultMultiplier.coerceAtLeast(0.05f)
        val minute = (((epochMs / 60_000L) % (24 * 60)) + (24 * 60)) % (24 * 60)
        val current = minute.toInt()
        val match = windows.firstOrNull { current >= it.fromMinuteOfDay && current < it.toMinuteOfDay }
        return (match?.multiplier ?: defaultMultiplier).coerceAtLeast(0.05f)
    }
}

data class DynamicEdgeWeights(
    val trafficPenalty: Float = 0f,
    val incidentPenalty: Float = 0f,
    val weatherPenalty: Float = 0f,
    val predictiveMultiplier: Float = 1f,
    val uncertaintyPenalty: Float = 0f,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

data class EdgeMetadata(
    val speedLimitKph: Float,
    val lanes: Int,
    val toll: Boolean,
    val bridge: Boolean,
    val tunnel: Boolean,
    val schoolZone: Boolean,
    val maxVehicleClass: String? = null,
    val tags: Map<String, String> = emptyMap(),
)

data class DirectedRoadEdge(
    val edgeId: String,
    val fromNode: Int,
    val toNode: Int,
    val baseTravelTimeSeconds: Float,
    val distanceMeters: Float,
    val roadClass: RoadClass,
    val turnType: TurnType? = null,
    val metadata: EdgeMetadata,
    val dynamicWeights: DynamicEdgeWeights = DynamicEdgeWeights(),
    val timeDependentWeights: TimeDependentEdgeWeights = TimeDependentEdgeWeights(),
) {
    fun travelTimeSecondsAt(epochMs: Long): Float {
        val dynamic = 1f + dynamicWeights.trafficPenalty + dynamicWeights.incidentPenalty + dynamicWeights.weatherPenalty
        val predictive = dynamicWeights.predictiveMultiplier.coerceAtLeast(0.05f)
        val timeScale = timeDependentWeights.multiplierAt(epochMs)
        return (baseTravelTimeSeconds * dynamic * predictive * timeScale + dynamicWeights.uncertaintyPenalty)
            .coerceAtLeast(0.1f)
    }
}

data class GraphSearchRequest(
    val sourceNode: Int,
    val targetNode: Int,
    val departureEpochMs: Long,
    val blockedEdges: Set<String> = emptySet(),
    val maxVisitedNodes: Int = 250_000,
)

data class EdgeWeightPatch(
    val edgeId: String,
    val dynamicWeights: DynamicEdgeWeights,
)

data class GraphPatch(
    val upsertNodes: List<DirectedRoadNode> = emptyList(),
    val upsertEdges: List<DirectedRoadEdge> = emptyList(),
    val removeEdges: List<String> = emptyList(),
    val weightPatches: List<EdgeWeightPatch> = emptyList(),
)

class DirectedRoadGraph {
    private val lock = ReentrantReadWriteLock()
    private val nodes = ConcurrentHashMap<Int, DirectedRoadNode>()
    private val edges = ConcurrentHashMap<String, DirectedRoadEdge>()
    private val outgoing = ConcurrentHashMap<Int, CopyOnWriteArraySet<String>>()
    private val incoming = ConcurrentHashMap<Int, CopyOnWriteArraySet<String>>()

    fun upsertNode(node: DirectedRoadNode) {
        lock.write { nodes[node.nodeId] = node }
    }

    fun upsertEdge(edge: DirectedRoadEdge) {
        lock.write {
            edges[edge.edgeId]?.let { existing ->
                outgoing[existing.fromNode]?.remove(existing.edgeId)
                incoming[existing.toNode]?.remove(existing.edgeId)
            }
            edges[edge.edgeId] = edge
            outgoing.computeIfAbsent(edge.fromNode) { CopyOnWriteArraySet() }.add(edge.edgeId)
            incoming.computeIfAbsent(edge.toNode) { CopyOnWriteArraySet() }.add(edge.edgeId)
        }
    }

    fun removeEdge(edgeId: String) {
        lock.write {
            val removed = edges.remove(edgeId) ?: return
            outgoing[removed.fromNode]?.remove(edgeId)
            incoming[removed.toNode]?.remove(edgeId)
        }
    }

    fun patchEdgeWeights(edgeId: String, dynamicWeights: DynamicEdgeWeights) {
        lock.write {
            val current = edges[edgeId] ?: return
            edges[edgeId] = current.copy(dynamicWeights = dynamicWeights)
        }
    }

    fun edge(edgeId: String): DirectedRoadEdge? = lock.read { edges[edgeId] }

    fun node(nodeId: Int): DirectedRoadNode? = lock.read { nodes[nodeId] }

    fun outgoingEdges(nodeId: Int): List<DirectedRoadEdge> = lock.read {
        outgoing[nodeId]
            ?.mapNotNull { edgeId -> edges[edgeId] }
            .orEmpty()
    }

    fun incomingEdges(nodeId: Int): List<DirectedRoadEdge> = lock.read {
        incoming[nodeId]
            ?.mapNotNull { edgeId -> edges[edgeId] }
            .orEmpty()
    }

    fun allEdges(): List<DirectedRoadEdge> = lock.read { edges.values.toList() }

    fun allNodes(): List<DirectedRoadNode> = lock.read { nodes.values.toList() }

    fun applyPatch(patch: GraphPatch) {
        lock.write {
            patch.upsertNodes.forEach { node -> nodes[node.nodeId] = node }
            patch.upsertEdges.forEach { edge ->
                edges[edge.edgeId]?.let { existing ->
                    outgoing[existing.fromNode]?.remove(existing.edgeId)
                    incoming[existing.toNode]?.remove(existing.edgeId)
                }
                edges[edge.edgeId] = edge
                outgoing.computeIfAbsent(edge.fromNode) { CopyOnWriteArraySet() }.add(edge.edgeId)
                incoming.computeIfAbsent(edge.toNode) { CopyOnWriteArraySet() }.add(edge.edgeId)
            }
            patch.removeEdges.forEach { edgeId ->
                val removed = edges.remove(edgeId)
                if (removed != null) {
                    outgoing[removed.fromNode]?.remove(edgeId)
                    incoming[removed.toNode]?.remove(edgeId)
                }
            }
            patch.weightPatches.forEach { weightPatch ->
                val current = edges[weightPatch.edgeId] ?: return@forEach
                edges[weightPatch.edgeId] = current.copy(dynamicWeights = weightPatch.dynamicWeights)
            }
        }
    }
}

interface RealTimeGraphUpdater {
    fun apply(patch: GraphPatch)

    fun applyWeightPatches(patches: List<EdgeWeightPatch>)
}

class DefaultRealTimeGraphUpdater @Inject constructor(
    private val graph: DirectedRoadGraph,
) : RealTimeGraphUpdater {
    override fun apply(patch: GraphPatch) {
        graph.applyPatch(patch)
    }

    override fun applyWeightPatches(patches: List<EdgeWeightPatch>) {
        if (patches.isEmpty()) return
        graph.applyPatch(GraphPatch(weightPatches = patches))
    }
}
