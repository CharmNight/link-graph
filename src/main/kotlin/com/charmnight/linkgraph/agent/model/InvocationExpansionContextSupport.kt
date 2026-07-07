package com.charmnight.linkgraph.agent.model

import com.charmnight.linkgraph.application.usecase.InvocationExpansionRegistryBuilder
import com.charmnight.linkgraph.application.usecase.InvocationExpansionRegistryEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

/** Result of applying invocation-expansion scene state to an LLM graph payload. */
internal data class InvocationExpansionScopedGraph(
    val graph: GraphDocument,
    val context: InvocationExpansionContext,
)

/** Builds the ACTIVE_CHAIN graph and explicit summary payload for flowchart invocation expansions. */
internal fun buildInvocationExpansionActiveChainScope(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
    sceneState: InvocationExpansionSceneState,
    anchorNodeId: String? = null,
): InvocationExpansionScopedGraph? {
    if (visibleGraph.nodes.isEmpty() || fullGraph.nodes.isEmpty()) {
        return null
    }
    val entries = InvocationExpansionRegistryBuilder.build(fullGraph)
    if (entries.isEmpty()) {
        return null
    }
    val entriesById = entries.associateBy(InvocationExpansionRegistryEntry::expansionId)
    val visibleCanonicalNodeIds = visibleGraph.nodes
        .flatMapTo(linkedSetOf()) { node -> listOf(node.id) + projectedFromNodeIds(node) }
    val rootExpansionIds = entries
        .filter { entry -> entry.parentExpansionId == null && entry.sourceInvocationNodeId in visibleCanonicalNodeIds }
        .mapTo(linkedSetOf()) { entry -> entry.expansionId }
    val reachableExpansionIds = collectReachableExpansionIds(
        rootExpansionIds = rootExpansionIds,
        entriesById = entriesById,
    )
    if (reachableExpansionIds.isEmpty()) {
        return null
    }
    val activePath = resolveActiveExpansionPath(
        entries = entries,
        entriesById = entriesById,
        reachableExpansionIds = reachableExpansionIds,
        rootExpansionIds = rootExpansionIds,
        sceneState = sceneState,
    )
    val fullExpansionIds = activePath
        .filter { expansionId -> expansionId in reachableExpansionIds }
        .filterNot { expansionId -> expansionId in sceneState.collapsedExpansionIds }
        .distinct()
    val summaryExpansionIds = entries
        .asSequence()
        .map(InvocationExpansionRegistryEntry::expansionId)
        .filter { expansionId -> expansionId in reachableExpansionIds }
        .filterNot { expansionId -> expansionId in fullExpansionIds }
        .toList()

    val visibleNodeIds = visibleGraph.nodes.mapTo(linkedSetOf()) { node -> node.id }
    val fullNodeIds = fullExpansionIds
        .flatMapTo(linkedSetOf()) { expansionId ->
            val entry = entriesById.getValue(expansionId)
            entry.ownedNodeIds + entry.borrowedNodeIds
        }
    val presentationNodeIds = linkedSetOf<String>().apply {
        addAll(visibleNodeIds)
        addAll(fullNodeIds)
    }
    val visibleEdgeIds = visibleGraph.edges.mapTo(linkedSetOf()) { edge -> edge.id }
    val fullEvidenceEdgeIds = fullExpansionIds
        .flatMapTo(linkedSetOf()) { expansionId ->
            val entry = entriesById.getValue(expansionId)
            entry.ownedEdgeIds + entry.callEdgeIds + entry.internalEdgeIds
        }
    val fullNodeSet = fullGraph.nodes.associateBy(GraphNode::id)
    val extraNodes = fullGraph.nodes.filter { node -> node.id in fullNodeIds && node.id !in visibleNodeIds }
    val extraEdges = fullGraph.edges.filter { edge ->
        edge.id !in visibleEdgeIds &&
            edge.fromNodeId in presentationNodeIds &&
            edge.toNodeId in presentationNodeIds &&
            (
                edge.id in fullEvidenceEdgeIds ||
                    edge.fromNodeId in fullNodeIds ||
                    edge.toNodeId in fullNodeIds
                )
    }
    val graph = visibleGraph.copy(
        nodes = visibleGraph.nodes + extraNodes,
        edges = (visibleGraph.edges + extraEdges).distinctBy(GraphEdge::id),
    )
    return InvocationExpansionScopedGraph(
        graph = graph,
        context = InvocationExpansionContext(
            mode = sceneState.contextMode,
            activeExpansionPath = activePath,
            fullExpansionIds = fullExpansionIds,
            summaryExpansionIds = summaryExpansionIds,
            summaries = summaryExpansionIds.mapNotNull { expansionId ->
                entriesById[expansionId]?.toSummary(fullNodeSet)
            },
        ),
    )
}

