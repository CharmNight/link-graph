package com.charmnight.linkgraph.projection

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
class GraphProjectionKernel(
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
    // P2-1 真正的架构分解：交互式投影委托给独立的 InteractiveProjectionEngine
    private val interactiveEngine = InteractiveProjectionEngine(
        maxVisibleNodes = maxVisibleNodes,
        maxVisibleEdges = maxVisibleEdges,
        upstreamDepth = upstreamDepth,
        downstreamDepth = downstreamDepth,
        maxNeighborsPerDirection = maxNeighborsPerDirection,
    )
    fun projectWindow(
        graph: GraphDocument,
        policy: GraphProjectionPolicy,
    ): GraphProjectionResult {
        if (graph.nodes.isEmpty()) {
            return GraphProjectionResult(
                visibleGraph = graph,
                fullGraph = graph,
                hiddenNodeCount = 0,
                hiddenEdgeCount = 0,
            )
        }

        val maxWindowNodes = policy.maxVisibleNodes.coerceAtLeast(1)
        val maxWindowEdges = policy.maxVisibleEdges.coerceAtLeast(0)
        if (graph.nodes.size <= maxWindowNodes && graph.edges.size <= maxWindowEdges) {
            return GraphProjectionResult(
                visibleGraph = graph,
                fullGraph = graph,
                hiddenNodeCount = 0,
                hiddenEdgeCount = 0,
            )
        }

        val reservesOverflowNode = policy.enableOverflowSummary && maxWindowNodes >= 2 && graph.nodes.size > maxWindowNodes
        val nodeBudget = if (reservesOverflowNode) (maxWindowNodes - 1).coerceAtLeast(1) else maxWindowNodes
        val edgeBudget = if (reservesOverflowNode && maxWindowEdges > 0) (maxWindowEdges - 1).coerceAtLeast(0) else maxWindowEdges
        val nodeById = graph.nodes.associateBy(GraphNode::id)
        val incomingByTarget = graph.edges.groupBy(GraphEdge::toNodeId)
        val outgoingBySource = graph.edges.groupBy(GraphEdge::fromNodeId)
        val nodeComparator = compareBy<GraphNode>({ policy.nodePriority(it) }, { it.title }, { it.id })
        val edgeComparator = compareBy<GraphEdge>({ policy.edgePriority(it) }, GraphEdge::id)
        val visibleNodeIds = linkedSetOf<String>()
        val queue = ArrayDeque<String>()

        fun addNode(nodeId: String?) {
            if (nodeId == null || visibleNodeIds.size >= nodeBudget || nodeId in visibleNodeIds || nodeById[nodeId] == null) {
                return
            }
            visibleNodeIds += nodeId
            queue.addLast(nodeId)
        }

        addNode(policy.anchorNodeId)
        addWindowRoleQuotaNodes(graph, policy.roleMetadataKey, policy.roleQuotas, nodeComparator, ::addNode)
        graph.nodes
            .filter { node -> node.type in policy.seedNodeTypes }
            .sortedWith(nodeComparator)
            .forEach { node -> addNode(node.id) }
        if (visibleNodeIds.isEmpty()) {
            graph.nodes.minWithOrNull(nodeComparator)?.let { node -> addNode(node.id) }
        }

        while (queue.isNotEmpty() && visibleNodeIds.size < nodeBudget) {
            val currentNodeId = queue.removeFirst()
            val candidateEdges = (outgoingBySource[currentNodeId].orEmpty() + incomingByTarget[currentNodeId].orEmpty())
                .sortedWith(
                    compareBy<GraphEdge>(
                        { edge -> policy.edgePriority(edge) },
                        { edge -> policy.nodePriority(nodeById[edge.neighborOf(currentNodeId)] ?: nodeById[currentNodeId]!!) },
                        GraphEdge::id,
                    ),
                )
            for (edge in candidateEdges) {
                if (visibleNodeIds.size >= nodeBudget) {
                    break
                }
                addNode(edge.neighborOf(currentNodeId))
            }
        }

        if (policy.fillDisconnectedNodes && visibleNodeIds.size < nodeBudget) {
            graph.nodes
                .sortedWith(nodeComparator)
                .forEach { node -> addNode(node.id) }
        }

        val visibleEdgeIds = graph.edges
            .asSequence()
            .filter { edge -> edge.fromNodeId in visibleNodeIds && edge.toNodeId in visibleNodeIds }
            .sortedWith(edgeComparator)
            .take(edgeBudget)
            .mapTo(linkedSetOf(), GraphEdge::id)
        val visibleNodes = graph.nodes.filter { node -> node.id in visibleNodeIds }.toMutableList()
        val visibleEdges = graph.edges.filter { edge -> edge.id in visibleEdgeIds }.toMutableList()
        val hiddenNodeIds = graph.nodes.mapTo(linkedSetOf(), GraphNode::id).also { it.removeAll(visibleNodeIds) }
        val hiddenEdgeIds = graph.edges.mapTo(linkedSetOf(), GraphEdge::id).also { it.removeAll(visibleEdgeIds) }

        if (policy.enableOverflowSummary && hiddenNodeIds.isNotEmpty() && visibleNodes.size < maxWindowNodes) {
            val overflowAnchorId = policy.anchorNodeId?.takeIf { it in visibleNodeIds }
                ?: visibleNodes.firstOrNull()?.id
            val overflowNode = windowOverflowNode(
                anchorNodeId = overflowAnchorId ?: "graph",
                hiddenNodeCount = hiddenNodeIds.size,
                hiddenEdgeCount = hiddenEdgeIds.size,
                ownerContext = policy.overflowOwnerContext,
            )
            visibleNodes += overflowNode
            if (overflowAnchorId != null && visibleEdges.size < maxWindowEdges) {
                visibleEdges += GraphEdge(
                    id = GraphEdge.stableId(
                        policy.overflowEdgeType,
                        overflowAnchorId,
                        overflowNode.id,
                        "${policy.overflowOwnerContext}-overflow",
                    ),
                    type = policy.overflowEdgeType,
                    fromNodeId = overflowAnchorId,
                    toNodeId = overflowNode.id,
                    label = "还有 ${hiddenNodeIds.size} 个节点未显示",
                    certainty = Certainty.RULE_INFERRED,
                    bindingStatus = BindingStatus.PARTIALLY_SYNCED,
                    sourceTag = GraphSourceTag.UNCERTAIN_FACT,
                )
            }
        }

        val visibleGraph = GraphDocument(
            nodes = visibleNodes,
            edges = visibleEdges,
            patch = graph.patch,
        )
        val visibleOriginalNodeIds = visibleGraph.nodes
            .asSequence()
            .map(GraphNode::id)
            .filter(nodeById::containsKey)
            .toSet()
        val originalEdgeIds = graph.edges.mapTo(linkedSetOf(), GraphEdge::id)
        val visibleOriginalEdgeIds = visibleGraph.edges
            .asSequence()
            .map(GraphEdge::id)
            .filter(originalEdgeIds::contains)
            .toSet()
        val hiddenNodeCount = (graph.nodes.map(GraphNode::id).toSet() - visibleOriginalNodeIds).size
        val hiddenEdgeCount = (graph.edges.map(GraphEdge::id).toSet() - visibleOriginalEdgeIds).size
        return GraphProjectionResult(
            visibleGraph = visibleGraph,
            fullGraph = graph,
            hiddenNodeCount = hiddenNodeCount,
            hiddenEdgeCount = hiddenEdgeCount,
            truncated = hiddenNodeCount > 0 || hiddenEdgeCount > 0,
        )
    }
    /** 把完整事实图投影成适合交互画布展示的精简视图。委托给 [interactiveEngine]。 */
    fun projectInteractive(
        graph: GraphDocument,
        anchorNodeId: String? = null,
    ): InteractiveGraphProjection = interactiveEngine.project(graph, anchorNodeId)


    private fun addWindowRoleQuotaNodes(
        graph: GraphDocument,
        roleMetadataKey: String?,
        roleQuotas: List<GraphWindowRoleQuota>,
        nodeComparator: Comparator<GraphNode>,
        addNode: (String?) -> Unit,
    ) {
        if (roleMetadataKey == null || roleQuotas.isEmpty()) {
            return
        }
        val nodesByRole = graph.nodes.groupBy { node -> node.metadata[roleMetadataKey].orEmpty() }
        roleQuotas.forEach { quota ->
            nodesByRole[quota.role].orEmpty()
                .sortedWith(nodeComparator)
                .take(quota.maxNodes.coerceAtLeast(0))
                .forEach { node -> addNode(node.id) }
        }
    }

    private fun windowOverflowNode(
        anchorNodeId: String,
        hiddenNodeCount: Int,
        hiddenEdgeCount: Int,
        ownerContext: String,
    ): GraphNode =
        GraphNode(
            id = GraphNode.stableId(
                NodeType.UNCERTAIN_LINK,
                "$anchorNodeId-window-overflow",
                ownerContext,
            ),
            type = NodeType.UNCERTAIN_LINK,
            title = "已折叠 $hiddenNodeCount 个节点",
            signature = "另有 $hiddenEdgeCount 条链路未在当前窗口展开",
            doc = "完整图仍保留在后台，可继续展开或调整范围。",
            certainty = Certainty.RULE_INFERRED,
            bindingStatus = BindingStatus.PARTIALLY_SYNCED,
            sourceTag = GraphSourceTag.UNCERTAIN_FACT,
            metadata = mapOf(
                GraphProjectionMetadata.Overflow.DIRECTION to "DOWNSTREAM",
                GraphProjectionMetadata.Hidden.NODE_COUNT to hiddenNodeCount.toString(),
                GraphProjectionMetadata.Hidden.EDGE_COUNT to hiddenEdgeCount.toString(),
                GraphProjectionMetadata.Hidden.CURRENT_METHOD_NODE_COUNT to "0",
                GraphProjectionMetadata.Hidden.CROSS_METHOD_NODE_COUNT to hiddenNodeCount.toString(),
                GraphProjectionMetadata.Overflow.BOUNDARY_NODE_COUNT to "0",
                GraphProjectionMetadata.Overflow.EXPANDABLE_NODE_COUNT to hiddenNodeCount.toString(),
                GraphProjectionMetadata.Overflow.PRESENTATION to "EXPANDABLE",
                GraphProjectionMetadata.Overflow.KIND to "GRAPH_WINDOW",
                GraphProjectionMetadata.Overflow.ANCHOR_NODE_ID to anchorNodeId,
            ),
        )

    /** GraphEdge.neighborOf 已抽到顶层（详见 GraphProjectionKernelHelpers.kt）。 */

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

    /** shouldSuppressCrossMethodBodyExpansion 已抽到顶层（详见 GraphProjectionKernelHelpers.kt）。 */

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
    /** seedDepthFromAnchor 已抽到顶层（详见 GraphProjectionKernelHelpers.kt）。 */
    /** nextTraversalDepth 已抽到顶层（详见 GraphProjectionKernelHelpers.kt）。 */
    /** isStructuralFlowEdge 已抽到顶层（详见 GraphProjectionKernelHelpers.kt）。 */
    /** collectCurrentMethodNodeIds 已抽到顶层（详见 GraphProjectionKernelHelpers.kt）。 */
    /** collectAnchorMethodBodyNodeIds 已抽到顶层（详见 GraphProjectionKernelHelpers.kt）。 */
    /** overflowNode 已抽到顶层（详见 GraphProjectionKernelHelpers.kt）。 */

    /** 计算边在展示中的优先级。 */
    private fun edgePriority(
        edge: GraphEdge,
        nodeById: Map<String, GraphNode>,
    ): Int = com.charmnight.linkgraph.projection.edgePriority(edge, nodeById)

    /** 返回方向遍历时使用的边排序器。 */
    private fun edgeComparator(nodeById: Map<String, GraphNode>): Comparator<GraphEdge> =
        com.charmnight.linkgraph.projection.edgeComparator(nodeById)

    /** 返回锚点一跳邻居选择时使用的边排序器。 */
    private fun rootEdgeComparator(nodeById: Map<String, GraphNode>): Comparator<GraphEdge> =
        com.charmnight.linkgraph.projection.rootEdgeComparator(nodeById)

    /** 读取根调用顺序。 */
    private fun rootCallOrder(edge: GraphEdge): Int? = com.charmnight.linkgraph.projection.rootCallOrder(edge)

    /** 判断节点是否已经是提取阶段生成的溢出摘要节点。 */
    private fun isExtractionOverflowNode(node: GraphNode?): Boolean =
        com.charmnight.linkgraph.projection.isExtractionOverflowNode(node)

    /** 计算节点在展示中的优先级。 */
    private fun nodePriority(node: GraphNode?): Int =
        com.charmnight.linkgraph.projection.nodePriority(node)

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
    private fun isAccessorLike(node: GraphNode): Boolean =
        com.charmnight.linkgraph.projection.isAccessorLike(node)

    /** OverflowDirection 已抽到顶层（详见 GraphProjectionKernelHelpers.kt）。 */

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
