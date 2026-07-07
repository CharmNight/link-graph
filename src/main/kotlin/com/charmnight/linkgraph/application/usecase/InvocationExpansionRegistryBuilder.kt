package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

internal data class InvocationExpansionRegistryEntry(
    val expansionId: String,
    val sourceInvocationNodeId: String?,
    val rootNodeId: String?,
    val targetSignature: String?,
    val createdAt: String?,
    val parentExpansionId: String?,
    val depth: Int,
    val ownedNodeIds: Set<String>,
    val borrowedNodeIds: Set<String>,
    val ownedEdgeIds: Set<String>,
    val callEdgeIds: Set<String>,
    val internalEdgeIds: Set<String>,
    val childExpansionIds: List<String>,
    val warnings: List<String>,
)

internal object InvocationExpansionRegistryBuilder {
    fun build(graph: GraphDocument): List<InvocationExpansionRegistryEntry> {
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
        val edgeIndex = InvocationExpansionEdgeIndex(graph.edges)
        val ownerExpansionIdByNodeId = ownerExpansionIdByNodeId(draftsById.values, nodesById)
        val stubs = draftsById.values.map { draft ->
            val metadataCandidates = draft.taggedNodes.map(GraphNode::metadata) + draft.taggedEdges.map(GraphEdge::metadata)
            val sourceInvocationNodeId = metadataCandidates.firstValue(EXPANSION_SOURCE_INVOCATION_NODE_ID)
            val explicitRootNodeId = metadataCandidates.firstValue(EXPANSION_ROOT_NODE_ID)
            val ownedNodeIds = draft.ownedNodeIds.toSet()
            val ownedEdges = draft.ownedEdgeIds.mapNotNull(edgesById::get)
            val rootNodeId = explicitRootNodeId
                ?: resolveExpansionRootNodeId(
                    ownedNodes = ownedNodeIds.mapNotNull(nodesById::get),
                    ownedEdges = ownedEdges,
                    ownedNodeIds = ownedNodeIds,
                )
            val callEdgeIds = edgeIndex.callEdgesFor(
                expansionId = draft.expansionId,
                sourceInvocationNodeId = sourceInvocationNodeId,
                ownedNodeIds = ownedNodeIds,
                rootNodeId = rootNodeId,
            )
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
            val internalEdgeIds = edgeIndex.internalEdgesFor(boundaryNodeIds, callEdgeIds)
            val parentExpansionId = sourceInvocationNodeId
                ?.let(ownerExpansionIdByNodeId::get)
                ?.takeUnless { parentId -> parentId == draft.expansionId }
            InvocationExpansionRegistryEntry(
                expansionId = draft.expansionId,
                sourceInvocationNodeId = sourceInvocationNodeId,
                rootNodeId = rootNodeId,
                targetSignature = metadataCandidates.firstValue(EXPANSION_TARGET_SIGNATURE),
                createdAt = metadataCandidates.firstValue(EXPANSION_CREATED_AT),
                parentExpansionId = parentExpansionId,
                depth = 1,
                ownedNodeIds = ownedNodeIds,
                borrowedNodeIds = borrowedNodeIds,
                ownedEdgeIds = draft.ownedEdgeIds.toSet(),
                callEdgeIds = callEdgeIds,
                internalEdgeIds = internalEdgeIds,
                childExpansionIds = emptyList(),
                warnings = emptyList(),
            )
        }
        return attachHierarchy(stubs, graph.nodes)
    }

    private fun ownerExpansionIdByNodeId(
        drafts: Collection<InvocationExpansionDraft>,
        nodesById: Map<String, GraphNode>,
    ): Map<String, String> {
        val ownerExpansionIdByNodeId = linkedMapOf<String, String>()
        drafts.forEach { draft ->
            draft.ownedNodeIds.forEach { nodeId ->
                ownerExpansionIdByNodeId[nodeId] = draft.expansionId
                nodesById[nodeId]?.let { node ->
                    projectedFromNodeIds(node).forEach { aliasNodeId ->
                        ownerExpansionIdByNodeId[aliasNodeId] = draft.expansionId
                    }
                }
            }
        }
        return ownerExpansionIdByNodeId
    }

