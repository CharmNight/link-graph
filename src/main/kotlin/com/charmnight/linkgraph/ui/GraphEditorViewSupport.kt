package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.view.FactGraphSummary
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationSummary
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument
import com.charmnight.linkgraph.ui.view.deriveFlowchartSummary
import com.charmnight.linkgraph.ui.view.projectReadableFlowchartView
import com.charmnight.linkgraph.ui.view.resolveProjectedFlowchartNodeId

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
    visibleGraph: GraphDocument?,
    factFullGraph: GraphDocument?,
    selectedNodeId: String?,
    selectedMethodSignature: String?,
): GraphEditorViewDocuments {
    val effectiveVisibleGraph = visibleGraph ?: factFullGraph ?: GraphDocument()
    val effectiveFactFullGraph = factFullGraph ?: effectiveVisibleGraph
    val anchorNodeId = resolveSelectedNodeId(
        graph = effectiveVisibleGraph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    ) ?: effectiveVisibleGraph.nodes.firstOrNull()?.id
    return GraphEditorViewDocuments(
        factGraphView = FactGraphViewDocument(
            visibleGraph = effectiveVisibleGraph,
            fullGraph = effectiveFactFullGraph,
            anchorNodeId = anchorNodeId,
            summary = FactGraphViewDocumentSummary(
                visibleGraph = effectiveVisibleGraph,
                fullGraph = effectiveFactFullGraph,
                anchorNodeId = anchorNodeId,
            ).toSummary(),
        ),
        flowchartView = projectReadableFlowchartView(
            graph = effectiveVisibleGraph,
            anchorNodeId = anchorNodeId,
        ),
        resourceRelationView = ResourceRelationViewDocument(
            visibleGraph = effectiveVisibleGraph,
            fullGraph = effectiveVisibleGraph,
            anchorNodeId = anchorNodeId,
            summary = ResourceRelationViewSummary(
                visibleGraph = effectiveVisibleGraph,
            ).toSummary(),
        ),
    )
}

internal fun syncWorkingGraphViewDocuments(
    currentState: GraphEditorStateService.Snapshot,
    graph: GraphDocument,
    effectiveSignature: String?,
): GraphEditorViewDocuments {
    return buildViewDocuments(
        visibleGraph = graph,
        factFullGraph = currentState.referenceFactGraph ?: graph,
        selectedNodeId = currentState.selectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
}

internal fun syncDisplayModeGraphViewDocuments(
    currentState: GraphEditorStateService.Snapshot,
    graph: GraphDocument,
    effectiveSignature: String?,
    nextSelectedNodeId: String?,
    displayMode: AnalysisDisplayMode,
): GraphEditorViewDocuments {
    val fallback = buildViewDocuments(
        visibleGraph = graph,
        factFullGraph = currentState.referenceFactGraph ?: graph,
        selectedNodeId = nextSelectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    val nextAnchorNodeId = resolveSelectedNodeId(
        graph = graph,
        selectedNodeId = nextSelectedNodeId,
        selectedMethodSignature = effectiveSignature,
    ) ?: graph.nodes.firstOrNull()?.id
    return when (displayMode) {
        AnalysisDisplayMode.FLOWCHART -> {
            val flowchartView = projectReadableFlowchartView(
                graph = graph,
                anchorNodeId = resolveSelectedNodeId(
                    graph = graph,
                    selectedNodeId = nextSelectedNodeId,
                    selectedMethodSignature = effectiveSignature,
                ) ?: graph.nodes.firstOrNull()?.id,
            )
            GraphEditorViewDocuments(
                factGraphView = currentState.factGraphView ?: fallback.factGraphView,
                flowchartView = flowchartView,
                resourceRelationView = currentState.resourceRelationView ?: fallback.resourceRelationView,
            )
        }

        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> GraphEditorViewDocuments(
            factGraphView = currentState.factGraphView ?: fallback.factGraphView,
            flowchartView = currentState.flowchartView ?: fallback.flowchartView,
            resourceRelationView = (currentState.resourceRelationView ?: fallback.resourceRelationView).copy(
                visibleGraph = graph,
                fullGraph = graph,
                anchorNodeId = nextAnchorNodeId,
                summary = ResourceRelationViewSummary(visibleGraph = graph).toSummary(),
            ),
        )

        AnalysisDisplayMode.FACT_GRAPH -> fallback
    }
}

internal fun resolveVisibleGraphForDisplayMode(
    snapshot: GraphEditorStateService.Snapshot,
    displayMode: AnalysisDisplayMode,
): GraphDocument {
    return when (displayMode) {
        AnalysisDisplayMode.FACT_GRAPH -> snapshot.factGraphView?.visibleGraph
            ?: snapshot.visibleGraph
            ?: GraphDocument()
        AnalysisDisplayMode.FLOWCHART -> snapshot.flowchartView?.visibleGraph
            ?: snapshot.visibleGraph
            ?: GraphDocument()
        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> snapshot.resourceRelationView?.visibleGraph
            ?: snapshot.visibleGraph
            ?: GraphDocument()
    }
}

internal fun extractLayoutState(graph: GraphDocument?): GraphLayoutState {
    if (graph == null) {
        return GraphLayoutState()
    }
    val positions = graph.nodes.mapNotNull { node ->
        val x = node.metadata?.get("ui.x")?.toDoubleOrNull() ?: return@mapNotNull null
        val y = node.metadata?.get("ui.y")?.toDoubleOrNull() ?: return@mapNotNull null
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

private data class FlowchartViewSummary(
    val visibleGraph: GraphDocument,
    val fullGraph: GraphDocument,
) {
    fun toSummary() = deriveFlowchartSummary(visibleGraph = visibleGraph, fullGraph = fullGraph)
}

private data class ResourceRelationViewSummary(
    val visibleGraph: GraphDocument,
) {
    fun toSummary() = ResourceRelationSummary(
        visibleNodeCount = visibleGraph.nodes.size,
        laneCounts = visibleGraph.nodes
            .groupingBy { it.metadata?.get("resource.lane") ?: "CODE" }
            .eachCount()
            .toSortedMap(),
    )
}
