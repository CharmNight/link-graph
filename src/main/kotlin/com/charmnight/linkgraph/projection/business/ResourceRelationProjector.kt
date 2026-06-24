package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.semantic.outcome.ResourceRelationSummary
import com.charmnight.linkgraph.semantic.outcome.ResourceRelationViewDocument
import com.charmnight.linkgraph.semantic.outcome.graphProjectionIndexForVisibleGraph
import com.charmnight.linkgraph.semantic.outcome.graphProjectionIndexForVisibleGraph
import com.charmnight.linkgraph.projection.GraphWindowPolicy
import com.charmnight.linkgraph.projection.GraphWindowProjector
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.graph.GraphAssembler
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy

/**
 * 资源关系图投影器。
 *
 * 把语义分析结果投影为"资源关系视图"：以某个锚点为中心，
 * 展示代码与各类外部资源（SQL、HTTP 端点、消息、配置等）之间的绑定关系。
 * 当不存在资源或绑定关系时，会在摘要中携带降级原因，方便前端给出提示。
 *
 * @param graphAssembler 把语义结果组装为完整图的组装器
 * @param windowProjector 窗口投影器，用于裁剪可见规模
 */
class ResourceRelationProjector(
    private val graphAssembler: GraphAssembler = GraphAssembler(),
    private val windowProjector: GraphWindowProjector = GraphWindowProjector(),
) : GraphProjector {
    /**
     * 把语义分析结果投影为资源关系视图文档。
     *
     * @param analysisResult 语义分析结果
     * @param projectionPolicy 投影策略（最大可见规模等）
     * @return 资源关系视图文档
     */
    fun project(
        analysisResult: SemanticAnalysisResult,
        projectionPolicy: ProjectionPolicy = ProjectionPolicy(),
    ): ResourceRelationViewDocument {
        val fullGraph = graphAssembler.assemble(analysisResult, AnalysisDisplayMode.RESOURCE_RELATION_VIEW)
        // 优先使用语义结果的锚点，缺失时回退到第一个节点。
        val anchorNodeId = analysisResult.anchors.firstOrNull()?.targetUnitId
            ?: fullGraph.nodes.firstOrNull()?.id
        val visibleGraph = projectGraph(fullGraph, anchorNodeId, projectionPolicy)
        return ResourceRelationViewDocument(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = anchorNodeId,
            summary = ResourceRelationSummary(
                visibleNodeCount = visibleGraph.nodes.size,
                relationCount = visibleGraph.edges.size,
                resourceCount = fullGraph.nodes.count { node ->
                    node.metadata["resource.lane"] != null || node.type.name.contains("RESOURCE") || node.type.name in setOf("SQL", "HTTP_ENDPOINT", "MQ_TOPIC", "CONFIG_ITEM")
                },
                fallbackReason = resourceFallbackReason(fullGraph),
                laneCounts = visibleGraph.nodes
                    .groupingBy { it.metadata?.get("resource.lane") ?: "CODE" }
                    .eachCount()
                    .toSortedMap(),
            ),
            projectionIndex = graphProjectionIndexForVisibleGraph(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
            ),
        )
    }

    /**
     * 调用窗口投影器裁剪资源关系图的可见规模。
     *
     * 关闭"填充孤立节点"选项，避免把无关联节点拉入视图。
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
            overflowOwnerContext = "resource-relation",
        ).graph

    /**
     * 推断资源关系视图的降级原因。
     *
     * - 没有边且没有任何资源节点 -> 完全缺失资源单元；
     * - 没有边但存在资源节点 -> 资源未被绑定到代码；
     * - 否则不需要降级提示。
     */
    private fun resourceFallbackReason(graph: GraphDocument): String {
        if (graph.edges.isNotEmpty()) {
            return "NONE"
        }
        val resourceCount = graph.nodes.count { node ->
            node.metadata["resource.lane"] != null || node.type.name.contains("RESOURCE") || node.type.name in setOf("SQL", "HTTP_ENDPOINT", "MQ_TOPIC", "CONFIG_ITEM")
        }
        return if (resourceCount == 0) "NO_RESOURCE_UNITS" else "NO_BINDING_RELATIONS"
    }
}
