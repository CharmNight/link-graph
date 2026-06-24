package com.charmnight.linkgraph.application.diagnostics

import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphMetadataKeys
import com.intellij.openapi.diagnostic.Logger

/**
 * 图诊断日志器。
 *
 * 把一份图文档的关键诊断指标（重复 ID、悬空边、坐标分布、节点度数、类型分布等）
 * 序列化为单行 debug 日志。开发期排查"图为什么这么渲染"等问题时使用。
 *
 * 所有日志都包在 debugLazy 中，未开启 debug 时不构造消息。
 */
internal class GraphDiagnosticsLogger(
    /** 日志器。 */
    private val logger: Logger,
) {
    /**
     * 记录一次图诊断。
     *
     * @param reason 触发诊断的原因（例如 "bootstrap"、"graphEdit"）
     * @param graph 待诊断的图；为 null 时只记录原因
     */
    fun log(
        reason: String,
        graph: GraphDocument?,
    ) {
        if (graph == null) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "链路图诊断[$reason]: graph=null" }
            return
        }
        // 收集节点 ID 并检测重复（取前 6 个避免日志过长）
        val nodeIds = graph.nodes.map { it.id }
        val nodeIdSet = nodeIds.toSet()
        val duplicateNodeIds = nodeIds.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
            .take(6)
        // 同样检测重复边
        val edgeIds = graph.edges.map { it.id }
        val duplicateEdgeIds = edgeIds.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
            .take(6)
        // 检测悬空边：端点不在节点集合中
        val danglingEdges = graph.edges
            .filter { edge -> edge.fromNodeId !in nodeIdSet || edge.toNodeId !in nodeIdSet }
            .take(6)
            .map { edge -> "${edge.id}(${edge.fromNodeId}->${edge.toNodeId})" }
        // 统计节点坐标分布
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
        // 节点度数：判断图是否高度集中（个别节点出/入度很大）
        val maxOutDegree = graph.edges.groupingBy { it.fromNodeId }.eachCount().values.maxOrNull() ?: 0
        val maxInDegree = graph.edges.groupingBy { it.toNodeId }.eachCount().values.maxOrNull() ?: 0
        // 节点类型分布：按数量降序拼接
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
