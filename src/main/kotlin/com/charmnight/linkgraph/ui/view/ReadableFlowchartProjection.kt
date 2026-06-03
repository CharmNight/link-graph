package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag

private const val FLOWCHART_ALIAS_IDS_KEY = "flowchart.projectedFromNodeIds"
private const val FLOWCHART_PROJECTION_MODE_KEY = "flowchart.projection.mode"
private const val FLOWCHART_PROJECTED_ACTION_TITLE_KEY = "flowchart.projectedActionTitle"
private const val FLOWCHART_READABLE_MODE = "READABLE"

internal fun projectReadableFlowchartView(
    graph: GraphDocument,
    anchorNodeId: String?,
): FlowchartViewDocument {
    val simplifiedVisibleGraph = projectReadableFlowchartGraph(graph)
    val resolvedAnchorNodeId = resolveProjectedFlowchartNodeId(simplifiedVisibleGraph, anchorNodeId)
        ?: simplifiedVisibleGraph.nodes.firstOrNull()?.id
    return FlowchartViewDocument(
        visibleGraph = simplifiedVisibleGraph,
        fullGraph = graph,
        anchorNodeId = resolvedAnchorNodeId,
        summary = deriveFlowchartSummary(
            visibleGraph = simplifiedVisibleGraph,
            fullGraph = graph,
        ),
        projectionIndex = graphProjectionIndexForVisibleGraph(
            visibleGraph = simplifiedVisibleGraph,
            fullGraph = graph,
        ),
    )
}

internal fun resolveProjectedFlowchartNodeId(
    graph: GraphDocument,
    requestedNodeId: String?,
): String? {
    val normalizedRequestedNodeId = requestedNodeId?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (graph.nodes.any { node -> node.id == normalizedRequestedNodeId }) {
        return normalizedRequestedNodeId
    }
    return graph.nodes.firstOrNull { node ->
        projectedAliasNodeIds(node).contains(normalizedRequestedNodeId)
    }?.id
}