internal fun InvocationExpansionSceneState.hasExplicitInvocationExpansionScope(): Boolean =
    activeExpansionId?.isNotBlank() == true ||
        activeExpansionPath.isNotEmpty() ||
        collapsedExpansionIds.isNotEmpty() ||
        activeSiblingByParentContext.isNotEmpty()

private fun collectReachableExpansionIds(
    rootExpansionIds: Set<String>,
    entriesById: Map<String, InvocationExpansionRegistryEntry>,
): Set<String> {
    val reachableExpansionIds = linkedSetOf<String>()
    val queue = ArrayDeque(rootExpansionIds)
    while (queue.isNotEmpty()) {
        val expansionId = queue.removeFirst()
        if (!reachableExpansionIds.add(expansionId)) {
            continue
        }
        entriesById[expansionId]?.childExpansionIds.orEmpty().forEach(queue::addLast)
    }
    return reachableExpansionIds
}

private fun resolveActiveExpansionPath(
    entries: List<InvocationExpansionRegistryEntry>,
    entriesById: Map<String, InvocationExpansionRegistryEntry>,
    reachableExpansionIds: Set<String>,
    rootExpansionIds: Set<String>,
    sceneState: InvocationExpansionSceneState,
): List<String> {
    val requestedPath = sceneState.activeExpansionPath
        .filter { expansionId -> expansionId in entriesById && expansionId in reachableExpansionIds }
        .distinct()
    if (requestedPath.isNotEmpty()) {
        return requestedPath
            .asSequence()
            .flatMap { expansionId -> activePathFor(expansionId, entriesById).asSequence() }
            .filter { expansionId -> expansionId in reachableExpansionIds }
            .distinct()
            .toList()
    }
    val activeExpansionId = sceneState.activeExpansionId
        ?.takeIf { expansionId -> expansionId in entriesById && expansionId in reachableExpansionIds }
    if (activeExpansionId != null) {
        return activePathFor(activeExpansionId, entriesById)
            .filter { expansionId -> expansionId in reachableExpansionIds }
    }
    val activeSiblingId = sceneState.activeSiblingByParentContext.values
        .firstOrNull { expansionId -> expansionId in entriesById && expansionId in reachableExpansionIds }
    if (activeSiblingId != null) {
        return activePathFor(activeSiblingId, entriesById)
            .filter { expansionId -> expansionId in reachableExpansionIds }
    }
    val firstRootExpansionId = entries
        .firstOrNull { entry -> entry.expansionId in rootExpansionIds }
        ?.expansionId
    return firstRootExpansionId?.let { expansionId -> activePathFor(expansionId, entriesById) }.orEmpty()
}

private fun activePathFor(
    expansionId: String,
    entriesById: Map<String, InvocationExpansionRegistryEntry>,
): List<String> {
    val path = ArrayDeque<String>()
    val visited = linkedSetOf<String>()
    var currentId: String? = expansionId
    while (currentId != null && visited.add(currentId)) {
        val currentEntry = entriesById[currentId] ?: break
        path.addFirst(currentId)
        currentId = currentEntry.parentExpansionId
    }
    return path.toList()
}

private fun InvocationExpansionRegistryEntry.toSummary(
    nodesById: Map<String, GraphNode>,
): InvocationExpansionSummary {
    val ownedNodes = ownedNodeIds.mapNotNull(nodesById::get)
    val rootNode = rootNodeId?.let(nodesById::get)
    return InvocationExpansionSummary(
        expansionId = expansionId,
        sourceInvocationNodeId = sourceInvocationNodeId,
        rootNodeId = rootNodeId,
        targetSignature = targetSignature,
        title = rootNode?.title ?: targetSignature,
        ownedNodeCount = ownedNodeIds.size,
        branchCount = ownedNodes.count { node -> node.metadata["flowchart.kind"] == "DECISION" },
        returnCount = ownedNodes.count { node -> node.type == NodeType.TERMINAL || node.metadata["flow.kind"] == "RETURN" },
        childExpansionCount = childExpansionIds.size,
        hasBorrowedRoot = rootNodeId != null && rootNodeId in borrowedNodeIds,
    )
}

private fun projectedFromNodeIds(node: GraphNode): List<String> =
    node.metadata[FLOWCHART_PROJECTED_FROM_NODE_IDS_KEY]
        ?.split(',')
        ?.mapNotNull { nodeId -> nodeId.trim().takeIf(String::isNotBlank) }
        .orEmpty()
private const val FLOWCHART_PROJECTED_FROM_NODE_IDS_KEY = "flowchart.projectedFromNodeIds"
