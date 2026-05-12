package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.ui.view.FactGraphSummary
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.GraphEditCommandKind
import com.charmnight.linkgraph.ui.view.GraphProjectionEdgeMapping
import com.charmnight.linkgraph.ui.view.GraphProjectionIndex
import com.charmnight.linkgraph.ui.view.GraphProjectionMappingKind
import com.charmnight.linkgraph.ui.view.GraphProjectionNodeMapping
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
        projectionIndex = exactProjectionIndex(workspaceGraph),
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
    val flowchartProjectionIndex = buildProjectionIndex(
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
        projectionIndex = exactProjectionIndex(workspaceGraph),
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
        val x = node.metadata["ui.x"]?.toDoubleOrNull() ?: return@mapNotNull null
        val y = node.metadata["ui.y"]?.toDoubleOrNull() ?: return@mapNotNull null
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

private fun exactProjectionIndex(graph: GraphDocument): GraphProjectionIndex {
    return GraphProjectionIndex(
        nodeMappings = graph.nodes.associate { node ->
            node.id to GraphProjectionNodeMapping(
                projectedNodeId = node.id,
                mappingKind = nodeMappingKind(node, listOf(node.id)),
                canonicalNodeIds = listOf(node.id),
                editableCommandKinds = editableNodeCommands(node),
            )
        },
        edgeMappings = graph.edges.associate { edge ->
            edge.id to GraphProjectionEdgeMapping(
                projectedEdgeId = edge.id,
                mappingKind = GraphProjectionMappingKind.EXACT,
                canonicalEdgeIds = listOf(edge.id),
                editableCommandKinds = editableEdgeCommands(edge),
            )
        },
    )
}

private fun buildProjectionIndex(
    visibleGraph: GraphDocument? = null,
    itVisibleGraph: GraphDocument? = null,
    fullGraph: GraphDocument,
): GraphProjectionIndex {
    val effectiveVisibleGraph = visibleGraph ?: itVisibleGraph ?: fullGraph
    val fullEdgeIds = fullGraph.edges.mapTo(linkedSetOf()) { it.id }
    val overflowNodeIds = effectiveVisibleGraph.nodes
        .filter(::isOverflowNode)
        .mapTo(linkedSetOf()) { it.id }
    return GraphProjectionIndex(
        nodeMappings = effectiveVisibleGraph.nodes.associate { node ->
            val aliasIds = projectedAliasNodeIds(node)
            val canonicalIds = listOf(node.id) + aliasIds
            val mappingKind = nodeMappingKind(node, canonicalIds)
            node.id to GraphProjectionNodeMapping(
                projectedNodeId = node.id,
                mappingKind = mappingKind,
                canonicalNodeIds = if (mappingKind == GraphProjectionMappingKind.OVERFLOW_READONLY) emptyList() else canonicalIds,
                editableCommandKinds = if (mappingKind == GraphProjectionMappingKind.EXACT) {
                    editableNodeCommands(node)
                } else {
                    emptySet()
                },
            )
        },
        edgeMappings = effectiveVisibleGraph.edges.associate { edge ->
            val mappingKind = edgeMappingKind(edge, fullEdgeIds, overflowNodeIds)
            edge.id to GraphProjectionEdgeMapping(
                projectedEdgeId = edge.id,
                mappingKind = mappingKind,
                canonicalEdgeIds = if (mappingKind == GraphProjectionMappingKind.EXACT) listOf(edge.id) else emptyList(),
                editableCommandKinds = if (mappingKind == GraphProjectionMappingKind.EXACT) {
                    editableEdgeCommands(edge)
                } else {
                    emptySet()
                },
            )
        },
    )
}

private fun projectedAliasNodeIds(node: GraphNode): List<String> {
    return node.metadata["flowchart.projectedFromNodeIds"]
        ?.split(',')
        ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
        .orEmpty()
}

private fun nodeMappingKind(
    node: GraphNode,
    canonicalIds: List<String>,
): GraphProjectionMappingKind {
    return when {
        isOverflowNode(node) -> GraphProjectionMappingKind.OVERFLOW_READONLY
        canonicalIds.distinct().size > 1 -> GraphProjectionMappingKind.MERGED_ALIAS
        else -> GraphProjectionMappingKind.EXACT
    }
}

private fun edgeMappingKind(
    edge: GraphEdge,
    fullEdgeIds: Set<String>,
    overflowNodeIds: Set<String>,
): GraphProjectionMappingKind {
    return when {
        edge.fromNodeId in overflowNodeIds || edge.toNodeId in overflowNodeIds -> GraphProjectionMappingKind.OVERFLOW_READONLY
        edge.metadata["flow.synthetic"] == "true" || edge.metadata["flowchart.synthetic"] != null -> {
            GraphProjectionMappingKind.SYNTHETIC_READONLY
        }
        edge.id in fullEdgeIds -> GraphProjectionMappingKind.EXACT
        else -> GraphProjectionMappingKind.PATH_ALIAS
    }
}

private fun isOverflowNode(node: GraphNode): Boolean = node.metadata.keys.any { it.startsWith("linkGraph.overflow.") }

private fun editableNodeCommands(node: GraphNode): Set<GraphEditCommandKind> {
    if (isOverflowNode(node)) {
        return emptySet()
    }
    return setOf(
        GraphEditCommandKind.UPDATE_NODE,
        GraphEditCommandKind.DELETE_NODE,
        GraphEditCommandKind.DELETE_NODE_SUBTREE,
        GraphEditCommandKind.CONNECT_NODES,
    )
}

private fun editableEdgeCommands(edge: GraphEdge): Set<GraphEditCommandKind> {
    if (edge.metadata["flow.synthetic"] == "true" || edge.metadata["flowchart.synthetic"] != null) {
        return emptySet()
    }
    return setOf(
        GraphEditCommandKind.DELETE_EDGE,
        GraphEditCommandKind.INSERT_NODE_INTO_EDGE,
    )
}
