package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphMetadataKeys
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.semantic.outcome.FactGraphSummary
import com.charmnight.linkgraph.semantic.outcome.FactGraphViewDocument
import com.charmnight.linkgraph.semantic.outcome.FlowchartViewDocument
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.semantic.outcome.ResourceRelationSummary
import com.charmnight.linkgraph.semantic.outcome.ResourceRelationViewDocument
import com.charmnight.linkgraph.semantic.outcome.deriveFlowchartSummary
import com.charmnight.linkgraph.semantic.outcome.exactGraphProjectionIndex
import com.charmnight.linkgraph.semantic.outcome.graphProjectionIndexForVisibleGraph
import com.charmnight.linkgraph.semantic.outcome.projectReadableFlowchartView
import com.charmnight.linkgraph.semantic.outcome.resolveProjectedFlowchartNodeId

/**
 * 在给定图中确定当前选中的节点 ID。
 *
 * 优先按原始节点 ID 精确匹配；若未命中，再尝试通过投影索引回查、最后用方法签名兜底。
 * 用于在刷新视图时把外部传入的选中信息稳定地映射到图中的真实节点。
 */
internal fun resolveSelectedNodeId(
    graph: GraphDocument,
    selectedNodeId: String?,
    selectedMethodSignature: String?,
): String? {
    if (selectedNodeId != null && graph.nodes.any { it.id == selectedNodeId }) {
        return selectedNodeId
    }
    resolveProjectedFlowchartNodeId(graph, selectedNodeId)?.let { projectedNodeId ->
        return projectedNodeId
    }
    return findNodeIdBySignature(graph, selectedMethodSignature)
}

/**
 * 按方法签名在图中查找首个匹配节点 ID。
 *
 * 同时支持原始签名匹配与归一化后的签名匹配（去掉包名只保留类名），以兼容不同来源的签名格式。
 */
internal fun findNodeIdBySignature(
    graph: GraphDocument?,
    signature: String?,
): String? {
    if (graph == null || signature.isNullOrBlank()) {
        return null
    }
    val expected = comparableMethodSignature(signature)
    return graph.nodes.firstOrNull { node ->
        val nodeSignature = node.signature ?: return@firstOrNull false
        nodeSignature == signature || comparableMethodSignature(nodeSignature) == expected
    }?.id
}

/** 把方法签名中的全限定类名压缩为简单类名，便于在签名格式不完全一致时仍能匹配同一目标。 */
private fun comparableMethodSignature(signature: String): String {
    val argumentsStart = signature.indexOf('(')
    if (argumentsStart <= 0) {
        return signature
    }
    val ownerAndMethod = signature.substring(0, argumentsStart)
    val owner = ownerAndMethod.substringBeforeLast('.', missingDelimiterValue = ownerAndMethod)
    val methodName = ownerAndMethod.substringAfterLast('.')
    val simpleOwner = owner.substringAfterLast('.')
    return "$simpleOwner.$methodName${signature.substring(argumentsStart)}"
}

/**
 * 基于工作区图和选中信息构建图谱编辑器需要的多视图文档集合。
 *
 * 依次构造事实图、流程图、资源关系图视图，并为流程图额外计算投影索引；
 * 全程通过 [runtimeTrace] 上报各阶段耗时与图规模，方便性能定位。
 */
