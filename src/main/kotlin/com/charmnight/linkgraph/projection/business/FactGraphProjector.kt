package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.semantic.outcome.FactGraphSummary
import com.charmnight.linkgraph.semantic.outcome.FactGraphViewDocument
import com.charmnight.linkgraph.semantic.outcome.graphProjectionIndexForVisibleGraph
import com.charmnight.linkgraph.semantic.outcome.graphProjectionIndexForVisibleGraph
import com.charmnight.linkgraph.projection.GraphWindowPolicy
import com.charmnight.linkgraph.projection.GraphWindowProjector
import com.charmnight.linkgraph.projection.graphProjectionHiddenCounts
import com.charmnight.linkgraph.projection.GraphHiddenBucketProjector
import com.charmnight.linkgraph.presentation.GraphPresentationControls
import com.charmnight.linkgraph.presentation.GraphPresentationLane
import com.charmnight.linkgraph.presentation.GraphPresentationLaneAxis
import com.charmnight.linkgraph.presentation.GraphPresentationTarget
import com.charmnight.linkgraph.presentation.GraphViewPresentation
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.graph.GraphAssembler
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy
import java.util.ArrayDeque

/**
 * 事实图投影器。
 *
 * 把语义分析结果投影为"事实图"视图：以当前方法为锚点，
 * 把上游事实、当前方法与下游事实按列展示，呈现方法级数据流。
 * 内部会基于 BFS 计算每个节点相对锚点的方向（上游/下游/当前），
 * 并通过窗口投影器限制可见规模，最终包装为 [FactGraphViewDocument]。
 *
 * @param graphAssembler 把语义结果组装为完整图的组装器
 * @param windowProjector 窗口投影器，用于裁剪可见规模
 * @param hiddenBucketProjector 隐藏桶投影器，按桶归类被裁剪的节点
 */