private fun projectReadableFlowchartGraph(graph: GraphDocument): GraphDocument {
    if (!looksLikeFlowchartGraph(graph)) {
        return graph
    }

    val nodesById = graph.nodes.associateBy(GraphNode::id)
    val controlFlowEdges = graph.edges.filter { edge -> edge.type == EdgeType.CONTROL_FLOW || edge.type == EdgeType.CONTAINS_FLOW }
    val outgoingControlFlowEdges = controlFlowEdges.groupBy(GraphEdge::fromNodeId)
    val incomingControlFlowEdges = controlFlowEdges.groupBy(GraphEdge::toNodeId)
    val flowRelevantNodeIds = linkedSetOf<String>().apply {
        controlFlowEdges.forEach { edge ->
            add(edge.fromNodeId)
            add(edge.toNodeId)
        }
        graph.nodes
            .filter { node ->
                node.metadata["flowchart.kind"] != null ||
                    node.metadata["flow.kind"] != null ||
                    shouldRetainEditableNodeInReadableFlowchart(node)
            }
            .mapTo(this) { node -> node.id }
    }
    val redirectedNodeIds = linkedMapOf<String, String>()
    val aliasNodeIdsByRetainedNodeId = linkedMapOf<String, LinkedHashSet<String>>()

    graph.nodes
        .filter(::isDecisionNode)
        .forEach { decisionNode ->
            val removableGuardNodeIds = collectGuardProjectionNodeIds(
                decisionNodeId = decisionNode.id,
                nodesById = nodesById,
                outgoingControlFlowEdges = outgoingControlFlowEdges,
                incomingControlFlowEdges = incomingControlFlowEdges,
                redirectedNodeIds = redirectedNodeIds,
            )
            if (removableGuardNodeIds.isEmpty()) {
                return@forEach
            }
            removableGuardNodeIds.forEach { removableNodeId ->
                redirectedNodeIds[removableNodeId] = decisionNode.id
            }
            aliasNodeIdsByRetainedNodeId
                .getOrPut(decisionNode.id) { linkedSetOf() }
                .addAll(removableGuardNodeIds)
        }

    graph.nodes
        .filter(::isReadableActionNode)
        .forEach { actionNode ->
            if (actionNode.id in redirectedNodeIds) {
                return@forEach
            }
            val removableInvocationNodeIds = collectInvocationProjectionNodeIds(
                actionNodeId = actionNode.id,
                nodesById = nodesById,
                outgoingControlFlowEdges = outgoingControlFlowEdges,
                incomingControlFlowEdges = incomingControlFlowEdges,
                redirectedNodeIds = redirectedNodeIds,
            )
            if (removableInvocationNodeIds.isEmpty()) {
                return@forEach
            }
            removableInvocationNodeIds.forEach { removableNodeId ->
                redirectedNodeIds[removableNodeId] = actionNode.id
            }
            aliasNodeIdsByRetainedNodeId
                .getOrPut(actionNode.id) { linkedSetOf() }
                .addAll(removableInvocationNodeIds)
        }

    if (redirectedNodeIds.isEmpty()) {
        return graph.copy(
            nodes = graph.nodes
                .filter { node -> node.id in flowRelevantNodeIds }
                .map { node ->
                    if (shouldMarkReadableProjectionNode(node)) {
                        markReadableProjectionNode(node)
                    } else {
                        node
                    }
                },
            edges = controlFlowEdges.map { edge ->
                edge.copy(metadata = edge.metadata + mapOf(FLOWCHART_PROJECTION_MODE_KEY to FLOWCHART_READABLE_MODE))
            },
        )
    }

    val retainedNodes = graph.nodes.mapNotNull { node ->
        if (node.id in redirectedNodeIds) {
            return@mapNotNull null
        }
        if (node.id !in flowRelevantNodeIds) {
            return@mapNotNull null
        }
        val aliasNodeIds = aliasNodeIdsByRetainedNodeId[node.id].orEmpty()
        if (aliasNodeIds.isEmpty()) {
            if (shouldMarkReadableProjectionNode(node)) {
                markReadableProjectionNode(node)
            } else {
                node
            }
        } else {
            val aliasedNodes = aliasNodeIds.mapNotNull(nodesById::get)
            val projectedNode = mergeReadableProjectedNode(
                retainedNode = node,
                aliasedNodes = aliasedNodes,
            )
            markReadableProjectionNode(
                projectedNode.copy(
                    metadata = projectedNode.metadata + mapOf(
                        FLOWCHART_ALIAS_IDS_KEY to aliasNodeIds.joinToString(","),
                    ),
                ),
            )
        }
    }
    val retainedNodeIds = retainedNodes.mapTo(linkedSetOf()) { node -> node.id }
    val projectedEdges = linkedMapOf<String, GraphEdge>()
    controlFlowEdges.forEach { edge ->
        val projectedFromNodeId = resolveProjectedNodeId(edge.fromNodeId, redirectedNodeIds)
        val projectedToNodeId = resolveProjectedNodeId(edge.toNodeId, redirectedNodeIds)
        if (projectedFromNodeId == projectedToNodeId) {
            return@forEach
        }
        if (projectedFromNodeId !in retainedNodeIds || projectedToNodeId !in retainedNodeIds) {
            return@forEach
        }
        val projectedEdge = edge.copy(
            id = GraphEdge.stableId(
                type = edge.type,
                fromNodeId = projectedFromNodeId,
                toNodeId = projectedToNodeId,
                ownerContext = edge.label ?: edge.id,
            ),
            fromNodeId = projectedFromNodeId,
            toNodeId = projectedToNodeId,
            metadata = edge.metadata + mapOf(FLOWCHART_PROJECTION_MODE_KEY to FLOWCHART_READABLE_MODE),
        )
        val dedupeKey = listOf(
            projectedEdge.type.name,
            projectedEdge.fromNodeId,
            projectedEdge.toNodeId,
            projectedEdge.label.orEmpty(),
        ).joinToString("|")
        projectedEdges.putIfAbsent(dedupeKey, projectedEdge)
    }

    return GraphDocument(
        nodes = retainedNodes,
        edges = projectedEdges.values.toList(),
        patch = graph.patch,
    )
}

private fun looksLikeFlowchartGraph(graph: GraphDocument): Boolean {
    return graph.nodes.any { node ->
        node.metadata["flowchart.kind"] != null || node.metadata["flow.kind"] != null
    }
}

private fun isDecisionNode(node: GraphNode): Boolean = resolveFlowchartKind(node) == "DECISION"

private fun isReadableActionNode(node: GraphNode): Boolean {
    if (resolveFlowchartKind(node) != "PROCESS") {
        return false
    }
    val flowKind = node.metadata["flow.kind"]
    return flowKind != "INVOCATION" && flowKind != "CONDITION"
}

private fun projectedAliasNodeIds(node: GraphNode): Set<String> {
    return node.metadata[FLOWCHART_ALIAS_IDS_KEY]
        ?.split(',')
        ?.mapNotNull { aliasNodeId -> aliasNodeId.trim().takeIf(String::isNotBlank) }
        ?.toSet()
        .orEmpty()
}

private fun shouldRetainEditableNodeInReadableFlowchart(node: GraphNode): Boolean {
    return node.sourceTag == GraphSourceTag.DRAFT_MANUAL
}

private fun shouldMarkReadableProjectionNode(node: GraphNode): Boolean {
    return node.metadata["flowchart.kind"] != null || node.metadata["flow.kind"] != null
}

