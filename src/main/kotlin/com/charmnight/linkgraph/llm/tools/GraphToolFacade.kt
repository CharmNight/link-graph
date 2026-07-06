package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

import com.charmnight.linkgraph.agent.model.buildInvocationExpansionActiveChainScope
import com.charmnight.linkgraph.agent.model.hasExplicitInvocationExpansionScope
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import java.util.ArrayDeque

/**
 * 图工具统一门面。
 * 第一阶段只提供对当前 snapshot 的只读访问，避免把图读取细节散落到多个 tool 实现中。
 */
class GraphToolFacade(
    private val evidenceAnchorResolver: QaEvidenceAnchorResolver = QaEvidenceAnchorResolver(),
) {
    /** 返回当前最适合问答使用的工作图。 */
    fun currentGraph(snapshot: ToolGraphSnapshot): GraphDocument {
        val workspaceGraph = currentWorkingGraph(snapshot)
        val visibleGraph = currentVisibleGraph(snapshot)
            .takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() }
            ?: workspaceGraph
        if (workspaceGraph.nodes.isEmpty() && workspaceGraph.edges.isEmpty()) {
            return visibleGraph
        }
        if (
            snapshot.currentSceneId == ToolGraphSceneId.WORKSPACE_FLOWCHART &&
            snapshot.currentSceneState().invocationExpansionState.hasExplicitInvocationExpansionScope()
        ) {
            buildInvocationExpansionActiveChainScope(
                visibleGraph = visibleGraph,
                fullGraph = snapshot.flowchartView.fullGraph.takeIf { graph ->
                    graph.nodes.isNotEmpty() || graph.edges.isNotEmpty()
                } ?: workspaceGraph,
                sceneState = snapshot.currentSceneState().invocationExpansionState,
            )?.let { scope ->
                return scope.graph
            }
        }
        val visibleNodeIds = visibleGraph.nodes.mapTo(linkedSetOf()) { node -> node.id }
        val nodeIds = linkedSetOf<String>().apply {
            addAll(visibleNodeIds)
            addAll(snapshot.currentSceneState().selectedNodeId?.let(::listOf).orEmpty())
        }
        val nodeById = (workspaceGraph.nodes + visibleGraph.nodes)
            .associateBy(GraphNode::id)
        val edgeIds = visibleGraph.edges.mapTo(linkedSetOf()) { edge -> edge.id }
        workspaceGraph.edges.forEach { edge ->
            if (edge.fromNodeId in nodeIds || edge.toNodeId in nodeIds) {
                nodeIds += edge.fromNodeId
                nodeIds += edge.toNodeId
                edgeIds += edge.id
            }
        }
        val expansionIds = collectVisibleExpansionIds(workspaceGraph, nodeIds)
        if (expansionIds.isNotEmpty()) {
            workspaceGraph.nodes.forEach { node ->
                if (node.metadata[INVOCATION_EXPANSION_ID_KEY]?.trim() in expansionIds) {
                    nodeIds += node.id
                }
            }
            workspaceGraph.edges.forEach { edge ->
                if (edge.metadata[INVOCATION_EXPANSION_ID_KEY]?.trim() in expansionIds) {
                    nodeIds += edge.fromNodeId
                    nodeIds += edge.toNodeId
                    edgeIds += edge.id
                }
            }
        }
        return GraphDocument(
            nodes = nodeIds.mapNotNull(nodeById::get),
            edges = (visibleGraph.edges + workspaceGraph.edges)
                .filter { edge ->
                    edge.id in edgeIds &&
                        edge.fromNodeId in nodeIds &&
                        edge.toNodeId in nodeIds
                }
                .distinctBy(GraphEdge::id),
        )
    }

    /** 返回当前图来源标签，便于调试和日志记录。 */
    fun currentGraphSource(snapshot: ToolGraphSnapshot): String {
        val visibleGraph = currentVisibleGraph(snapshot)
        val workspaceGraph = currentWorkingGraph(snapshot)
        return when {
            visibleGraph.nodes.isNotEmpty() || visibleGraph.edges.isNotEmpty() -> "interactiveGraph"
            workspaceGraph.nodes.isNotEmpty() || workspaceGraph.edges.isNotEmpty() -> "interactiveGraph"
            else -> "emptyGraph"
        }
    }

    /** 解析当前选区；若调用方显式给了 nodeIds，则优先使用调用方输入。 */
    fun selectedNodeIds(
        snapshot: ToolGraphSnapshot,
        requestedNodeIds: List<String> = emptyList(),
    ): List<String> {
        return requestedNodeIds.ifEmpty {
            snapshot.currentSceneState().selectedNodeId?.let(::listOf).orEmpty()
        }
    }

    /** 返回指定节点详情。 */
    fun nodeDetail(
        snapshot: ToolGraphSnapshot,
        nodeId: String,
    ): GraphNode? {
        return currentGraph(snapshot).nodes.firstOrNull { it.id == nodeId }
    }

    /** 解析问答取证锚点，允许投影视图节点回溯到 canonical 源码节点。 */
    fun evidenceAnchor(
        snapshot: ToolGraphSnapshot,
        nodeId: String? = null,
        symbolSignature: String? = null,
    ): QaEvidenceAnchorResolution {
        return evidenceAnchorResolver.resolve(snapshot, nodeId, symbolSignature)
    }

    /**
     * 返回某个节点的邻域子图。
     * 第一阶段仅按无向一跳/多跳近邻展开，足够支撑问答先收缩讨论范围。
     *
     * @param depth 邻域展开深度，1 表示直接邻居，数值越大覆盖范围越广。
     */
    fun expandNeighborhood(
        snapshot: ToolGraphSnapshot,
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
    fun currentDiff(snapshot: ToolGraphSnapshot): GraphDiff = snapshot.diff ?: GraphDiff()
}

