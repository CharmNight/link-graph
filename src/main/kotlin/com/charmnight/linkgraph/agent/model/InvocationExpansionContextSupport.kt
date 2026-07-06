package com.charmnight.linkgraph.agent.model

import com.charmnight.linkgraph.model.EdgeType
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
    val entries = buildInvocationExpansionEntries(fullGraph)
    if (entries.isEmpty()) {
        return null
    }
    val entriesById = entries.associateBy(InvocationExpansionEntry::expansionId)
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
        .map(InvocationExpansionEntry::expansionId)
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

private data class InvocationExpansionEntry(
    val expansionId: String,
    val sourceInvocationNodeId: String?,
    val rootNodeId: String?,
    val targetSignature: String?,
    val createdAt: String?,
    val parentExpansionId: String?,
    val ownedNodeIds: Set<String>,
    val borrowedNodeIds: Set<String>,
    val ownedEdgeIds: Set<String>,
    val callEdgeIds: Set<String>,
    val internalEdgeIds: Set<String>,
    val childExpansionIds: List<String>,
)

private data class InvocationExpansionDraft(
    val expansionId: String,
    val taggedNodes: MutableList<GraphNode> = mutableListOf(),
    val taggedEdges: MutableList<GraphEdge> = mutableListOf(),
    val ownedNodeIds: LinkedHashSet<String> = linkedSetOf(),
    val ownedEdgeIds: LinkedHashSet<String> = linkedSetOf(),
)

private fun buildInvocationExpansionEntries(graph: GraphDocument): List<InvocationExpansionEntry> {
    val draftsById = linkedMapOf<String, InvocationExpansionDraft>()
    graph.nodes.forEach { node ->
        val expansionId = node.metadata.expansionId() ?: return@forEach
        draftsById.getOrPut(expansionId) { InvocationExpansionDraft(expansionId) }.also { draft ->
            draft.taggedNodes += node
            draft.ownedNodeIds += node.id
        }
    }
    graph.edges.forEach { edge ->
        val expansionId = edge.metadata.expansionId() ?: return@forEach
        draftsById.getOrPut(expansionId) { InvocationExpansionDraft(expansionId) }.also { draft ->
            draft.taggedEdges += edge
            draft.ownedEdgeIds += edge.id
        }
    }
    if (draftsById.isEmpty()) {
        return emptyList()
    }

    val nodesById = graph.nodes.associateBy(GraphNode::id)
    val edgesById = graph.edges.associateBy(GraphEdge::id)
    val ownerExpansionIdByNodeId = linkedMapOf<String, String>()
    draftsById.values.forEach { draft ->
        draft.ownedNodeIds.forEach { nodeId ->
            ownerExpansionIdByNodeId[nodeId] = draft.expansionId
            nodesById[nodeId]?.let { node ->
                projectedFromNodeIds(node).forEach { aliasNodeId ->
                    ownerExpansionIdByNodeId[aliasNodeId] = draft.expansionId
                }
            }
        }
    }

    val stubs = draftsById.values.map { draft ->
        val metadataCandidates = draft.taggedNodes.map(GraphNode::metadata) + draft.taggedEdges.map(GraphEdge::metadata)
        val sourceInvocationNodeId = metadataCandidates.firstValue(INVOCATION_EXPANSION_SOURCE_NODE_ID_KEY)
        val explicitRootNodeId = metadataCandidates.firstValue(INVOCATION_EXPANSION_ROOT_NODE_ID_KEY)
        val targetSignature = metadataCandidates.firstValue(INVOCATION_EXPANSION_TARGET_SIGNATURE_KEY)
        val createdAt = metadataCandidates.firstValue(INVOCATION_EXPANSION_CREATED_AT_KEY)
        val ownedNodeIds = draft.ownedNodeIds.toSet()
        val ownedEdges = draft.ownedEdgeIds.mapNotNull(edgesById::get)
        val rootNodeId = explicitRootNodeId
            ?: resolveExpansionRootNodeId(
                ownedNodes = ownedNodeIds.mapNotNull(nodesById::get),
                ownedEdges = ownedEdges,
                ownedNodeIds = ownedNodeIds,
            )
        val callEdgeIds = graph.edges
            .filter { edge ->
                edge.type == EdgeType.CALL &&
                    (
                        edge.metadata.expansionId() == draft.expansionId ||
                            (
                                sourceInvocationNodeId != null &&
                                    edge.fromNodeId == sourceInvocationNodeId &&
                                    (edge.toNodeId in ownedNodeIds || edge.toNodeId == rootNodeId)
                                )
                        )
            }
            .mapTo(linkedSetOf()) { edge -> edge.id }
        val borrowedNodeIds = linkedSetOf<String>()
        if (rootNodeId != null && rootNodeId in nodesById && rootNodeId !in ownedNodeIds) {
            borrowedNodeIds += rootNodeId
        }
        ownedEdges
            .filterNot { edge -> edge.id in callEdgeIds }
            .forEach { edge ->
                listOf(edge.fromNodeId, edge.toNodeId).forEach { nodeId ->
                    if (nodeId in nodesById && nodeId !in ownedNodeIds) {
                        borrowedNodeIds += nodeId
                    }
                }
            }
        val boundaryNodeIds = ownedNodeIds + borrowedNodeIds
        val internalEdgeIds = graph.edges
            .filter { edge ->
                edge.fromNodeId in boundaryNodeIds &&
                    edge.toNodeId in boundaryNodeIds &&
                    edge.id !in callEdgeIds
            }
            .mapTo(linkedSetOf()) { edge -> edge.id }
        val parentExpansionId = sourceInvocationNodeId
            ?.let(ownerExpansionIdByNodeId::get)
            ?.takeUnless { parentId -> parentId == draft.expansionId }
        InvocationExpansionEntry(
            expansionId = draft.expansionId,
            sourceInvocationNodeId = sourceInvocationNodeId,
            rootNodeId = rootNodeId,
            targetSignature = targetSignature,
            createdAt = createdAt,
            parentExpansionId = parentExpansionId,
            ownedNodeIds = ownedNodeIds,
            borrowedNodeIds = borrowedNodeIds,
            ownedEdgeIds = draft.ownedEdgeIds.toSet(),
            callEdgeIds = callEdgeIds,
            internalEdgeIds = internalEdgeIds,
            childExpansionIds = emptyList(),
        )
    }

    val childIdsByParentId = linkedMapOf<String, MutableList<String>>()
    stubs.forEach { entry ->
        entry.parentExpansionId?.let { parentId ->
            childIdsByParentId.getOrPut(parentId) { mutableListOf() } += entry.expansionId
        }
    }
    val nodeOrder = graph.nodes.mapIndexed { index, node -> node.id to index }.toMap()
    val stubsById = stubs.associateBy(InvocationExpansionEntry::expansionId)
    return sortEntries(stubs, nodeOrder).map { entry ->
        entry.copy(
            childExpansionIds = sortEntries(
                childIdsByParentId[entry.expansionId].orEmpty().mapNotNull(stubsById::get),
                nodeOrder,
            ).map(InvocationExpansionEntry::expansionId),
        )
    }
}

