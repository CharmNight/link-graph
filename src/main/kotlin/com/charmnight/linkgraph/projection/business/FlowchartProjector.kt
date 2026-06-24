package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.semantic.outcome.FlowchartViewDocument
import com.charmnight.linkgraph.semantic.outcome.deriveFlowchartSummary
import com.charmnight.linkgraph.semantic.outcome.graphProjectionIndexForVisibleGraph
import com.charmnight.linkgraph.semantic.outcome.projectReadableFlowchartView
import com.charmnight.linkgraph.semantic.outcome.resolveProjectedFlowchartNodeId
import com.charmnight.linkgraph.semantic.outcome.graphProjectionIndexForVisibleGraph
import com.charmnight.linkgraph.projection.GraphWindowPolicy
import com.charmnight.linkgraph.projection.GraphWindowProjector
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.graph.GraphAssembler
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy

/**
 * 流程图投影器。
 *
 * 把语义分析结果投影为可读的流程图视图：
 * 1) 用 [GraphAssembler] 把语义结果组装为完整流程图；
 * 2) 调用 [projectReadableFlowchartView] 做可读性裁剪（合并/折叠等）；
 * 3) 通过 [GraphWindowProjector] 做窗口级裁剪，限制可见规模；
 * 4) 把所有结果打包为 [FlowchartViewDocument] 给 UI。
 *
 * @param graphAssembler 把语义结果组装为图文档的组装器
 * @param windowProjector 窗口投影器，做最终规模裁剪
 */
class FlowchartProjector(
    private val graphAssembler: GraphAssembler = GraphAssembler(),
    private val windowProjector: GraphWindowProjector = GraphWindowProjector(),
) : GraphProjector {
    /**
     * 投影出最终的流程图视图文档。
     *
     * @param analysisResult 语义分析结果
     * @param projectionPolicy 投影策略（节点/边数量等）
     */
    fun project(
        analysisResult: SemanticAnalysisResult,
        projectionPolicy: ProjectionPolicy = ProjectionPolicy(),
    ): FlowchartViewDocument {
        // 第一步：组装完整流程图（未做任何裁剪）
        val fullGraph = graphAssembler.assemble(analysisResult, AnalysisDisplayMode.FLOWCHART)
        // 第二步：取锚点节点 ID，优先用语义结果的第一个锚点，缺失时退到首个节点
        val anchorNodeId = analysisResult.anchors.firstOrNull()?.targetUnitId
            ?: fullGraph.nodes.firstOrNull()?.id
        // 第三步：可读性裁剪——把复杂结构折叠为可读的小图
        val readableView = projectReadableFlowchartView(
            graph = fullGraph,
            anchorNodeId = anchorNodeId,
        )
        // 第四步：窗口裁剪——按策略限制最终可见节点/边数量
        val visibleGraph = projectGraph(
            graph = readableView.visibleGraph,
            anchorNodeId = readableView.anchorNodeId,
            projectionPolicy = projectionPolicy,
        )
        // 第五步：把锚点节点 ID 映射到投影后的 ID（投影过程可能合并节点）
        val resolvedAnchorNodeId = resolveProjectedFlowchartNodeId(visibleGraph, readableView.anchorNodeId)
            ?: visibleGraph.nodes.firstOrNull()?.id
        return FlowchartViewDocument(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = resolvedAnchorNodeId,
            summary = deriveFlowchartSummary(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
            ),
            projectionIndex = graphProjectionIndexForVisibleGraph(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
            ),
        )
    }

    /**
     * 调用窗口投影器对图做最终裁剪。
     * 流程图场景下关闭"填充无连接节点"，避免把孤立节点硬塞进图里破坏流程语义。
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
                // 流程图强依赖连续性，不填充孤岛节点
                fillDisconnectedNodes = false,
            ),
            anchorNodeId = anchorNodeId,
            overflowOwnerContext = "flowchart",
        ).graph
}