    private fun attachHierarchy(
        stubs: List<InvocationExpansionRegistryEntry>,
        nodes: List<GraphNode>,
    ): List<InvocationExpansionRegistryEntry> {
        val warningsById = stubs.associate { entry -> entry.expansionId to entry.warnings.toMutableList() }.toMutableMap()
        val stubsById = stubs.associateBy(InvocationExpansionRegistryEntry::expansionId)
        val parentById = stubs.associate { entry ->
            val parentId = entry.parentExpansionId
            entry.expansionId to if (parentId != null && parentId !in stubsById) {
                warningsById.getValue(entry.expansionId) += "missing-parent"
                null
            } else {
                parentId
            }
        }.toMutableMap()
        breakCyclicParentLinks(parentById, warningsById, stubsById.keys)
        val depthById = linkedMapOf<String, Int>()
        fun depthFor(expansionId: String): Int {
            depthById[expansionId]?.let { return it }
            val parentId = parentById[expansionId]
            val depth = if (parentId == null) 1 else depthFor(parentId) + 1
            depthById[expansionId] = depth
            return depth
        }
        stubs.forEach { entry -> depthFor(entry.expansionId) }

        val childIdsByParentId = linkedMapOf<String, MutableList<String>>()
        stubs.forEach { entry ->
            parentById[entry.expansionId]?.let { parentId ->
                childIdsByParentId.getOrPut(parentId) { mutableListOf() } += entry.expansionId
            }
        }
        val nodeOrder = nodes.mapIndexed { index, node -> node.id to index }.toMap()
        val normalizedStubsById = stubs.associate { entry ->
            entry.expansionId to entry.copy(
                parentExpansionId = parentById[entry.expansionId],
                depth = depthById.getValue(entry.expansionId),
                warnings = warningsById.getValue(entry.expansionId).toList(),
            )
        }
        return sortEntries(normalizedStubsById.values.toList(), nodeOrder).map { entry ->
            entry.copy(
                childExpansionIds = sortEntries(
                    childIdsByParentId[entry.expansionId].orEmpty().mapNotNull(normalizedStubsById::get),
                    nodeOrder,
                ).map(InvocationExpansionRegistryEntry::expansionId),
            )
        }
    }