private fun markReadableProjectionNode(node: GraphNode): GraphNode {
    return node.copy(
        metadata = node.metadata + mapOf(FLOWCHART_PROJECTION_MODE_KEY to FLOWCHART_READABLE_MODE),
    )
}

private fun mergeReadableProjectedNode(
    retainedNode: GraphNode,
    aliasedNodes: List<GraphNode>,
): GraphNode {
    val projectedInvocation = aliasedNodes.firstOrNull { node ->
        node.metadata["flow.kind"] == "INVOCATION" && node.metadata["flowchart.kind"] == "SUBROUTINE"
    } ?: return retainedNode
    return retainedNode.copy(
        title = projectedInvocation.title,
        signature = projectedInvocation.signature ?: retainedNode.signature,
        doc = projectedInvocation.doc ?: retainedNode.doc,
        metadata = retainedNode.metadata + mapOf(
            FLOWCHART_PROJECTED_ACTION_TITLE_KEY to retainedNode.title,
        ),
    )
}

private fun collectGuardProjectionNodeIds(
    decisionNodeId: String,
    nodesById: Map<String, GraphNode>,
    outgoingControlFlowEdges: Map<String, List<GraphEdge>>,
    incomingControlFlowEdges: Map<String, List<GraphEdge>>,
    redirectedNodeIds: Map<String, String>,
): List<String> {
    val removableNodeIds = mutableListOf<String>()
    var cursorNodeId = decisionNodeId
    var sawConditionAction = false

    while (true) {
        val incomingEdges = incomingControlFlowEdges[cursorNodeId]
            .orEmpty()
            .filter { edge -> edge.label.isNullOrBlank() }
        if (incomingEdges.size != 1) {
            break
        }
        val previousNodeId = incomingEdges.single().fromNodeId
        if (previousNodeId in redirectedNodeIds) {
            break
        }
        val previousNode = nodesById[previousNodeId] ?: break
        val outgoingEdges = outgoingControlFlowEdges[previousNodeId]
            .orEmpty()
            .filter { edge -> edge.label.isNullOrBlank() }
        if (outgoingEdges.size != 1 || outgoingEdges.single().toNodeId != cursorNodeId) {
            break
        }
        when {
            previousNode.metadata["flow.kind"] == "INVOCATION" && previousNode.metadata["flowchart.kind"] == "SUBROUTINE" -> {
                removableNodeIds += previousNodeId
                cursorNodeId = previousNodeId
            }

            previousNode.metadata["flow.kind"] == "CONDITION" && previousNode.metadata["flowchart.kind"] == "PROCESS" -> {
                removableNodeIds += previousNodeId
                sawConditionAction = true
                break
            }

            else -> break
        }
    }

    return if (sawConditionAction) removableNodeIds.asReversed() else emptyList()
}

private fun collectInvocationProjectionNodeIds(
    actionNodeId: String,
    nodesById: Map<String, GraphNode>,
    outgoingControlFlowEdges: Map<String, List<GraphEdge>>,
    incomingControlFlowEdges: Map<String, List<GraphEdge>>,
    redirectedNodeIds: Map<String, String>,
): List<String> {
    val removableInvocationNodeIds = mutableListOf<String>()
    var cursorNodeId = actionNodeId

    while (true) {
        val outgoingEdges = outgoingControlFlowEdges[cursorNodeId]
            .orEmpty()
            .filter { edge -> edge.label.isNullOrBlank() }
        if (outgoingEdges.size != 1) {
            break
        }
        val nextNodeId = outgoingEdges.single().toNodeId
        if (nextNodeId in redirectedNodeIds) {
            break
        }
        val nextNode = nodesById[nextNodeId] ?: break
        if (nextNode.metadata["flow.kind"] != "INVOCATION" || nextNode.metadata["flowchart.kind"] != "SUBROUTINE") {
            break
        }
        val incomingEdges = incomingControlFlowEdges[nextNodeId]
            .orEmpty()
            .filter { edge -> edge.label.isNullOrBlank() }
        if (incomingEdges.size != 1 || incomingEdges.single().fromNodeId != cursorNodeId) {
            break
        }
        removableInvocationNodeIds += nextNodeId
        cursorNodeId = nextNodeId
    }

    return removableInvocationNodeIds
}

private fun resolveProjectedNodeId(
    nodeId: String,
    redirectedNodeIds: Map<String, String>,
): String {
    var currentNodeId = nodeId
    val visitedNodeIds = linkedSetOf<String>()
    while (true) {
        val redirectedNodeId = redirectedNodeIds[currentNodeId] ?: return currentNodeId
        if (!visitedNodeIds.add(redirectedNodeId)) {
            return redirectedNodeId
        }
        currentNodeId = redirectedNodeId
    }
}