internal fun buildViewDocuments(
    workspaceGraph: GraphDocument,
    selectedNodeId: String?,
    selectedMethodSignature: String?,
    runtimeTrace: ((() -> String) -> Unit)? = null,
): GraphEditorViewDocuments {
    val totalStartedAt = System.nanoTime()
    val anchorNodeId = resolveSelectedNodeId(
        graph = workspaceGraph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    ) ?: workspaceGraph.nodes.firstOrNull()?.id
    val factStartedAt = System.nanoTime()
    val factGraphView = FactGraphViewDocument(
        visibleGraph = workspaceGraph,
        fullGraph = workspaceGraph,
        anchorNodeId = anchorNodeId,
        summary = FactGraphViewDocumentSummary(
            visibleGraph = workspaceGraph,
            fullGraph = workspaceGraph,
            anchorNodeId = anchorNodeId,
        ).toSummary(),
        projectionIndex = exactGraphProjectionIndex(workspaceGraph),
    )
    traceViewStage(
        runtimeTrace = runtimeTrace,
        stage = "state.buildViewDocuments.fact",
        startedAtNanos = factStartedAt,
    ) {
        listOf(
            "workspace=${LinkGraphRenderTrace.graphSummary(workspaceGraph)}",
            "visible=${LinkGraphRenderTrace.graphSummary(factGraphView.visibleGraph)}",
        )
    }
    val flowProjectStartedAt = System.nanoTime()
    val flowchartView = projectReadableFlowchartView(
        graph = workspaceGraph,
        anchorNodeId = anchorNodeId,
    )
    traceViewStage(
        runtimeTrace = runtimeTrace,
        stage = "state.buildViewDocuments.flowchart.projectReadable",
        startedAtNanos = flowProjectStartedAt,
    ) {
        listOf(
            "workspace=${LinkGraphRenderTrace.graphSummary(workspaceGraph)}",
            "visible=${LinkGraphRenderTrace.graphSummary(flowchartView.visibleGraph)}",
        )
    }
    val flowIndexStartedAt = System.nanoTime()
    val flowchartProjectionIndex = graphProjectionIndexForVisibleGraph(
        visibleGraph = flowchartView.visibleGraph,
        fullGraph = workspaceGraph,
    )
    val flowchartViewWithIndex = flowchartView.withProjectionIndex(flowchartProjectionIndex)
    traceViewStage(
        runtimeTrace = runtimeTrace,
        stage = "state.buildViewDocuments.flowchart.projectionIndex",
        startedAtNanos = flowIndexStartedAt,
    ) {
        listOf(
            "visible=${LinkGraphRenderTrace.graphSummary(flowchartView.visibleGraph)}",
            "full=${LinkGraphRenderTrace.graphSummary(workspaceGraph)}",
            "nodeMappings=${flowchartProjectionIndex.nodeMappings.size}",
            "edgeMappings=${flowchartProjectionIndex.edgeMappings.size}",
        )
    }
    val resourceStartedAt = System.nanoTime()
    val resourceRelationView = ResourceRelationViewDocument(
        visibleGraph = workspaceGraph,
        fullGraph = workspaceGraph,
        anchorNodeId = anchorNodeId,
        summary = ResourceRelationViewSummary(
            visibleGraph = workspaceGraph,
        ).toSummary(),
        projectionIndex = exactGraphProjectionIndex(workspaceGraph),
    )
    traceViewStage(
        runtimeTrace = runtimeTrace,
        stage = "state.buildViewDocuments.resource",
        startedAtNanos = resourceStartedAt,
    ) {
        listOf(
            "workspace=${LinkGraphRenderTrace.graphSummary(workspaceGraph)}",
            "visible=${LinkGraphRenderTrace.graphSummary(resourceRelationView.visibleGraph)}",
        )
    }
    val documents = GraphEditorViewDocuments(
        factGraphView = factGraphView,
        flowchartView = flowchartViewWithIndex,
        resourceRelationView = resourceRelationView,
    )
    traceViewStage(
        runtimeTrace = runtimeTrace,
        stage = "state.buildViewDocuments.total",
        startedAtNanos = totalStartedAt,
    ) {
        listOf(
            "workspace=${LinkGraphRenderTrace.graphSummary(workspaceGraph)}",
            "factVisible=${LinkGraphRenderTrace.graphSummary(documents.factGraphView.visibleGraph)}",
            "flowVisible=${LinkGraphRenderTrace.graphSummary(documents.flowchartView.visibleGraph)}",
            "resourceVisible=${LinkGraphRenderTrace.graphSummary(documents.resourceRelationView.visibleGraph)}",
        )
    }
    return documents
}

/** 复制一份流程图视图文档，并把投影索引替换为指定值。 */
private fun FlowchartViewDocument.withProjectionIndex(index: GraphProjectionIndex): FlowchartViewDocument = copy(
    projectionIndex = index,
)

/**
 * 上报一次视图构建阶段的执行追踪信息。
 *
 * 没有传入 [runtimeTrace] 时直接跳过；否则记录阶段名、起始时间戳以及各阶段自定义明细。
 */
private fun traceViewStage(
    runtimeTrace: ((() -> String) -> Unit)?,
    stage: String,
    startedAtNanos: Long,
    details: () -> List<String>,
) {
    val trace = runtimeTrace ?: return
    LinkGraphRenderTrace.stage(
        enabled = true,
        log = { message -> trace { message } },
        stage = stage,
        startedAtNanos = startedAtNanos,
        details = details,
    )
}

/**
 * 根据分析展示模式从快照中取出对应的可见图。
 *
 * 不同模式对应不同的图视图（事实图、流程图、资源关系图、架构图、类图、审查图），
 * 用于按当前模式渲染节点和边。
 */
