package com.charmnight.linkgraph.application.diagnostics

import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphMetadataKeys
import com.intellij.openapi.diagnostic.Logger

internal class GraphDiagnosticsLogger(
    private val logger: Logger,
) {
    fun log(
        reason: String,
        graph: GraphDocument?,
    ) {
        if (graph == null) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "链路图诊断[$reason]: graph=null" }
            return
        }
        val nodeIds = graph.nodes.map { it.id }
        val nodeIdSet = nodeIds.toSet()
        val duplicateNodeIds = nodeIds.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
            .take(6)
        val edgeIds = graph.edges.map { it.id }
        val duplicateEdgeIds = edgeIds.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
            .take(6)
        val danglingEdges = graph.edges
            .filter { edge -> edge.fromNodeId !in nodeIdSet || edge.toNodeId !in nodeIdSet }
            .take(6)
            .map { edge -> "${edge.id}(${edge.fromNodeId}->${edge.toNodeId})" }
        val positionedNodes = graph.nodes.mapNotNull { node ->
            val x = node.metadata[GraphMetadataKeys.Ui.X]?.toDoubleOrNull()
            val y = node.metadata[GraphMetadataKeys.Ui.Y]?.toDoubleOrNull()
            if (x != null && y != null) {
                x to y
            } else {
                null
            }
        }
        val xRange = if (positionedNodes.isEmpty()) {
            "n/a"
        } else {
            "${positionedNodes.minOf { it.first }.toInt()}..${positionedNodes.maxOf { it.first }.toInt()}"
        }
        val yRange = if (positionedNodes.isEmpty()) {
            "n/a"
        } else {
            "${positionedNodes.minOf { it.second }.toInt()}..${positionedNodes.maxOf { it.second }.toInt()}"
        }
        val maxOutDegree = graph.edges.groupingBy { it.fromNodeId }.eachCount().values.maxOrNull() ?: 0
        val maxInDegree = graph.edges.groupingBy { it.toNodeId }.eachCount().values.maxOrNull() ?: 0
        val typeSummary = graph.nodes.groupingBy { it.type.name }.eachCount().entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.key}:${it.value}" }
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "链路图诊断[$reason]: nodes=${graph.nodes.size}, edges=${graph.edges.size}, " +
                "duplicateNodeIds=$duplicateNodeIds, duplicateEdgeIds=$duplicateEdgeIds, danglingEdges=$danglingEdges, " +
                "positioned=${positionedNodes.size}, xRange=$xRange, yRange=$yRange, maxOutDegree=$maxOutDegree, maxInDegree=$maxInDegree, " +
                "sampleNodes=${graph.nodes.take(6).map { it.id }}, nodeTypes=[$typeSummary]"
        }
    }
}
