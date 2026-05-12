package com.charmnight.linkgraph.application.planning

import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import java.util.PriorityQueue

/**
 * 把完整事实图收束成“可交互画布视图”。
 * 当前策略优先保留锚点、直接上游和直接下游；超出部分不会丢失，
 * 而是折叠成上游/下游摘要节点，完整图仍交给后台用于问答分析和导出。
 */
class InteractiveGraphProjector(
    /** 可见图允许展示的最大节点数。 */
    private val maxVisibleNodes: Int = 26,
    /** 可见图允许展示的最大边数。 */
    private val maxVisibleEdges: Int = 40,
    /** 向上游展开的最大深度。 */
    private val upstreamDepth: Int = 2,
    /** 向下游展开的最大深度。 */
    private val downstreamDepth: Int = 2,
    /** 每个方向单层最多保留的邻居数。 */
    private val maxNeighborsPerDirection: Int = 5,
) {
    /** 把完整事实图投影成适合交互画布展示的精简视图。 */
    fun project(
        graph: GraphDocument,
        anchorNodeId: String? = null,
    ): InteractiveGraphProjection {
        if (graph.nodes.isEmpty()) {
            return InteractiveGraphProjection(
                visibleGraph = graph,
                fullGraph = graph,
                truncated = false,
                hiddenNodeCount = 0,
                hiddenEdgeCount = 0,
            )
        }

        /** 图中节点 ID 到节点对象的映射。 */
        val nodeById = graph.nodes.associateBy { it.id }
        /** 实际使用的锚点节点 ID。 */
        val resolvedAnchorNodeId = anchorNodeId
            ?.takeIf(nodeById::containsKey)
            ?: graph.nodes.firstOrNull { it.type == NodeType.METHOD }?.id
            ?: graph.nodes.first().id
        /** 锚点节点类型。 */
        val anchorNodeType = nodeById[resolvedAnchorNodeId]?.type

        /** 按终点分组的入边索引。 */
        val incomingByTarget = graph.edges.groupBy { it.toNodeId }
        /** 按起点分组的出边索引。 */
        val outgoingBySource = graph.edges.groupBy { it.fromNodeId }
        /** 当前方法所属的节点集合。 */
        val currentMethodNodeIds = collectCurrentMethodNodeIds(
            anchorNodeId = resolvedAnchorNodeId,
            outgoingBySource = outgoingBySource,
            nodeById = nodeById,
        )
        /** 锚点方法本体中的流程节点集合。 */
        val anchorMethodBodyNodeIds = collectAnchorMethodBodyNodeIds(
            anchorNodeId = resolvedAnchorNodeId,
            outgoingBySource = outgoingBySource,
            nodeById = nodeById,
        )
        /** 是否需要额外做方法边界折叠投影。 */
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
        /** 锚点直接上游是否已存在提取阶段生成的溢出节点。 */
        val hasDirectUpstreamExtractionOverflow = incomingByTarget[resolvedAnchorNodeId]
            .orEmpty()
            .any { edge -> isExtractionOverflowNode(nodeById[edge.fromNodeId]) }
        /** 锚点直接下游是否已存在提取阶段生成的溢出节点。 */
        val hasDirectDownstreamExtractionOverflow = outgoingBySource[resolvedAnchorNodeId]
            .orEmpty()
            .any { edge -> isExtractionOverflowNode(nodeById[edge.toNodeId]) }
        /** 生效的节点预算，至少保留锚点。 */
        val effectiveNodeBudget = maxVisibleNodes.coerceAtLeast(1)
        /** 生效的边预算。 */
        val effectiveEdgeBudget = maxVisibleEdges.coerceAtLeast(0)
        /** 当前可见节点 ID 集合。 */
        val visibleNodeIds = linkedSetOf(resolvedAnchorNodeId)
        /** 当前可见边 ID 集合。 */
        val visibleEdgeIds = linkedSetOf<String>()

        /** 被折叠的上游节点集合。 */
        val upstreamHiddenNodeIds = linkedSetOf<String>()
        /** 被折叠的上游边集合。 */
        val upstreamHiddenEdgeIds = linkedSetOf<String>()
        /** 被折叠的下游节点集合。 */
        val downstreamHiddenNodeIds = linkedSetOf<String>()
        /** 被折叠的下游边集合。 */
        val downstreamHiddenEdgeIds = linkedSetOf<String>()
        /** 因方法体边界限制而折叠的下游节点集合。 */
        val downstreamBoundaryHiddenNodeIds = linkedSetOf<String>()
        /** 因方法体边界限制而折叠的下游边集合。 */
        val downstreamBoundaryHiddenEdgeIds = linkedSetOf<String>()

        /** 锚点直接邻居的选择结果。 */
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

        /** 对隐藏的上游方向做闭包扩展，统计完整隐藏规模。 */
        expandHiddenDirection(
            frontierNodeIds = upstreamHiddenNodeIds,
            frontierEdgeIds = upstreamHiddenEdgeIds,
            edgesByNodeId = incomingByTarget,
            resolveNeighborId = { edge -> edge.fromNodeId },
            visibleNodeIds = visibleNodeIds,
        )
        expandHiddenDirection(
            frontierNodeIds = downstreamHiddenNodeIds,
            frontierEdgeIds = downstreamHiddenEdgeIds,
            edgesByNodeId = outgoingBySource,
            resolveNeighborId = { edge -> edge.toNodeId },
            visibleNodeIds = visibleNodeIds,
        )

        /** 继续向上游收集可见节点，同时累计超预算节点。 */
        collectDirection(
            seeds = anchorNeighbors.upstreamSeeds,
            depthLimit = upstreamDepth,
            edgesByNodeId = incomingByTarget,
            resolveNeighborId = { edge -> edge.fromNodeId },
            visibleNodeIds = visibleNodeIds,
            visibleEdgeIds = visibleEdgeIds,
            hiddenNodeIds = upstreamHiddenNodeIds,
            hiddenEdgeIds = upstreamHiddenEdgeIds,
            nodeById = nodeById,
            nodeBudget = effectiveNodeBudget,
            edgeBudget = effectiveEdgeBudget,
            currentMethodNodeIds = currentMethodNodeIds,
            anchorMethodBodyNodeIds = anchorMethodBodyNodeIds,
            anchorNodeType = anchorNodeType,
            boundaryHiddenNodeIds = null,
            boundaryHiddenEdgeIds = null,
        )
        /** 继续向下游收集可见节点，同时累计超预算节点。 */
        collectDirection(
            seeds = anchorNeighbors.downstreamSeeds,
            depthLimit = downstreamDepth,
            edgesByNodeId = outgoingBySource,
            resolveNeighborId = { edge -> edge.toNodeId },
            visibleNodeIds = visibleNodeIds,
            visibleEdgeIds = visibleEdgeIds,
            hiddenNodeIds = downstreamHiddenNodeIds,
            hiddenEdgeIds = downstreamHiddenEdgeIds,
            nodeById = nodeById,
            nodeBudget = effectiveNodeBudget,
            edgeBudget = effectiveEdgeBudget,
            currentMethodNodeIds = currentMethodNodeIds,
            anchorMethodBodyNodeIds = anchorMethodBodyNodeIds,
            anchorNodeType = anchorNodeType,
            boundaryHiddenNodeIds = downstreamBoundaryHiddenNodeIds,
            boundaryHiddenEdgeIds = downstreamBoundaryHiddenEdgeIds,
        )

        /** 再次扩展隐藏集合，确保统计覆盖所有折叠节点。 */
        expandHiddenDirection(
            frontierNodeIds = upstreamHiddenNodeIds,
            frontierEdgeIds = upstreamHiddenEdgeIds,
            edgesByNodeId = incomingByTarget,
            resolveNeighborId = { edge -> edge.fromNodeId },
            visibleNodeIds = visibleNodeIds,
        )
        expandHiddenDirection(
            frontierNodeIds = downstreamHiddenNodeIds,
            frontierEdgeIds = downstreamHiddenEdgeIds,
            edgesByNodeId = outgoingBySource,
            resolveNeighborId = { edge -> edge.toNodeId },
            visibleNodeIds = visibleNodeIds,
        )
        expandHiddenDirection(
            frontierNodeIds = downstreamBoundaryHiddenNodeIds,
            frontierEdgeIds = downstreamBoundaryHiddenEdgeIds,
            edgesByNodeId = outgoingBySource,
            resolveNeighborId = { edge -> edge.toNodeId },
            visibleNodeIds = visibleNodeIds,
        )

        /** 在已选可见节点之间补齐优先级最高的边。 */
        graph.edges
            .asSequence()
            .filter { edge -> edge.fromNodeId in visibleNodeIds && edge.toNodeId in visibleNodeIds }
            .sortedWith(compareBy({ edgePriority(it, nodeById) }, { it.id }))
            .forEach { edge ->
                if (visibleEdgeIds.size < effectiveEdgeBudget) {
                    visibleEdgeIds += edge.id
                }
            }

        /** 从隐藏集合中剔除最终变为可见的节点。 */
        upstreamHiddenNodeIds.removeAll(visibleNodeIds)
        downstreamHiddenNodeIds.removeAll(visibleNodeIds)
        downstreamBoundaryHiddenNodeIds.removeAll(visibleNodeIds)
        upstreamHiddenEdgeIds.removeAll(visibleEdgeIds)
        downstreamHiddenEdgeIds.removeAll(visibleEdgeIds)
        downstreamBoundaryHiddenEdgeIds.removeAll(visibleEdgeIds)

        /** 最终可见节点列表。 */
        val visibleNodes = graph.nodes.filter { it.id in visibleNodeIds }.toMutableList()
        /** 最终可见边列表。 */
        val visibleEdges = graph.edges.filter { it.id in visibleEdgeIds }.toMutableList()

        if (
            (upstreamHiddenNodeIds.isNotEmpty() || upstreamHiddenEdgeIds.isNotEmpty()) &&
            !hasDirectUpstreamExtractionOverflow &&
            visibleNodes.size < maxVisibleNodes &&
            visibleEdges.size < maxVisibleEdges
        ) {
            /** 上游折叠摘要节点。 */
            val overflowNode = overflowNode(
                anchorNodeId = resolvedAnchorNodeId,
                direction = OverflowDirection.UPSTREAM,
                hiddenNodeCount = upstreamHiddenNodeIds.size,
                hiddenEdgeCount = upstreamHiddenEdgeIds.size,
                hiddenCurrentMethodNodeCount = upstreamHiddenNodeIds.count { it in currentMethodNodeIds },
                hiddenCrossMethodNodeCount = upstreamHiddenNodeIds.count { it !in currentMethodNodeIds },
            )
            visibleNodes += overflowNode
            visibleEdges += GraphEdge(
                id = GraphEdge.stableId(EdgeType.CALL, overflowNode.id, resolvedAnchorNodeId, "interactive-overflow"),
                type = EdgeType.CALL,
                fromNodeId = overflowNode.id,
                toNodeId = resolvedAnchorNodeId,
                label = "还有 ${upstreamHiddenNodeIds.size} 个上游节点",
                certainty = Certainty.RULE_INFERRED,
                bindingStatus = BindingStatus.PARTIALLY_SYNCED,
                sourceTag = GraphSourceTag.UNCERTAIN_FACT,
            )
        }

        if (
            (
                downstreamHiddenNodeIds.isNotEmpty() ||
                    downstreamHiddenEdgeIds.isNotEmpty() ||
                    downstreamBoundaryHiddenNodeIds.isNotEmpty() ||
                    downstreamBoundaryHiddenEdgeIds.isNotEmpty()
                ) &&
            !hasDirectDownstreamExtractionOverflow &&
            visibleNodes.size < maxVisibleNodes &&
            visibleEdges.size < maxVisibleEdges
        ) {
            /** 下游所有隐藏节点的总集合。 */
            val totalDownstreamHiddenNodeIds = linkedSetOf<String>().apply {
                addAll(downstreamHiddenNodeIds)
                addAll(downstreamBoundaryHiddenNodeIds)
            }
            /** 下游所有隐藏边的总集合。 */
            val totalDownstreamHiddenEdgeIds = linkedSetOf<String>().apply {
                addAll(downstreamHiddenEdgeIds)
                addAll(downstreamBoundaryHiddenEdgeIds)
            }
            /** 下游折叠摘要节点。 */
            val overflowNode = overflowNode(
                anchorNodeId = resolvedAnchorNodeId,
                direction = OverflowDirection.DOWNSTREAM,
                hiddenNodeCount = totalDownstreamHiddenNodeIds.size,
                hiddenEdgeCount = totalDownstreamHiddenEdgeIds.size,
                hiddenCurrentMethodNodeCount = totalDownstreamHiddenNodeIds.count { it in currentMethodNodeIds },
                hiddenCrossMethodNodeCount = totalDownstreamHiddenNodeIds.count { it !in currentMethodNodeIds },
                boundaryNodeCount = downstreamBoundaryHiddenNodeIds.size,
                expandableNodeCount = downstreamHiddenNodeIds.size,
            )
            visibleNodes += overflowNode
            visibleEdges += GraphEdge(
                id = GraphEdge.stableId(EdgeType.CALL, resolvedAnchorNodeId, overflowNode.id, "interactive-overflow"),
                type = EdgeType.CALL,
                fromNodeId = resolvedAnchorNodeId,
                toNodeId = overflowNode.id,
                label = "还有 ${totalDownstreamHiddenNodeIds.size} 个下游节点",
                certainty = Certainty.RULE_INFERRED,
                bindingStatus = BindingStatus.PARTIALLY_SYNCED,
                sourceTag = GraphSourceTag.UNCERTAIN_FACT,
            )
        }

        /** 完整图中的全部节点 ID。 */
        val fullNodeIds = graph.nodes.mapTo(linkedSetOf()) { it.id }
        /** 完整图中的全部边 ID。 */
        val fullEdgeIds = graph.edges.mapTo(linkedSetOf()) { it.id }
        /** 可见节点中仍属于完整图的节点 ID。 */
        val visibleFullNodeIds = visibleNodes.mapNotNullTo(linkedSetOf()) { node ->
            node.id.takeIf(fullNodeIds::contains)
        }
        /** 可见边中仍属于完整图的边 ID。 */
        val visibleFullEdgeIds = visibleEdges.mapNotNullTo(linkedSetOf()) { edge ->
            edge.id.takeIf(fullEdgeIds::contains)
        }
        /** 被折叠的节点总数。 */
        val hiddenNodeCount = (fullNodeIds - visibleFullNodeIds).size
        /** 被折叠的边总数。 */
        val hiddenEdgeCount = (fullEdgeIds - visibleFullEdgeIds).size
        /** 被折叠的当前方法内部节点数。 */
        val hiddenCurrentMethodNodeCount = (currentMethodNodeIds - visibleFullNodeIds).size
        /** 被折叠的跨方法节点数。 */
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

    /** 先为锚点挑选最重要的一圈邻居，作为后续展开种子。 */
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
        /** 候选上游边列表。 */
        val upstreamCandidates = if (upstreamDepth > 0) {
            incomingEdges.sortedWith(rootEdgeComparator(nodeById))
        } else {
            emptyList()
        }
        /** 候选下游边列表。 */
        val downstreamCandidates = if (downstreamDepth > 0) {
            outgoingEdges.sortedWith(rootEdgeComparator(nodeById))
        } else {
            emptyList()
        }

        /** 锚点直接邻居总数。 */
        val totalDirectNeighbors = (upstreamCandidates.map { it.fromNodeId } + downstreamCandidates.map { it.toNodeId })
            .distinct()
            .size
        /** 锚点直接相连边总数。 */
        val totalDirectEdges = upstreamCandidates.size + downstreamCandidates.size
        /** 是否能在预算内完整展示所有一跳邻居。 */
        val canShowAllDirectNeighbors =
            visibleNodeIds.size + totalDirectNeighbors <= nodeBudget &&
                visibleEdgeIds.size + totalDirectEdges <= edgeBudget

        /** 上游方向允许直接保留的邻居上限。 */
        val upstreamSelectionLimit = if (canShowAllDirectNeighbors) Int.MAX_VALUE else maxNeighborsPerDirection
        /** 下游方向允许直接保留的邻居上限。 */
        val downstreamSelectionLimit = if (canShowAllDirectNeighbors) Int.MAX_VALUE else maxNeighborsPerDirection

        /** 上游遍历种子及其初始深度。 */
        val upstreamSeeds = linkedMapOf<String, Int>()
        /** 下游遍历种子及其初始深度。 */
        val downstreamSeeds = linkedMapOf<String, Int>()

        selectAnchorDirection(
            candidateEdges = upstreamCandidates,
            neighborIdOf = { edge -> edge.fromNodeId },
            visibleNodeIds = visibleNodeIds,
            visibleEdgeIds = visibleEdgeIds,
            hiddenNodeIds = upstreamHiddenNodeIds,
            hiddenEdgeIds = upstreamHiddenEdgeIds,
            selectedSeeds = upstreamSeeds,
            selectionLimit = upstreamSelectionLimit,
            nodeBudget = nodeBudget,
            edgeBudget = edgeBudget,
            nodeById = nodeById,
        )
        selectAnchorDirection(
            candidateEdges = downstreamCandidates,
            neighborIdOf = { edge -> edge.toNodeId },
            visibleNodeIds = visibleNodeIds,
            visibleEdgeIds = visibleEdgeIds,
            hiddenNodeIds = downstreamHiddenNodeIds,
            hiddenEdgeIds = downstreamHiddenEdgeIds,
            selectedSeeds = downstreamSeeds,
            selectionLimit = downstreamSelectionLimit,
            nodeBudget = nodeBudget,
            edgeBudget = edgeBudget,
            nodeById = nodeById,
        )

        return AnchorNeighborSelection(
            upstreamSeeds = upstreamSeeds.entries.map { TraversalSeed(it.key, it.value) },
            downstreamSeeds = downstreamSeeds.entries.map { TraversalSeed(it.key, it.value) },
        )
    }

    /** 在单个方向上为锚点选择一跳邻居。 */
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
        /** 当前方向已选中的邻居数。 */
        var selectedCount = 0
        candidateEdges.forEach { edge ->
            /** 当前边指向的邻居节点 ID。 */
            val neighborId = neighborIdOf(edge)
            /** 当前邻居是否还能在预算内展示。 */
            val canShowNeighbor =
                neighborId in visibleNodeIds ||
                    (
                        selectedCount < selectionLimit &&
                            visibleNodeIds.size < nodeBudget &&
                            visibleEdgeIds.size < edgeBudget
                        )
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
            /** 该邻居从锚点开始的初始遍历深度。 */
            val seedDepth = seedDepthFromAnchor(edge, nodeById[neighborId])
            /** 当前邻居已经记录的更优初始深度。 */
            val existingDepth = selectedSeeds[neighborId]
            if (existingDepth == null || seedDepth < existingDepth) {
                selectedSeeds[neighborId] = seedDepth
            }
        }
    }

    /** 按方向继续展开种子节点。 */
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
        if (depthLimit <= 0 || seeds.isEmpty()) {
            return
        }
        /** 遍历帧顺序号，用于稳定优先队列顺序。 */
        var frameSequence = 0L
        /** 按深度和优先级排序的待遍历队列。 */
        val queue = PriorityQueue(
            compareBy<TraversalFrame>(
                { it.depth },
                { traversalNodePriority(it.nodeId, nodeById, currentMethodNodeIds) },
                { it.sequence },
                { it.nodeId },
            ),
        ).apply {
            seeds
                .distinctBy { it.nodeId }
                .forEach { seed ->
                    add(TraversalFrame(seed.nodeId, seed.depth, frameSequence++))
                }
        }
        /** 每个节点当前已知的最优深度。 */
        val bestDepthByNodeId = linkedMapOf<String, Int>().apply {
            seeds.forEach { seed ->
                merge(seed.nodeId, seed.depth, ::minOf)
            }
        }

        while (queue.isNotEmpty()) {
            /** 当前出队的遍历帧。 */
            val current = queue.remove()
            /** 当前节点已知的最优深度。 */
            val bestKnownDepth = bestDepthByNodeId[current.nodeId]
            if (bestKnownDepth != null && current.depth > bestKnownDepth) {
                continue
            }
            /** 当前节点对应方向上的边列表。 */
            val edges = edgesByNodeId[current.nodeId]
                .orEmpty()
                .sortedWith(edgeComparator(nodeById))

            /** 当前节点已消耗的邻居展示数量。 */
            var consumedNeighbors = 0
            edges.forEach { edge ->
                /** 当前边的邻居节点 ID。 */
                val neighborId = resolveNeighborId(edge)
                if (neighborId == current.nodeId) {
                    return@forEach
                }
                /** 当前遍历节点。 */
                val currentNode = nodeById[current.nodeId]
                /** 邻居节点。 */
                val neighborNode = nodeById[neighborId]
                if (shouldSuppressCrossMethodBodyExpansion(anchorNodeType, currentNode, neighborNode, anchorMethodBodyNodeIds)) {
                    boundaryHiddenEdgeIds?.add(edge.id) ?: hiddenEdgeIds.add(edge.id)
                    boundaryHiddenNodeIds?.add(neighborId) ?: hiddenNodeIds.add(neighborId)
                    return@forEach
                }
                /** 继续沿该边前进后的深度。 */
                val nextDepth = nextTraversalDepth(current.depth, edge, neighborNode)

                /** 当前邻居是否还能在预算内展示。 */
                val canShowNeighbor =
                    neighborId in visibleNodeIds ||
                        (
                            nextDepth <= depthLimit &&
                            visibleNodeIds.size < nodeBudget &&
                                visibleEdgeIds.size < edgeBudget &&
                                consumedNeighbors < maxNeighborsPerDirection
                            )

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
                    /** 邻居节点先前记录的最优深度。 */
                    val previousDepth = bestDepthByNodeId[neighborId]
                    if (previousDepth == null || nextDepth < previousDepth) {
                        bestDepthByNodeId[neighborId] = nextDepth
                        queue += TraversalFrame(neighborId, nextDepth, frameSequence++)
                    }
                }
            }
        }
    }

    private fun shouldSuppressCrossMethodBodyExpansion(
        anchorNodeType: NodeType?,
        currentNode: GraphNode?,
        neighborNode: GraphNode?,
        anchorMethodBodyNodeIds: Set<String>,
    ): Boolean {
        if (anchorNodeType != NodeType.METHOD) {
            return false
        }
        if (currentNode == null || neighborNode == null) {
            return false
        }
        if (currentNode.id in anchorMethodBodyNodeIds) {
            return false
        }
        if (currentNode.type != NodeType.METHOD) {
            return false
        }
        return neighborNode.type == NodeType.FLOW_ACTION || neighborNode.type == NodeType.FLOW_SCOPE
    }

    /** 从已知隐藏前沿继续扩展，统计完整被折叠的闭包范围。 */
    private fun expandHiddenDirection(
        frontierNodeIds: LinkedHashSet<String>,
        frontierEdgeIds: LinkedHashSet<String>,
        edgesByNodeId: Map<String, List<GraphEdge>>,
        resolveNeighborId: (GraphEdge) -> String,
        visibleNodeIds: Set<String>,
    ) {
        /** 隐藏节点遍历队列。 */
        val queue = ArrayDeque(frontierNodeIds)
        while (queue.isNotEmpty()) {
            /** 当前正在扩展的隐藏节点。 */
            val currentNodeId = queue.removeFirst()
            edgesByNodeId[currentNodeId].orEmpty().forEach { edge ->
                frontierEdgeIds += edge.id
                /** 当前边对应的邻居节点。 */
                val neighborId = resolveNeighborId(edge)
                if (neighborId !in visibleNodeIds && frontierNodeIds.add(neighborId)) {
                    queue += neighborId
                }
            }
        }
    }

    /** 计算锚点一跳邻居的初始遍历深度。 */
    private fun seedDepthFromAnchor(
        edge: GraphEdge,
        neighborNode: GraphNode?,
    ): Int = if (isStructuralFlowEdge(edge, neighborNode)) 0 else 1

    /** 计算沿边继续遍历后的深度。 */
    private fun nextTraversalDepth(
        currentDepth: Int,
        edge: GraphEdge,
        neighborNode: GraphNode?,
    ): Int = if (isStructuralFlowEdge(edge, neighborNode)) currentDepth else currentDepth + 1

    /** 判断当前边是否属于方法体结构性流程边。 */
    private fun isStructuralFlowEdge(
        edge: GraphEdge,
        neighborNode: GraphNode?,
    ): Boolean = edge.type == EdgeType.CONTAINS_FLOW && neighborNode?.type == NodeType.FLOW_SCOPE

    /** 收集锚点方法及其直接流程体内的重要节点。 */
    private fun collectCurrentMethodNodeIds(
        anchorNodeId: String,
        outgoingBySource: Map<String, List<GraphEdge>>,
        nodeById: Map<String, GraphNode>,
    ): Set<String> {
        /** 当前方法相关节点集合。 */
        val currentMethodNodeIds = linkedSetOf(anchorNodeId)
        /** 广度遍历队列。 */
        val queue = ArrayDeque<String>().apply { add(anchorNodeId) }
        while (queue.isNotEmpty()) {
            /** 当前出队的源节点 ID。 */
            val sourceNodeId = queue.removeFirst()
            outgoingBySource[sourceNodeId]
                .orEmpty()
                .sortedWith(rootEdgeComparator(nodeById))
                .forEach { edge ->
                    /** 当前边指向的目标节点。 */
                    val targetNode = nodeById[edge.toNodeId] ?: return@forEach
                    when {
                        edge.type == EdgeType.CONTAINS_FLOW && targetNode.type == NodeType.FLOW_SCOPE -> {
                            if (currentMethodNodeIds.add(targetNode.id)) {
                                queue += targetNode.id
                            }
                        }

                        edge.type == EdgeType.CALL && targetNode.type != NodeType.UNCERTAIN_LINK -> {
                            if (currentMethodNodeIds.add(targetNode.id) && targetNode.type == NodeType.FLOW_ACTION) {
                                queue += targetNode.id
                            }
                        }
                    }
                }
        }
        return currentMethodNodeIds
    }

    /** 收集锚点方法本体中的流程节点，不跨出方法体。 */
    private fun collectAnchorMethodBodyNodeIds(
        anchorNodeId: String,
        outgoingBySource: Map<String, List<GraphEdge>>,
        nodeById: Map<String, GraphNode>,
    ): Set<String> {
        /** 锚点方法体节点集合。 */
        val bodyNodeIds = linkedSetOf(anchorNodeId)
        /** 广度遍历队列。 */
        val queue = ArrayDeque<String>().apply { add(anchorNodeId) }
        while (queue.isNotEmpty()) {
            /** 当前出队的源节点 ID。 */
            val sourceNodeId = queue.removeFirst()
            outgoingBySource[sourceNodeId]
                .orEmpty()
                .sortedWith(rootEdgeComparator(nodeById))
                .forEach { edge ->
                    /** 当前边指向的目标节点。 */
                    val targetNode = nodeById[edge.toNodeId] ?: return@forEach
                    when {
                        edge.type == EdgeType.CONTAINS_FLOW && targetNode.type == NodeType.FLOW_SCOPE -> {
                            if (bodyNodeIds.add(targetNode.id)) {
                                queue += targetNode.id
                            }
                        }

                        edge.type == EdgeType.CALL && targetNode.type == NodeType.FLOW_ACTION -> {
                            if (bodyNodeIds.add(targetNode.id)) {
                                queue += targetNode.id
                            }
                        }
                    }
                }
        }
        return bodyNodeIds
    }

    /** 创建上下游折叠摘要节点。 */
    private fun overflowNode(
        anchorNodeId: String,
        direction: OverflowDirection,
        hiddenNodeCount: Int,
        hiddenEdgeCount: Int,
        hiddenCurrentMethodNodeCount: Int,
        hiddenCrossMethodNodeCount: Int,
        boundaryNodeCount: Int = 0,
        expandableNodeCount: Int = hiddenNodeCount,
    ) = GraphNode(
        id = GraphNode.stableId(
            NodeType.UNCERTAIN_LINK,
            "$anchorNodeId-${direction.name.lowercase()}-overflow",
            "interactive",
        ),
        type = NodeType.UNCERTAIN_LINK,
        title = "${direction.label}已折叠 $hiddenNodeCount 个节点",
        signature = "另有 $hiddenEdgeCount 条链路未在当前交互窗口展开",
        doc = "完整事实图仍保留在后台，可继续问答、导出 Mermaid，或切换到其他方法重新聚焦。",
        certainty = Certainty.RULE_INFERRED,
        bindingStatus = BindingStatus.PARTIALLY_SYNCED,
        sourceTag = GraphSourceTag.UNCERTAIN_FACT,
        metadata = mapOf(
            "linkGraph.overflow.direction" to direction.name,
            "linkGraph.hiddenNodeCount" to hiddenNodeCount.toString(),
            "linkGraph.hiddenEdgeCount" to hiddenEdgeCount.toString(),
            "linkGraph.hidden.currentMethodNodeCount" to hiddenCurrentMethodNodeCount.toString(),
            "linkGraph.hidden.crossMethodNodeCount" to hiddenCrossMethodNodeCount.toString(),
            "linkGraph.overflow.boundaryNodeCount" to boundaryNodeCount.toString(),
            "linkGraph.overflow.expandableNodeCount" to expandableNodeCount.toString(),
            "linkGraph.overflow.presentation" to if (expandableNodeCount == 0 && boundaryNodeCount > 0) {
                "METHOD_BOUNDARY"
            } else {
                "EXPANDABLE"
            },
        ),
    )

    /** 计算边在展示中的优先级。 */
    private fun edgePriority(
        edge: GraphEdge,
        nodeById: Map<String, GraphNode>,
    ): Int {
        /** 由边类型决定的结构优先级。 */
        val structuralPriority = when (edge.type) {
            EdgeType.CONTAINS_FLOW -> 0
            EdgeType.CALL -> 1
            EdgeType.ROUTES_TO -> 2
            EdgeType.PUBLISHES_TO, EdgeType.CONSUMES_FROM -> 3
            else -> 4
        }
        /** 由端点节点类型决定的附加优先级。 */
        val endpointPriority = maxOf(
            nodePriority(nodeById[edge.fromNodeId]),
            nodePriority(nodeById[edge.toNodeId]),
        )
        return structuralPriority * 10 + endpointPriority
    }

    /** 返回方向遍历时使用的边排序器。 */
    private fun edgeComparator(nodeById: Map<String, GraphNode>): Comparator<GraphEdge> {
        return compareBy(
            { edgePriority(it, nodeById) },
            { rootCallOrder(it) ?: Int.MAX_VALUE },
            { it.id },
        )
    }

    /** 返回锚点一跳邻居选择时使用的边排序器。 */
    private fun rootEdgeComparator(nodeById: Map<String, GraphNode>): Comparator<GraphEdge> {
        return compareBy(
            { rootCallOrder(it) ?: Int.MAX_VALUE },
            { edgePriority(it, nodeById) },
            { it.id },
        )
    }

    /** 读取根调用顺序。 */
    private fun rootCallOrder(edge: GraphEdge): Int? = edge.metadata["callOrder"]?.toIntOrNull()

    /** 判断节点是否已经是提取阶段生成的溢出摘要节点。 */
    private fun isExtractionOverflowNode(node: GraphNode?): Boolean {
        return node?.metadata?.containsKey("linkGraph.overflow.hiddenMethodCount") == true &&
            node.metadata.containsKey("linkGraph.overflow.titlePrefix")
    }

    /** 计算节点在展示中的优先级。 */
    private fun nodePriority(node: GraphNode?): Int {
        if (node == null) {
            return 4
        }
        if (isAccessorLike(node)) {
            return 4
        }
        return when (node.type) {
            NodeType.FLOW_SCOPE -> 0
            NodeType.FLOW_ACTION -> 1
            NodeType.TERMINAL,
            NodeType.MERGE -> 1
            NodeType.METHOD -> 2
            NodeType.SQL,
            NodeType.HTTP_ENDPOINT,
            NodeType.FEIGN_CLIENT,
            NodeType.DUBBO_SERVICE,
            NodeType.MQ_TOPIC,
            NodeType.MQ_CONSUMER -> 2
            NodeType.CLASS,
            NodeType.CONFIG_ITEM,
            NodeType.XML_RESOURCE,
            NodeType.DOC_PAGE -> 3
            NodeType.UNCERTAIN_LINK -> 4
        }
    }

    /** 计算遍历过程中节点的优先级，优先保留当前方法内部节点。 */
    private fun traversalNodePriority(
        nodeId: String,
        nodeById: Map<String, GraphNode>,
        currentMethodNodeIds: Set<String>,
    ): Int {
        /** 当前方法节点的优先级提升。 */
        val currentMethodBoost = if (nodeId in currentMethodNodeIds) 0 else 10
        return currentMethodBoost + nodePriority(nodeById[nodeId])
    }

    /** 判断方法节点是否更像 getter/setter 等访问器。 */
    private fun isAccessorLike(node: GraphNode): Boolean {
        if (node.type != NodeType.METHOD) {
            return false
        }
        /** 方法简单名。 */
        val methodName = node.title.substringAfterLast('.')
        /** 参数列表文本。 */
        val parameterText = node.signature.orEmpty().substringAfter('(', "").substringBefore(')', "")
        /** 参数个数。 */
        val parameterCount = parameterText
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .size
        /** 是否像 getter。 */
        val getterLike =
            (methodName.startsWith("get") && methodName.length > 3 && parameterCount == 0) ||
                (methodName.startsWith("is") && methodName.length > 2 && parameterCount == 0) ||
                (methodName.startsWith("has") && methodName.length > 3 && parameterCount == 0)
        /** 是否像 setter。 */
        val setterLike = methodName.startsWith("set") && methodName.length > 3 && parameterCount <= 1
        return getterLike || setterLike
    }

    /** 折叠摘要节点的方向类型。 */
    private enum class OverflowDirection(val label: String) {
        UPSTREAM("上游"),
        DOWNSTREAM("下游"),
    }

    /** 遍历队列中的一帧状态。 */
    private data class TraversalFrame(
        /** 当前节点 ID。 */
        val nodeId: String,
        /** 当前遍历深度。 */
        val depth: Int,
        /** 入队顺序号。 */
        val sequence: Long,
    )

    /** 方向遍历的起始种子。 */
    private data class TraversalSeed(
        /** 种子节点 ID。 */
        val nodeId: String,
        /** 种子初始深度。 */
        val depth: Int,
    )

    /** 锚点直接邻居选择结果。 */
    private data class AnchorNeighborSelection(
        /** 上游方向的种子列表。 */
        val upstreamSeeds: List<TraversalSeed>,
        /** 下游方向的种子列表。 */
        val downstreamSeeds: List<TraversalSeed>,
    )
}

/** 可交互图投影结果。 */
data class InteractiveGraphProjection(
    /** 适合前端展示的可见图。 */
    val visibleGraph: GraphDocument,
    /** 后台保留的完整图。 */
    val fullGraph: GraphDocument,
    /** 是否发生了折叠裁剪。 */
    val truncated: Boolean,
    /** 被折叠的节点总数。 */
    val hiddenNodeCount: Int,
    /** 被折叠的边总数。 */
    val hiddenEdgeCount: Int,
    /** 被折叠的当前方法内部节点数。 */
    val hiddenCurrentMethodNodeCount: Int = 0,
    /** 被折叠的跨方法节点数。 */
    val hiddenCrossMethodNodeCount: Int = 0,
)
