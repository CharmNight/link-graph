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

private fun FlowchartViewDocument.withProjectionIndex(index: GraphProjectionIndex): FlowchartViewDocument = copy(
    projectionIndex = index,
)

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

internal fun resolveVisibleGraphForScene(snapshot: GraphEditorStateSnapshot): GraphDocument {
    return if (snapshot.currentSceneId == GraphSceneId.DIFF) {
        snapshot.diffGraph ?: GraphDocument()
    } else {
        resolveVisibleGraphForDisplayMode(snapshot, snapshot.analysisDisplayMode)
    }
}

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

internal data class GraphEditorViewDocuments(
    val factGraphView: FactGraphViewDocument,
    val flowchartView: FlowchartViewDocument,
    val resourceRelationView: ResourceRelationViewDocument,
    val architectureGraphView: ArchitectureGraphResult = ArchitectureGraphResult(),
    val classDiagramView: ClassDiagramResult = ClassDiagramResult(),
)

private data class FactGraphViewDocumentSummary(
    val visibleGraph: GraphDocument,
    val fullGraph: GraphDocument,
    val anchorNodeId: String?,
) {
    fun toSummary() = FactGraphSummary(
        anchorTitle = fullGraph.nodes.firstOrNull { it.id == anchorNodeId }?.title
            ?: visibleGraph.nodes.firstOrNull { it.id == anchorNodeId }?.title,
        visibleNodeCount = visibleGraph.nodes.size,
        fullNodeCount = fullGraph.nodes.size,
    )
}

private data class ResourceRelationViewSummary(
    val visibleGraph: GraphDocument,
) {
    fun toSummary() = ResourceRelationSummary(
        visibleNodeCount = visibleGraph.nodes.size,
        laneCounts = visibleGraph.nodes
            .groupingBy { it.metadata["resource.lane"] ?: "CODE" }
            .eachCount()
            .toSortedMap(),
    )
}
