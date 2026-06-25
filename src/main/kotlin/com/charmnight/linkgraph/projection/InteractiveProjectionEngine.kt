package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import java.util.ArrayDeque

/**
 * 锚点为中心的交互式投影引擎（P2-1 真正的架构分解）。
 *
 * 从 GraphProjectionKernel.projectInteractive() 抽出的独立 class。
 * 负责基于锚点节点的方向性 BFS 遍历：向上游和下游分别展开到有限深度，
 * 方法体边界限制，超出预算的节点折叠为溢出摘要节点。
 */
internal class InteractiveProjectionEngine(
    private val maxVisibleNodes: Int,
    private val maxVisibleEdges: Int,
    private val upstreamDepth: Int,
    private val downstreamDepth: Int,
    private val maxNeighborsPerDirection: Int,
) {
    fun project(graph: GraphDocument, anchorNodeId: String? = null): InteractiveGraphProjection {
        if (graph.nodes.isEmpty()) {
            return InteractiveGraphProjection(
                visibleGraph = graph,
                fullGraph = graph,
                truncated = false,
                hiddenNodeCount = 0,
                hiddenEdgeCount = 0,
            )
        }

        val nodeById = graph.nodes.associateBy { it.id }
        val resolvedAnchorNodeId = anchorNodeId
            ?.takeIf(nodeById::containsKey)
            ?: graph.nodes.firstOrNull { it.type == NodeType.METHOD }?.id
            ?: graph.nodes.first().id
        val anchorNodeType = nodeById[resolvedAnchorNodeId]?.type

        val incomingByTarget = graph.edges.groupBy { it.toNodeId }
        val outgoingBySource = graph.edges.groupBy { it.fromNodeId }
        val currentMethodNodeIds = collectCurrentMethodNodeIds(resolvedAnchorNodeId, outgoingBySource, nodeById)
        val anchorMethodBodyNodeIds = collectAnchorMethodBodyNodeIds(resolvedAnchorNodeId, outgoingBySource, nodeById)
        val needsBoundaryProjection =
            anchorNodeType == NodeType.METHOD &&
                graph.nodes.any { node ->
                    (node.type == NodeType.FLOW_ACTION || node.type == NodeType.FLOW_SCOPE) &&
                        node.id !in anchorMethodBodyNodeIds
                }
        if (graph.nodes.size <= maxVisibleNodes && graph.edges.size <= maxVisibleEdges && !needsBoundaryProjection) {
            return InteractiveGraphProjection(
                visibleGraph = graph,
                fullGraph = graph,
                truncated = false,
                hiddenNodeCount = 0,
                hiddenEdgeCount = 0,
            )
        }

        val hasDirectUpstreamExtractionOverflow = incomingByTarget[resolvedAnchorNodeId].orEmpty()
            .any { edge -> isExtractionOverflowNode(nodeById[edge.fromNodeId]) }
        val hasDirectDownstreamExtractionOverflow = outgoingBySource[resolvedAnchorNodeId].orEmpty()
            .any { edge -> isExtractionOverflowNode(nodeById[edge.toNodeId]) }

        val effectiveNodeBudget = maxVisibleNodes.coerceAtLeast(1)
        val effectiveEdgeBudget = maxVisibleEdges.coerceAtLeast(0)
        val visibleNodeIds = linkedSetOf(resolvedAnchorNodeId)
        val visibleEdgeIds = linkedSetOf<String>()

        val upstreamHiddenNodeIds = linkedSetOf<String>()
        val upstreamHiddenEdgeIds = linkedSetOf<String>()
        val downstreamHiddenNodeIds = linkedSetOf<String>()
        val downstreamHiddenEdgeIds = linkedSetOf<String>()
        val downstreamBoundaryHiddenNodeIds = linkedSetOf<String>()
        val downstreamBoundaryHiddenEdgeIds = linkedSetOf<String>()

        val anchorNeighbors = collectAnchorNeighbors(
            anchorNodeId = resolvedAnchorNodeId,
            upstreamDepth = upstreamDepth,
            downstreamDepth = downstreamDepth,
            incomingEdges = incomingByTarget[resolvedAnchorNodeId].orEmpty(),
            outgoingEdges = outgoingBySource[resolvedAnchorNodeId].orEmpty(),
            visibleNodeIds = visibleNodeIds,
            visibleEdgeIds = visibleEdgeIds,
            upstreamHiddenNodeIds = upstreamHiddenNodeIds,
            upstreamHiddenEdgeIds = upstreamHiddenEdgeIds,
            downstreamHiddenNodeIds = downstreamHiddenNodeIds,
            downstreamHiddenEdgeIds = downstreamHiddenEdgeIds,
            nodeById = nodeById,
            nodeBudget = effectiveNodeBudget,
            edgeBudget = effectiveEdgeBudget,
        )

        expandHiddenDirection(upstreamHiddenNodeIds, upstreamHiddenEdgeIds, incomingByTarget, { edge -> edge.fromNodeId }, visibleNodeIds)
        expandHiddenDirection(downstreamHiddenNodeIds, downstreamHiddenEdgeIds, outgoingBySource, { edge -> edge.toNodeId }, visibleNodeIds)

        collectDirection(
            anchorNeighbors.upstreamSeeds, upstreamDepth, incomingByTarget, { edge -> edge.fromNodeId },
            visibleNodeIds, visibleEdgeIds, upstreamHiddenNodeIds, upstreamHiddenEdgeIds,
            nodeById, effectiveNodeBudget, effectiveEdgeBudget,
            currentMethodNodeIds, anchorMethodBodyNodeIds, anchorNodeType, null, null,
        )
        collectDirection(
            anchorNeighbors.downstreamSeeds, downstreamDepth, outgoingBySource, { edge -> edge.toNodeId },
            visibleNodeIds, visibleEdgeIds, downstreamHiddenNodeIds, downstreamHiddenEdgeIds,
            nodeById, effectiveNodeBudget, effectiveEdgeBudget,
            currentMethodNodeIds, anchorMethodBodyNodeIds, anchorNodeType,
            downstreamBoundaryHiddenNodeIds, downstreamBoundaryHiddenEdgeIds,
        )

        expandHiddenDirection(upstreamHiddenNodeIds, upstreamHiddenEdgeIds, incomingByTarget, { edge -> edge.fromNodeId }, visibleNodeIds)
        expandHiddenDirection(downstreamHiddenNodeIds, downstreamHiddenEdgeIds, outgoingBySource, { edge -> edge.toNodeId }, visibleNodeIds)
        expandHiddenDirection(downstreamBoundaryHiddenNodeIds, downstreamBoundaryHiddenEdgeIds, outgoingBySource, { edge -> edge.toNodeId }, visibleNodeIds)

        graph.edges
            .asSequence()
            .filter { edge -> edge.fromNodeId in visibleNodeIds && edge.toNodeId in visibleNodeIds }
            .sortedWith(compareBy({ edgePriority(it, nodeById) }, { it.id }))
            .forEach { edge ->
                if (visibleEdgeIds.size < effectiveEdgeBudget) {
                    visibleEdgeIds += edge.id
                }
            }

        upstreamHiddenNodeIds.removeAll(visibleNodeIds)
        downstreamHiddenNodeIds.removeAll(visibleNodeIds)
        downstreamBoundaryHiddenNodeIds.removeAll(visibleNodeIds)
        upstreamHiddenEdgeIds.removeAll(visibleEdgeIds)
        downstreamHiddenEdgeIds.removeAll(visibleEdgeIds)
        downstreamBoundaryHiddenEdgeIds.removeAll(visibleEdgeIds)

        val visibleNodes = graph.nodes.filter { it.id in visibleNodeIds }.toMutableList()
        val visibleEdges = graph.edges.filter { it.id in visibleEdgeIds }.toMutableList()

        if (
            (upstreamHiddenNodeIds.isNotEmpty() || upstreamHiddenEdgeIds.isNotEmpty()) &&
            !hasDirectUpstreamExtractionOverflow &&
            visibleNodes.size < maxVisibleNodes && visibleEdges.size < maxVisibleEdges
        ) {
            val upOverflow = overflowNode(
                resolvedAnchorNodeId, OverflowDirection.UPSTREAM,
                upstreamHiddenNodeIds.size, upstreamHiddenEdgeIds.size,
                upstreamHiddenNodeIds.count { it in currentMethodNodeIds },
                upstreamHiddenNodeIds.count { it !in currentMethodNodeIds },
            )
            visibleNodes += upOverflow
            visibleEdges += GraphEdge(
                id = GraphEdge.stableId(EdgeType.CALL, upOverflow.id, resolvedAnchorNodeId, "interactive-overflow"),
                type = EdgeType.CALL, fromNodeId = upOverflow.id, toNodeId = resolvedAnchorNodeId,
                label = "还有 ${upstreamHiddenNodeIds.size} 个上游节点",
                certainty = Certainty.RULE_INFERRED, bindingStatus = BindingStatus.PARTIALLY_SYNCED,
                sourceTag = GraphSourceTag.UNCERTAIN_FACT,
            )
        }

        val totalDownstreamHidden = linkedSetOf<String>().apply {
            addAll(downstreamHiddenNodeIds)
            addAll(downstreamBoundaryHiddenNodeIds)
        }
        val totalDownstreamHiddenEdges = linkedSetOf<String>().apply {
            addAll(downstreamHiddenEdgeIds)
            addAll(downstreamBoundaryHiddenEdgeIds)
        }
        if (
            totalDownstreamHidden.isNotEmpty() && !hasDirectDownstreamExtractionOverflow &&
            visibleNodes.size < maxVisibleNodes && visibleEdges.size < maxVisibleEdges
        ) {
            val downOverflow = overflowNode(
                resolvedAnchorNodeId, OverflowDirection.DOWNSTREAM,
                totalDownstreamHidden.size, totalDownstreamHiddenEdges.size,
                totalDownstreamHidden.count { it in currentMethodNodeIds },
                totalDownstreamHidden.count { it !in currentMethodNodeIds },
                downstreamBoundaryHiddenNodeIds.size,
                downstreamHiddenNodeIds.size,
            )
            visibleNodes += downOverflow
            visibleEdges += GraphEdge(
                id = GraphEdge.stableId(EdgeType.CALL, resolvedAnchorNodeId, downOverflow.id, "interactive-overflow"),
                type = EdgeType.CALL, fromNodeId = resolvedAnchorNodeId, toNodeId = downOverflow.id,
                label = "还有 ${totalDownstreamHidden.size} 个下游节点",
                certainty = Certainty.RULE_INFERRED, bindingStatus = BindingStatus.PARTIALLY_SYNCED,
                sourceTag = GraphSourceTag.UNCERTAIN_FACT,
            )
        }

        val fullNodeIds = graph.nodes.mapTo(linkedSetOf()) { it.id }
        val fullEdgeIds = graph.edges.mapTo(linkedSetOf()) { it.id }
        val visibleFullNodeIds = visibleNodes.mapNotNullTo(linkedSetOf()) { node -> node.id.takeIf(fullNodeIds::contains) }
        val visibleFullEdgeIds = visibleEdges.mapNotNullTo(linkedSetOf()) { edge -> edge.id.takeIf(fullEdgeIds::contains) }
        val hiddenNodeCount = (fullNodeIds - visibleFullNodeIds).size
        val hiddenEdgeCount = (fullEdgeIds - visibleFullEdgeIds).size
        val hiddenCurrentMethodNodeCount = (currentMethodNodeIds - visibleFullNodeIds).size
        val hiddenCrossMethodNodeCount = (fullNodeIds - currentMethodNodeIds - visibleFullNodeIds).size

        return InteractiveGraphProjection(
            visibleGraph = GraphDocument(
                nodes = visibleNodes.sortedBy { it.id },
                edges = visibleEdges.sortedBy { it.id },
                patch = graph.patch,
            ),
            fullGraph = graph,
            truncated = hiddenNodeCount > 0 || hiddenEdgeCount > 0,
            hiddenNodeCount = hiddenNodeCount,
            hiddenEdgeCount = hiddenEdgeCount,
            hiddenCurrentMethodNodeCount = hiddenCurrentMethodNodeCount,
            hiddenCrossMethodNodeCount = hiddenCrossMethodNodeCount,
        )
    }

    private data class TraversalFrame(val nodeId: String, val depth: Int, val sequence: Long)
    private data class TraversalSeed(val nodeId: String, val depth: Int)
    private data class AnchorNeighborSelection(
        val upstreamSeeds: List<TraversalSeed>,
        val downstreamSeeds: List<TraversalSeed>,
    )

    private fun collectAnchorNeighbors(
        anchorNodeId: String,
        upstreamDepth: Int,
        downstreamDepth: Int,
        incomingEdges: List<GraphEdge>,
        outgoingEdges: List<GraphEdge>,
        visibleNodeIds: LinkedHashSet<String>,
        visibleEdgeIds: LinkedHashSet<String>,
        upstreamHiddenNodeIds: LinkedHashSet<String>,
        upstreamHiddenEdgeIds: LinkedHashSet<String>,
        downstreamHiddenNodeIds: LinkedHashSet<String>,
        downstreamHiddenEdgeIds: LinkedHashSet<String>,
        nodeById: Map<String, GraphNode>,
        nodeBudget: Int,
        edgeBudget: Int,
    ): AnchorNeighborSelection {
        val upstreamCandidates = if (upstreamDepth > 0) {
            incomingEdges.sortedWith(rootEdgeComparator(nodeById))
        } else {
            emptyList()
        }
        val downstreamCandidates = if (downstreamDepth > 0) {
            outgoingEdges.sortedWith(rootEdgeComparator(nodeById))
        } else {
            emptyList()
        }

        val totalDirectNeighbors = (upstreamCandidates.map { it.fromNodeId } + downstreamCandidates.map { it.toNodeId }).distinct().size
        val totalDirectEdges = upstreamCandidates.size + downstreamCandidates.size
        val canShowAllDirectNeighbors =
            visibleNodeIds.size + totalDirectNeighbors <= nodeBudget &&
                visibleEdgeIds.size + totalDirectEdges <= edgeBudget

        val upstreamSelectionLimit = if (canShowAllDirectNeighbors) Int.MAX_VALUE else maxNeighborsPerDirection
        val downstreamSelectionLimit = if (canShowAllDirectNeighbors) Int.MAX_VALUE else maxNeighborsPerDirection

        val upstreamSeeds = linkedMapOf<String, Int>()
        val downstreamSeeds = linkedMapOf<String, Int>()

        selectAnchorDirection(upstreamCandidates, { edge -> edge.fromNodeId },
            visibleNodeIds, visibleEdgeIds, upstreamHiddenNodeIds, upstreamHiddenEdgeIds,
            upstreamSeeds, upstreamSelectionLimit, nodeBudget, edgeBudget, nodeById)
        selectAnchorDirection(downstreamCandidates, { edge -> edge.toNodeId },
            visibleNodeIds, visibleEdgeIds, downstreamHiddenNodeIds, downstreamHiddenEdgeIds,
            downstreamSeeds, downstreamSelectionLimit, nodeBudget, edgeBudget, nodeById)

        return AnchorNeighborSelection(
            upstreamSeeds = upstreamSeeds.entries.map { TraversalSeed(it.key, it.value) },
            downstreamSeeds = downstreamSeeds.entries.map { TraversalSeed(it.key, it.value) },
        )
    }

    private fun selectAnchorDirection(
        candidateEdges: List<GraphEdge>,
        neighborIdOf: (GraphEdge) -> String,
        visibleNodeIds: LinkedHashSet<String>,
        visibleEdgeIds: LinkedHashSet<String>,
        hiddenNodeIds: LinkedHashSet<String>,
        hiddenEdgeIds: LinkedHashSet<String>,
        selectedSeeds: LinkedHashMap<String, Int>,
        selectionLimit: Int,
        nodeBudget: Int,
        edgeBudget: Int,
        nodeById: Map<String, GraphNode>,
    ) {
        var selectedCount = 0
        candidateEdges.forEach { edge ->
            val neighborId = neighborIdOf(edge)
            val canShowNeighbor =
                neighborId in visibleNodeIds ||
                    (selectedCount < selectionLimit && visibleNodeIds.size < nodeBudget && visibleEdgeIds.size < edgeBudget)
            if (!canShowNeighbor) {
                hiddenEdgeIds += edge.id
                hiddenNodeIds += neighborId
                return@forEach
            }
            if (neighborId !in visibleNodeIds) {
                visibleNodeIds += neighborId
                selectedCount += 1
            }
            visibleEdgeIds += edge.id
            val seedDepth = seedDepthFromAnchor(edge, nodeById[neighborId])
            val existingDepth = selectedSeeds[neighborId]
            if (existingDepth == null || seedDepth < existingDepth) {
                selectedSeeds[neighborId] = seedDepth
            }
        }
    }

    private fun collectDirection(
        seeds: List<TraversalSeed>,
        depthLimit: Int,
        edgesByNodeId: Map<String, List<GraphEdge>>,
        resolveNeighborId: (GraphEdge) -> String,
        visibleNodeIds: LinkedHashSet<String>,
        visibleEdgeIds: LinkedHashSet<String>,
        hiddenNodeIds: LinkedHashSet<String>,
        hiddenEdgeIds: LinkedHashSet<String>,
        nodeById: Map<String, GraphNode>,
        nodeBudget: Int,
        edgeBudget: Int,
        currentMethodNodeIds: Set<String>,
        anchorMethodBodyNodeIds: Set<String>,
        anchorNodeType: NodeType?,
        boundaryHiddenNodeIds: LinkedHashSet<String>?,
        boundaryHiddenEdgeIds: LinkedHashSet<String>?,
    ) {
        if (depthLimit <= 0 || seeds.isEmpty()) return

        var frameSequence = 0L
        val queue = java.util.PriorityQueue(
            compareBy<TraversalFrame>(
                { it.depth },
                { com.charmnight.linkgraph.projection.traversalNodePriority(it.nodeId, nodeById, currentMethodNodeIds) },
                { it.sequence },
                { it.nodeId },
            ),
        ).apply {
            seeds.distinctBy { it.nodeId }.forEach { seed ->
                add(TraversalFrame(seed.nodeId, seed.depth, frameSequence++))
            }
        }
        val bestDepthByNodeId = linkedMapOf<String, Int>().apply {
            seeds.forEach { seed -> merge(seed.nodeId, seed.depth, ::minOf) }
        }

        while (queue.isNotEmpty()) {
            val current = queue.remove()
            val bestKnownDepth = bestDepthByNodeId[current.nodeId]
            if (bestKnownDepth != null && current.depth > bestKnownDepth) continue
            val edges = edgesByNodeId[current.nodeId].orEmpty().sortedWith(edgeComparator(nodeById))
            var consumedNeighbors = 0
            edges.forEach { edge ->
                val neighborId = resolveNeighborId(edge)
                if (neighborId == current.nodeId) return@forEach
                val currentNode = nodeById[current.nodeId]
                val neighborNode = nodeById[neighborId]
                if (shouldSuppressCrossMethodBodyExpansion(anchorNodeType, currentNode, neighborNode, anchorMethodBodyNodeIds)) {
                    boundaryHiddenEdgeIds?.add(edge.id) ?: hiddenEdgeIds.add(edge.id)
                    boundaryHiddenNodeIds?.add(neighborId) ?: hiddenNodeIds.add(neighborId)
                    return@forEach
                }
                val nextDepth = nextTraversalDepth(current.depth, edge, neighborNode)
                val canShowNeighbor =
                    neighborId in visibleNodeIds ||
                        (nextDepth <= depthLimit && visibleNodeIds.size < nodeBudget &&
                            visibleEdgeIds.size < edgeBudget && consumedNeighbors < maxNeighborsPerDirection)
                if (!canShowNeighbor) {
                    hiddenEdgeIds += edge.id
                    hiddenNodeIds += neighborId
                    return@forEach
                }
                if (neighborId !in visibleNodeIds) {
                    visibleNodeIds += neighborId
                    consumedNeighbors += 1
                }
                if (visibleEdgeIds.size < edgeBudget) {
                    visibleEdgeIds += edge.id
                } else {
                    hiddenEdgeIds += edge.id
                    hiddenNodeIds += neighborId
                    return@forEach
                }
                if (neighborNode != null) {
                    val previousDepth = bestDepthByNodeId[neighborId]
                    if (previousDepth == null || nextDepth < previousDepth) {
                        bestDepthByNodeId[neighborId] = nextDepth
                        queue += TraversalFrame(neighborId, nextDepth, frameSequence++)
                    }
                }
            }
        }
    }

    private fun expandHiddenDirection(
        frontierNodeIds: LinkedHashSet<String>,
        frontierEdgeIds: LinkedHashSet<String>,
        edgesByNodeId: Map<String, List<GraphEdge>>,
        resolveNeighborId: (GraphEdge) -> String,
        visibleNodeIds: Set<String>,
    ) {
        val queue = ArrayDeque(frontierNodeIds.toList())
        while (queue.isNotEmpty()) {
            val currentNodeId = queue.removeFirst()
            val edges = edgesByNodeId[currentNodeId].orEmpty()
            for (edge in edges) {
                val neighborNodeId = resolveNeighborId(edge)
                if (neighborNodeId == currentNodeId) continue
                if (neighborNodeId in visibleNodeIds) continue
                frontierEdgeIds += edge.id
                if (frontierNodeIds.add(neighborNodeId)) { queue.addLast(neighborNodeId) }
            }
        }
    }

}

// collectCurrentMethodNodeIds / collectAnchorMethodBodyNodeIds 使用同包 top-level 函数（GraphProjectionKernelHelpers.kt）。