internal fun resolveVisibleGraphForDisplayMode(
    snapshot: GraphEditorStateSnapshot,
    displayMode: AnalysisDisplayMode,
): GraphDocument {
    return when (displayMode) {
        AnalysisDisplayMode.FACT_GRAPH -> snapshot.factGraphView.visibleGraph
        AnalysisDisplayMode.FLOWCHART -> snapshot.flowchartView.visibleGraph
        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> snapshot.resourceRelationView.visibleGraph
        AnalysisDisplayMode.ARCHITECTURE_GRAPH -> snapshot.architectureGraphView.visibleGraph
        AnalysisDisplayMode.CLASS_DIAGRAM -> snapshot.classDiagramView.visibleGraph
        AnalysisDisplayMode.REVIEW_GRAPH -> snapshot.reviewGraphView.visibleGraph
    }
}

/**
 * 按当前场景取可见图：差异比对场景下优先返回 diff 图，其它场景按分析模式选择。
 *
 * 用于在多个场景共用同一份状态时，按场景上下文返回渲染所需的图。
 */
internal fun resolveVisibleGraphForScene(snapshot: GraphEditorStateSnapshot): GraphDocument {
    return if (snapshot.currentSceneId == GraphSceneId.DIFF) {
        snapshot.diffGraph ?: GraphDocument()
    } else {
        resolveVisibleGraphForDisplayMode(snapshot, snapshot.analysisDisplayMode)
    }
}

/**
 * 从图的节点元数据中读取 UI 保存的坐标，重建出布局状态。
 *
 * 缺失 X/Y 元数据的节点会被跳过，最终返回以节点 ID 为键的坐标映射。
 */
internal fun extractLayoutState(graph: GraphDocument?): GraphLayoutState {
    if (graph == null) {
        return GraphLayoutState()
    }
    val positions = graph.nodes.mapNotNull { node ->
        val x = node.metadata[GraphMetadataKeys.Ui.X]?.toDoubleOrNull() ?: return@mapNotNull null
        val y = node.metadata[GraphMetadataKeys.Ui.Y]?.toDoubleOrNull() ?: return@mapNotNull null
        node.id to GraphLayoutPosition(x = x, y = y)
    }.toMap()
    return GraphLayoutState(positions)
}

/**
 * 合并多个布局来源，得到最终生效的节点布局。
 *
 * 优先使用从图元数据中提取的坐标，其次使用 [preferred]，最后回退到 [fallback]；
 * 任一来源都没有该节点坐标时跳过，确保结果只包含确定位置的节点。
 */
internal fun mergeLayoutState(
    graph: GraphDocument,
    preferred: GraphLayoutState,
    fallback: GraphLayoutState,
): GraphLayoutState {
    val extracted = extractLayoutState(graph)
    val positions = graph.nodes.mapNotNull { node ->
        val position = extracted.positions[node.id]
            ?: preferred.positions[node.id]
            ?: fallback.positions[node.id]
            ?: return@mapNotNull null
        node.id to position
    }.toMap()
    return GraphLayoutState(positions)
}

/** 图谱编辑器各场景视图文档的集合，包含事实图、流程图、资源关系图等视图。 */
internal data class GraphEditorViewDocuments(
    val factGraphView: FactGraphViewDocument,
    val flowchartView: FlowchartViewDocument,
    val resourceRelationView: ResourceRelationViewDocument,
    val architectureGraphView: ArchitectureGraphResult = ArchitectureGraphResult(),
    val classDiagramView: ClassDiagramResult = ClassDiagramResult(),
)

/** 事实图视图的汇总计算中间结构，根据可见图、完整图与锚点节点生成对外展示的摘要。 */
private data class FactGraphViewDocumentSummary(
    val visibleGraph: GraphDocument,
    val fullGraph: GraphDocument,
    val anchorNodeId: String?,
) {
    /** 生成事实图摘要：包含锚点节点标题，以及可见/完整两份节点数。 */
    fun toSummary() = FactGraphSummary(
        anchorTitle = fullGraph.nodes.firstOrNull { it.id == anchorNodeId }?.title
            ?: visibleGraph.nodes.firstOrNull { it.id == anchorNodeId }?.title,
        visibleNodeCount = visibleGraph.nodes.size,
        fullNodeCount = fullGraph.nodes.size,
    )
}

/** 资源关系图视图的汇总计算中间结构，按泳道分类统计节点数。 */
private data class ResourceRelationViewSummary(
    val visibleGraph: GraphDocument,
) {
    /** 生成资源关系图摘要：可见节点总数与按泳道分组后的节点计数（按 key 排序）。 */
    fun toSummary() = ResourceRelationSummary(
        visibleNodeCount = visibleGraph.nodes.size,
        laneCounts = visibleGraph.nodes
            .groupingBy { it.metadata["resource.lane"] ?: "CODE" }
            .eachCount()
            .toSortedMap(),
    )
}