    private fun breakCyclicParentLinks(
        parentById: MutableMap<String, String?>,
        warningsById: MutableMap<String, MutableList<String>>,
        expansionIds: Set<String>,
    ) {
        expansionIds.forEach { expansionId ->
            val pathIndexById = linkedMapOf<String, Int>()
            var currentId: String? = expansionId
            while (currentId != null && currentId in expansionIds) {
                val cycleStartIndex = pathIndexById[currentId]
                if (cycleStartIndex != null) {
                    pathIndexById.keys.drop(cycleStartIndex).forEach { cycleExpansionId ->
                        warningsById.getValue(cycleExpansionId).addWarning("cycle-parent")
                        parentById[cycleExpansionId] = null
                    }
                    break
                }
                pathIndexById[currentId] = pathIndexById.size
                currentId = parentById[currentId]
            }
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

    private fun sortEntries(
        entries: List<InvocationExpansionRegistryEntry>,
        nodeOrder: Map<String, Int>,
    ): List<InvocationExpansionRegistryEntry> =
        entries.sortedWith(
            compareBy<InvocationExpansionRegistryEntry> { entry ->
                entry.sourceInvocationNodeId?.let(nodeOrder::get) ?: Int.MAX_VALUE
            }
                .thenBy { entry -> entry.createdAt.orEmpty() }
                .thenBy { entry -> entry.targetSignature.orEmpty() }
                .thenBy { entry -> entry.expansionId },
    )
}

private fun MutableList<String>.addWarning(warning: String) {
    if (warning !in this) {
        this += warning
    }
}

private data class InvocationExpansionDraft(
    val expansionId: String,
    val taggedNodes: MutableList<GraphNode> = mutableListOf(),
    val taggedEdges: MutableList<GraphEdge> = mutableListOf(),
    val ownedNodeIds: LinkedHashSet<String> = linkedSetOf(),
    val ownedEdgeIds: LinkedHashSet<String> = linkedSetOf(),
)

private class InvocationExpansionEdgeIndex(edges: List<GraphEdge>) {
    private val callEdgesByExpansionId = linkedMapOf<String, MutableList<GraphEdge>>()
    private val callEdgesBySourceNodeId = linkedMapOf<String, MutableList<GraphEdge>>()
    private val edgesByEndpointNodeId = linkedMapOf<String, MutableList<GraphEdge>>()

    init {
        edges.forEach { edge ->
            edgesByEndpointNodeId.getOrPut(edge.fromNodeId) { mutableListOf() } += edge
            edgesByEndpointNodeId.getOrPut(edge.toNodeId) { mutableListOf() } += edge
            if (edge.type == EdgeType.CALL) {
                edge.metadata.expansionId()?.let { expansionId ->
                    callEdgesByExpansionId.getOrPut(expansionId) { mutableListOf() } += edge
                }
                callEdgesBySourceNodeId.getOrPut(edge.fromNodeId) { mutableListOf() } += edge
            }
        }
    }

    fun callEdgesFor(
        expansionId: String,
        sourceInvocationNodeId: String?,
        ownedNodeIds: Set<String>,
        rootNodeId: String?,
    ): Set<String> {
        val result = linkedSetOf<String>()
        callEdgesByExpansionId[expansionId].orEmpty().mapTo(result, GraphEdge::id)
        if (sourceInvocationNodeId != null) {
            callEdgesBySourceNodeId[sourceInvocationNodeId].orEmpty()
                .filter { edge -> edge.toNodeId in ownedNodeIds || edge.toNodeId == rootNodeId }
                .mapTo(result, GraphEdge::id)
        }
        return result
    }

    fun internalEdgesFor(
        boundaryNodeIds: Set<String>,
        callEdgeIds: Set<String>,
    ): Set<String> {
        val candidates = boundaryNodeIds
            .asSequence()
            .flatMap { nodeId -> edgesByEndpointNodeId[nodeId].orEmpty().asSequence() }
            .distinctBy(GraphEdge::id)
        return candidates
            .filter { edge -> edge.fromNodeId in boundaryNodeIds && edge.toNodeId in boundaryNodeIds && edge.id !in callEdgeIds }
            .mapTo(linkedSetOf(), GraphEdge::id)
    }
}

private fun List<Map<String, String>>.firstValue(key: String): String? =
    firstNotNullOfOrNull { metadata -> metadata[key]?.trim()?.takeIf(String::isNotBlank) }

private fun Map<String, String>.expansionId(): String? =
    this[EXPANSION_ID]?.trim()?.takeIf(String::isNotBlank)

private fun projectedFromNodeIds(node: GraphNode): List<String> =
    node.metadata[FLOWCHART_PROJECTED_FROM_NODE_IDS]
        ?.split(',')
        ?.mapNotNull { nodeId -> nodeId.trim().takeIf(String::isNotBlank) }
        .orEmpty()

internal const val EXPANSION_ID = "linkGraph.expansion.id"
internal const val EXPANSION_SOURCE_INVOCATION_NODE_ID = "linkGraph.expansion.sourceInvocationNodeId"
internal const val EXPANSION_ROOT_NODE_ID = "linkGraph.expansion.rootNodeId"
internal const val EXPANSION_TARGET_SIGNATURE = "linkGraph.expansion.targetSignature"
internal const val EXPANSION_CREATED_AT = "linkGraph.expansion.createdAt"
internal const val FLOWCHART_PROJECTED_FROM_NODE_IDS = "flowchart.projectedFromNodeIds"