/** 汇总给定节点集合及其相关边上的扩展调用 ID，便于在构造当前工作图时把折叠展开节点重新纳入。 */
private fun collectVisibleExpansionIds(
    graph: GraphDocument,
    nodeIds: Set<String>,
): Set<String> {
    val expansionIds = linkedSetOf<String>()
    graph.nodes.forEach { node ->
        if (node.id in nodeIds) {
            node.metadata[INVOCATION_EXPANSION_ID_KEY]
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(expansionIds::add)
        }
    }
    graph.edges.forEach { edge ->
        if (edge.fromNodeId in nodeIds || edge.toNodeId in nodeIds) {
            edge.metadata[INVOCATION_EXPANSION_ID_KEY]
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(expansionIds::add)
        }
    }
    return expansionIds
}

/** 根据当前场景 ID 选择对应的可见子图，供 currentGraph 等方法消费。 */
private fun currentVisibleGraph(snapshot: ToolGraphSnapshot): GraphDocument {
    return when (snapshot.currentSceneId) {
        ToolGraphSceneId.WORKSPACE_FLOWCHART -> snapshot.flowchartView.visibleGraph
        ToolGraphSceneId.WORKSPACE_RESOURCE_RELATION -> snapshot.resourceRelationView.visibleGraph
        ToolGraphSceneId.WORKSPACE_FACT -> snapshot.factGraphView.visibleGraph
        ToolGraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH -> snapshot.architectureGraphView.visibleGraph
        ToolGraphSceneId.WORKSPACE_CLASS_DIAGRAM -> snapshot.classDiagramView.visibleGraph
        ToolGraphSceneId.WORKSPACE_REVIEW_GRAPH -> snapshot.reviewGraphView.visibleGraph
        ToolGraphSceneId.DIFF -> snapshot.diffGraph ?: GraphDocument()
    }
}

/** 节点/边上记录调用展开分组的元数据键。 */
private const val INVOCATION_EXPANSION_ID_KEY = "linkGraph.expansion.id"
