package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.services.currentWorkingGraph
import com.charmnight.linkgraph.services.currentWorkingGraphSource
import com.charmnight.linkgraph.ui.GraphEditorStateService
import java.util.ArrayDeque

/**
 * 图工具统一门面。
 * 第一阶段只提供对当前 snapshot 的只读访问，避免把图读取细节散落到多个 tool 实现中。
 */
class GraphToolFacade {
    /** 返回当前最适合问答使用的工作图。 */
    fun currentGraph(snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot): GraphDocument {
        return currentWorkingGraph(snapshot)
    }

    /** 返回当前图来源标签，便于调试和日志记录。 */
    fun currentGraphSource(snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot): String {
        return currentWorkingGraphSource(snapshot)
    }

    /** 解析当前选区；若调用方显式给了 nodeIds，则优先使用调用方输入。 */
    fun selectedNodeIds(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        requestedNodeIds: List<String> = emptyList(),
    ): List<String> {
        return requestedNodeIds.ifEmpty {
            snapshot.selectedNodeId?.let(::listOf).orEmpty()
        }
    }

    /** 返回指定节点详情。 */
    fun nodeDetail(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        nodeId: String,
    ): GraphNode? {
        return currentGraph(snapshot).nodes.firstOrNull { it.id == nodeId }
    }

    /**
     * 返回某个节点的邻域子图。
     * 第一阶段仅按无向一跳/多跳近邻展开，足够支撑问答先收缩讨论范围。
     */
    fun expandNeighborhood(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        nodeId: String,
        depth: Int = 1,
    ): GraphDocument {
        val graph = currentGraph(snapshot)
        val nodeById = graph.nodes.associateBy(GraphNode::id)
        if (!nodeById.containsKey(nodeId)) {
            return GraphDocument()
        }
        val visitedNodeIds = linkedSetOf(nodeId)
        val queue = ArrayDeque<Pair<String, Int>>()
        queue += nodeId to 0
        while (queue.isNotEmpty()) {
            val (currentNodeId, currentDepth) = queue.removeFirst()
            if (currentDepth >= depth) {
                continue
            }
            graph.edges.asSequence()
                .filter { edge -> edge.fromNodeId == currentNodeId || edge.toNodeId == currentNodeId }
                .flatMap { edge -> sequenceOf(edge.fromNodeId, edge.toNodeId) }
                .filter { neighborId -> visitedNodeIds.add(neighborId) }
                .forEach { neighborId ->
                    queue += neighborId to (currentDepth + 1)
                }
        }
        val neighborhoodEdges = graph.edges.filter { edge ->
            edge.fromNodeId in visitedNodeIds && edge.toNodeId in visitedNodeIds
        }
        return GraphDocument(
            nodes = visitedNodeIds.mapNotNull(nodeById::get),
            edges = neighborhoodEdges,
        )
    }

    /** 返回当前差异对象，没有则返回空 diff。 */
    fun currentDiff(snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot): GraphDiff = snapshot.diff ?: GraphDiff()
}