class FactGraphProjector(
    private val graphAssembler: GraphAssembler = GraphAssembler(),
    private val windowProjector: GraphWindowProjector = GraphWindowProjector(),
    private val hiddenBucketProjector: GraphHiddenBucketProjector = GraphHiddenBucketProjector(),
) : GraphProjector {
    /**
     * 把语义分析结果投影为事实图视图。
     *
     * @param analysisResult 语义分析结果
     * @param projectionPolicy 投影策略（最大可见规模等）
     * @return 事实图视图文档
     */
    fun project(
        analysisResult: SemanticAnalysisResult,
        projectionPolicy: ProjectionPolicy = ProjectionPolicy(),
    ): FactGraphViewDocument {
        val assembledGraph = graphAssembler.assemble(analysisResult, AnalysisDisplayMode.FACT_GRAPH)
        // 锚点节点优先使用语义结果的锚点，回退到第一个节点。
        val anchorNodeId = analysisResult.anchors.firstOrNull()?.targetUnitId
            ?: assembledGraph.nodes.firstOrNull()?.id
        val fullGraph = assembledGraph.withFactPresentationMetadata(anchorNodeId)
        val visibleGraph = projectGraph(fullGraph, anchorNodeId, projectionPolicy)
            .withOverflowPresentationMetadata("downstream", "DOWNSTREAM", 30)
        val hiddenCounts = graphProjectionHiddenCounts(visibleGraph = visibleGraph, fullGraph = fullGraph)
        val targetNode = fullGraph.nodes.firstOrNull { it.id == anchorNodeId }
        return FactGraphViewDocument(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = anchorNodeId,
            summary = FactGraphSummary(
                anchorTitle = fullGraph.nodes.firstOrNull { it.id == anchorNodeId }?.title,
                visibleNodeCount = visibleGraph.nodes.size,
                fullNodeCount = fullGraph.nodes.size,
                hiddenNodeCount = hiddenCounts.hiddenNodeCount,
                hiddenEdgeCount = hiddenCounts.hiddenEdgeCount,
                truncated = hiddenCounts.truncated,
            ),
            projectionIndex = graphProjectionIndexForVisibleGraph(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
            ),
            presentation = GraphViewPresentation(
                target = GraphPresentationTarget(
                    nodeId = anchorNodeId,
                    title = targetNode?.title.orEmpty(),
                    subtitle = "当前方法",
                    location = targetNode?.location,
                ),
                lanes = factPresentationLanes(),
                hiddenBuckets = hiddenBucketProjector.project(
                    visibleGraph = visibleGraph,
                    fullGraph = fullGraph,
                    bucketForNode = { node -> node.metadata["presentation.laneId"] ?: factBucketForNode(node) },
                    labelForBucket = ::factBucketLabel,
                ),
                controls = GraphPresentationControls(
                    primaryScope = "主链",
                    availableScopes = listOf("主链", "全部"),
                ),
            ),
        )
    }

    /**
     * 调用窗口投影器裁剪事实图的可见规模。
     *
     * 关闭"填充孤立节点"选项，避免把无关联节点拉入事实图。
     */
    private fun projectGraph(
        graph: GraphDocument,
        anchorNodeId: String?,
        projectionPolicy: ProjectionPolicy,
    ): GraphDocument =
        windowProjector.project(
            graph = graph,
            policy = GraphWindowPolicy(
                maxVisibleNodes = projectionPolicy.maxVisibleNodes,
                maxVisibleEdges = projectionPolicy.maxVisibleEdges,
                enableOverflowSummary = projectionPolicy.enableOverflowSummary,
                fillDisconnectedNodes = false,
            ),
            anchorNodeId = anchorNodeId,
            overflowOwnerContext = "fact-graph",
        ).graph

    /**
     * 给事实图节点补齐展示元数据（角色/泳道/优先级/紧凑模式）。
     *
     * 通过对入边和出边做 BFS 计算上游/下游距离，从而推断每个节点相对锚点的方向。
     */
    private fun GraphDocument.withFactPresentationMetadata(anchorNodeId: String?): GraphDocument {
        val anchorId = anchorNodeId ?: nodes.firstOrNull()?.id ?: return this
        // 入边邻接表：节点 -> 直接上游节点列表。
        val incoming = edges.groupBy(GraphEdge::toNodeId).mapValues { (_, edges) -> edges.map(GraphEdge::fromNodeId) }
        // 出边邻接表：节点 -> 直接下游节点列表。
        val outgoing = edges.groupBy(GraphEdge::fromNodeId).mapValues { (_, edges) -> edges.map(GraphEdge::toNodeId) }
        // 锚点到各节点的上游距离。
        val upstreamDistances = bfs(anchorId, incoming)
        // 锚点到各节点的下游距离。
        val downstreamDistances = bfs(anchorId, outgoing)
        return copy(
            nodes = nodes.map { node ->
                val direction = factDirection(
                    node = node,
                    anchorId = anchorId,
                    upstreamDistances = upstreamDistances,
                    downstreamDistances = downstreamDistances,
                )
                node.copy(
                    metadata = node.metadata + mapOf(
                        "presentation.role" to direction.role,
                        "presentation.laneId" to direction.laneId,
                        "presentation.priority" to direction.priority.toString(),
                        "presentation.compact" to (direction.role != "ANCHOR").toString(),
                    ),
                )
            },
        )
    }

    /**
     * 给仍缺失展示信息的节点补齐默认展示元数据，把它们归到下游溢出桶。
     */
    private fun GraphDocument.withOverflowPresentationMetadata(
        laneId: String,
        role: String,
        priority: Int,
    ): GraphDocument =
        copy(
            nodes = nodes.map { node ->
                if (node.metadata["presentation.role"] != null) {
                    node
                } else {
                    node.copy(
                        metadata = node.metadata + mapOf(
                            "presentation.role" to role,
                            "presentation.laneId" to laneId,
                            "presentation.priority" to priority.toString(),
                            "presentation.compact" to "true",
                        ),
                    )
                }
            },
        )

    /**
     * 推断节点在事实图中的展示方向（角色、泳道、优先级）。
     *
     * - 锚点本身或当前方法相关节点 -> 当前；
     * - 仅在 upstream 中出现 -> 上游；
     * - 仅在 downstream 中出现 -> 下游；
     * - 既出现在 upstream 又出现在 downstream 时优先下游；
     * - 全部失败回退到当前。
     */
    private fun factDirection(
        node: GraphNode,
        anchorId: String,
        upstreamDistances: Map<String, Int>,
        downstreamDistances: Map<String, Int>,
    ): FactPresentationDirection {
        if (node.id == anchorId) {
            return FactPresentationDirection("current", "ANCHOR", 20)
        }
        if (node.type in currentFactNodeTypes && node.id in downstreamDistances) {
            return FactPresentationDirection("current", "ANCHOR", 20)
        }
        if (node.id in upstreamDistances && node.id !in downstreamDistances) {
            return FactPresentationDirection("upstream", "UPSTREAM", 10)
        }
        if (node.id in downstreamDistances) {
            return FactPresentationDirection("downstream", "DOWNSTREAM", 30)
        }
        if (node.id in upstreamDistances) {
            return FactPresentationDirection("upstream", "UPSTREAM", 10)
        }
        return FactPresentationDirection("current", "ANCHOR", 20)
    }

    /**
     * 从起始节点出发做广度优先搜索，返回各可达节点的距离。
     *
     * 用于计算事实图节点相对锚点的上下游层级。
     */
    private fun bfs(
        startId: String,
        adjacency: Map<String, List<String>>,
    ): Map<String, Int> {
        val distances = linkedMapOf(startId to 0)
        val queue = ArrayDeque<String>()
        queue.add(startId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val baseDistance = distances.getValue(current)
            adjacency[current].orEmpty().forEach { next ->
                if (next !in distances) {
                    distances[next] = baseDistance + 1
                    queue.add(next)
                }
            }
        }
        return distances
    }

    /** 事实图节点的展示方向内部表示：泳道 ID、角色、优先级。 */
    private data class FactPresentationDirection(
        val laneId: String,
        val role: String,
        val priority: Int,
    )

    private companion object {
        /** 视为"当前方法"相关流程的节点类型集合。 */
        private val currentFactNodeTypes = setOf(
            NodeType.FLOW_SCOPE,
            NodeType.FLOW_ACTION,
            NodeType.MERGE,
            NodeType.TERMINAL,
        )

        /**
         * 事实图固定展示的三条泳道：上游事实、当前方法、下游事实。
         */
        private fun factPresentationLanes(): List<GraphPresentationLane> =
            listOf(
                GraphPresentationLane("upstream", "上游事实", GraphPresentationLaneAxis.COLUMN, 10, "UPSTREAM"),
                GraphPresentationLane("current", "当前方法", GraphPresentationLaneAxis.COLUMN, 20, "ANCHOR"),
                GraphPresentationLane("downstream", "下游事实", GraphPresentationLaneAxis.COLUMN, 30, "DOWNSTREAM"),
            )

        /**
         * 根据节点类型推断其在隐藏桶中的归类：方法、资源或流程。
         */
        private fun factBucketForNode(node: GraphNode): String =
            when (node.type) {
                NodeType.METHOD -> "cross-method"
                NodeType.SQL,
                NodeType.HTTP_ENDPOINT,
                NodeType.FEIGN_CLIENT,
                NodeType.DUBBO_SERVICE,
                NodeType.MQ_TOPIC,
                NodeType.MQ_CONSUMER,
                NodeType.CONFIG_ITEM,
                NodeType.XML_RESOURCE,
                NodeType.DOC_PAGE,
                NodeType.RESOURCE,
                -> "resource"
                else -> "flow"
            }

        /**
         * 把隐藏桶 ID 转换为中文展示标签。
         */
        private fun factBucketLabel(bucket: String): String =
            when (bucket) {
                "upstream" -> "上游事实"
                "current" -> "当前方法"
                "downstream" -> "下游事实"
                "cross-method" -> "跨方法"
                "resource" -> "资源"
                "flow" -> "流程"
                else -> bucket
            }
    }
}
