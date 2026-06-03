package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

fun GraphBeautificationContext.effectiveEvidenceProfile(): GraphEvidenceProfile {
    if (evidenceProfile.anchorNodeType != null || evidenceProfile.allowedExplanationModes.isNotEmpty()) {
        return evidenceProfile
    }
    return buildGraphEvidenceProfile(
        graph = presentationContext.graph,
        fullGraph = presentationContext.fullGraph,
        anchorNodeId = presentationContext.anchorNodeId,
        selectedNodeIds = presentationContext.selectedNodeIds,
        sourceContext = sourceContext + stepSourceContext,
    )
}

fun GraphQaContext.effectiveEvidenceProfile(): GraphEvidenceProfile {
    if (evidenceProfile.anchorNodeType != null || evidenceProfile.allowedExplanationModes.isNotEmpty()) {
        return evidenceProfile
    }
    val graph = editableGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() } ?: factGraph
    return buildGraphEvidenceProfile(
        graph = graph,
        fullGraph = factGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() } ?: graph,
        anchorNodeId = selectedNodeIds.firstOrNull(),
        selectedNodeIds = selectedNodeIds,
        sourceContext = sourceContext,
    )
}

fun buildGraphEvidenceProfile(
    graph: GraphDocument,
    fullGraph: GraphDocument = graph,
    anchorNodeId: String? = null,
    selectedNodeIds: List<String> = emptyList(),
    sourceContext: List<SourceSnippetContext> = emptyList(),
): GraphEvidenceProfile {
    val anchor = resolveEvidenceAnchor(graph, fullGraph, anchorNodeId, selectedNodeIds)
    val relationGraph = fullGraph.takeIf { it.edges.isNotEmpty() } ?: graph
    val anchorId = anchor?.id ?: anchorNodeId
    val incomingEdges = relationGraph.edges.filter { edge -> anchorId != null && edge.toNodeId == anchorId }
    val outgoingEdges = relationGraph.edges.filter { edge -> anchorId != null && edge.fromNodeId == anchorId }
    val relationKinds = (incomingEdges + outgoingEdges)
        .map { edge -> edge.metadata["jvm.relation.kind"] ?: edge.type.name }
        .distinct()
        .sorted()
    val hasMethodCallEvidence = (incomingEdges + outgoingEdges).any { edge ->
        edge.type == EdgeType.CALL || edge.metadata["jvm.relation.kind"] == "CALLS"
    }
    val hasSourceEvidence = sourceContext.any { snippet ->
        snippet.nodeId == anchorId || selectedNodeIds.contains(snippet.nodeId)
    }
    val hasPackageMemberEvidence = anchor?.metadata?.get("indexed.memberClassCount")?.toIntOrNull()?.let { it > 0 } == true ||
        anchor?.metadata?.get("architecture.sourceSample.count")?.toIntOrNull()?.let { it > 0 } == true
    val modes = allowedModesFor(anchor, hasMethodCallEvidence, relationKinds)
    val forbiddenClaims = forbiddenClaimsFor(anchor, hasMethodCallEvidence)
    val evidenceGaps = evidenceGapsFor(anchor, hasMethodCallEvidence, incomingEdges.size, outgoingEdges.size)
    return GraphEvidenceProfile(
        anchorNodeId = anchorId,
        anchorNodeType = anchor?.type,
        anchorArchitectureKind = anchor?.metadata?.get("architecture.node.kind"),
        availableRelationKinds = relationKinds,
        incomingRelationCount = incomingEdges.size,
        outgoingRelationCount = outgoingEdges.size,
        hasMethodCallEvidence = hasMethodCallEvidence,
        hasSourceEvidence = hasSourceEvidence,
        hasPackageMemberEvidence = hasPackageMemberEvidence,
        allowedExplanationModes = modes,
        forbiddenClaims = forbiddenClaims,
        evidenceGaps = evidenceGaps,
        recommendedDrilldowns = recommendedDrilldowns(graph, fullGraph, anchor),
    )
}

