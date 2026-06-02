package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.projection.GraphWindowPolicy
import com.charmnight.linkgraph.projection.GraphWindowProjector
import com.charmnight.linkgraph.projection.graphProjectionHiddenCounts
import com.charmnight.linkgraph.presentation.GraphHiddenBucketProjector
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

class FactGraphProjector(
    private val graphAssembler: GraphAssembler = GraphAssembler(),
    private val windowProjector: GraphWindowProjector = GraphWindowProjector(),
    private val hiddenBucketProjector: GraphHiddenBucketProjector = GraphHiddenBucketProjector(),
) {
    fun project(
        analysisResult: SemanticAnalysisResult,
        projectionPolicy: ProjectionPolicy = ProjectionPolicy(),
    ): FactGraphViewDocument {
        val assembledGraph = graphAssembler.assemble(analysisResult, AnalysisDisplayMode.FACT_GRAPH)
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

    private fun GraphDocument.withFactPresentationMetadata(anchorNodeId: String?): GraphDocument {
        val anchorId = anchorNodeId ?: nodes.firstOrNull()?.id ?: return this
        val incoming = edges.groupBy(GraphEdge::toNodeId).mapValues { (_, edges) -> edges.map(GraphEdge::fromNodeId) }
        val outgoing = edges.groupBy(GraphEdge::fromNodeId).mapValues { (_, edges) -> edges.map(GraphEdge::toNodeId) }
        val upstreamDistances = bfs(anchorId, incoming)
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

    private data class FactPresentationDirection(
        val laneId: String,
        val role: String,
        val priority: Int,
    )

    private companion object {
        private val currentFactNodeTypes = setOf(
            NodeType.FLOW_SCOPE,
            NodeType.FLOW_ACTION,
            NodeType.MERGE,
            NodeType.TERMINAL,
        )

        private fun factPresentationLanes(): List<GraphPresentationLane> =
            listOf(
                GraphPresentationLane("upstream", "上游事实", GraphPresentationLaneAxis.COLUMN, 10, "UPSTREAM"),
                GraphPresentationLane("current", "当前方法", GraphPresentationLaneAxis.COLUMN, 20, "ANCHOR"),
                GraphPresentationLane("downstream", "下游事实", GraphPresentationLaneAxis.COLUMN, 30, "DOWNSTREAM"),
            )

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