private fun resolveExpansionRootNodeId(
    ownedNodes: List<GraphNode>,
    ownedEdges: List<GraphEdge>,
    ownedNodeIds: Set<String>,
): String? {
    ownedNodes.firstOrNull { node -> node.type == NodeType.METHOD }?.let { node -> return node.id }
    val internalTargetIds = ownedEdges
        .filter { edge -> edge.fromNodeId in ownedNodeIds && edge.toNodeId in ownedNodeIds }
        .mapTo(linkedSetOf()) { edge -> edge.toNodeId }
    return ownedNodes.firstOrNull { node -> node.id !in internalTargetIds }?.id
        ?: ownedNodes.firstOrNull()?.id
}

private fun collectReachableExpansionIds(
    rootExpansionIds: Set<String>,
    entriesById: Map<String, InvocationExpansionEntry>,
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
    entries: List<InvocationExpansionEntry>,
    entriesById: Map<String, InvocationExpansionEntry>,
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
    entriesById: Map<String, InvocationExpansionEntry>,
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

private fun InvocationExpansionEntry.toSummary(
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

private fun sortEntries(
    entries: List<InvocationExpansionEntry>,
    nodeOrder: Map<String, Int>,
): List<InvocationExpansionEntry> =
    entries.sortedWith(
        compareBy<InvocationExpansionEntry> { entry ->
            entry.sourceInvocationNodeId?.let(nodeOrder::get) ?: Int.MAX_VALUE
        }
            .thenBy { entry -> entry.createdAt.orEmpty() }
            .thenBy { entry -> entry.targetSignature.orEmpty() }
            .thenBy { entry -> entry.expansionId },
    )

private fun List<Map<String, String>>.firstValue(key: String): String? =
    firstNotNullOfOrNull { metadata -> metadata[key]?.trim()?.takeIf(String::isNotBlank) }

private fun Map<String, String>.expansionId(): String? =
    this[INVOCATION_EXPANSION_ID_KEY]?.trim()?.takeIf(String::isNotBlank)

private fun projectedFromNodeIds(node: GraphNode): List<String> =
    node.metadata[FLOWCHART_PROJECTED_FROM_NODE_IDS_KEY]
        ?.split(',')
        ?.mapNotNull { nodeId -> nodeId.trim().takeIf(String::isNotBlank) }
        .orEmpty()

private const val INVOCATION_EXPANSION_ID_KEY = "linkGraph.expansion.id"
private const val INVOCATION_EXPANSION_SOURCE_NODE_ID_KEY = "linkGraph.expansion.sourceInvocationNodeId"
private const val INVOCATION_EXPANSION_ROOT_NODE_ID_KEY = "linkGraph.expansion.rootNodeId"
private const val INVOCATION_EXPANSION_TARGET_SIGNATURE_KEY = "linkGraph.expansion.targetSignature"
private const val INVOCATION_EXPANSION_CREATED_AT_KEY = "linkGraph.expansion.createdAt"
private const val FLOWCHART_PROJECTED_FROM_NODE_IDS_KEY = "flowchart.projectedFromNodeIds"