private fun resolveEvidenceAnchor(
    graph: GraphDocument,
    fullGraph: GraphDocument,
    anchorNodeId: String?,
    selectedNodeIds: List<String>,
): GraphNode? {
    val candidates = listOfNotNull(anchorNodeId) + selectedNodeIds
    return candidates.firstNotNullOfOrNull { id ->
        graph.nodes.firstOrNull { node -> node.id == id } ?: fullGraph.nodes.firstOrNull { node -> node.id == id }
    } ?: graph.nodes.firstOrNull() ?: fullGraph.nodes.firstOrNull()
}

private fun allowedModesFor(
    anchor: GraphNode?,
    hasMethodCallEvidence: Boolean,
    relationKinds: List<String>,
): List<GraphExplanationMode> =
    when (anchor?.type) {
        NodeType.METHOD,
        NodeType.FLOW_ACTION,
        NodeType.FLOW_SCOPE,
        NodeType.TERMINAL,
        -> buildList {
            if (hasMethodCallEvidence || anchor.type == NodeType.METHOD || anchor.type == NodeType.FLOW_ACTION) {
                add(GraphExplanationMode.METHOD_CHAIN)
            }
            add(GraphExplanationMode.DRILLDOWN_SUGGESTION)
        }
        NodeType.PACKAGE -> listOf(GraphExplanationMode.PACKAGE_OVERVIEW, GraphExplanationMode.DRILLDOWN_SUGGESTION) + relationSummaryMode(relationKinds)
        NodeType.COMPONENT,
        NodeType.SERVICE,
        NodeType.LAYER,
        -> listOf(GraphExplanationMode.COMPONENT_OVERVIEW, GraphExplanationMode.DRILLDOWN_SUGGESTION) + relationSummaryMode(relationKinds)
        NodeType.RESOURCE,
        NodeType.SQL,
        NodeType.CONFIG_ITEM,
        NodeType.HTTP_ENDPOINT,
        -> listOf(GraphExplanationMode.RESOURCE_BINDING, GraphExplanationMode.DRILLDOWN_SUGGESTION) + relationSummaryMode(relationKinds)
        else -> listOf(GraphExplanationMode.STRUCTURE_OVERVIEW, GraphExplanationMode.DRILLDOWN_SUGGESTION) + relationSummaryMode(relationKinds)
    }.distinct()

private fun relationSummaryMode(relationKinds: List<String>): List<GraphExplanationMode> =
    if (relationKinds.isEmpty()) emptyList() else listOf(GraphExplanationMode.RELATION_SUMMARY)

private fun forbiddenClaimsFor(anchor: GraphNode?, hasMethodCallEvidence: Boolean): List<String> =
    buildList {
        if (anchor?.type !in methodLikeNodeTypes) {
            add("不要把当前锚点称为方法或当前方法")
            add("不要输出“定位被调方法”")
            add("不要生成方法调用链或业务步骤链")
        }
        if (!hasMethodCallEvidence) {
            add("没有 CALL 边或 CALLS 关系时不要断言被调方法")
        }
    }

private fun evidenceGapsFor(
    anchor: GraphNode?,
    hasMethodCallEvidence: Boolean,
    incomingRelationCount: Int,
    outgoingRelationCount: Int,
): List<String> =
    buildList {
        if (anchor?.type !in methodLikeNodeTypes && !hasMethodCallEvidence) {
            add("缺少方法级调用边")
        }
        if (incomingRelationCount == 0) {
            add("缺少上游关系证据")
        }
        if (outgoingRelationCount == 0) {
            add("缺少下游关系证据")
        }
    }

private fun recommendedDrilldowns(
    graph: GraphDocument,
    fullGraph: GraphDocument,
    anchor: GraphNode?,
): List<String> {
    val nodes = (graph.nodes + fullGraph.nodes).distinctBy(GraphNode::id)
    val anchorId = anchor?.id ?: return emptyList()
    return nodes
        .filter { node -> node.id != anchorId && node.type in methodLikeNodeTypes + NodeType.CLASS + NodeType.INTERFACE }
        .take(8)
        .map(GraphNode::id)
}

private val methodLikeNodeTypes = setOf(
    NodeType.METHOD,
    NodeType.FLOW_ACTION,
    NodeType.FLOW_SCOPE,
    NodeType.TERMINAL,
)
