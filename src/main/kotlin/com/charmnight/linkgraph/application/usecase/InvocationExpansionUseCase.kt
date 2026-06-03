package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import java.time.Instant
import java.util.UUID

enum class InvocationExpansionTargetKind {
    PROJECT_SOURCE,
    MULTIPLE_IMPLEMENTATIONS,
    NO_IMPLEMENTATION,
    EXTERNAL_JDK,
    EXTERNAL_LIBRARY,
    CROSS_SERVICE,
    NOT_FOUND,
}

data class InvocationExpansionTarget(
    val kind: InvocationExpansionTargetKind,
    val signature: String? = null,
    val candidateSignatures: List<String> = emptyList(),
    val message: String? = null,
)

data class InvocationExpansionMergeResult(
    val graph: GraphDocument,
    val expansionId: String,
)

data class InvocationExpansionRemovalResult(
    val graph: GraphDocument,
    val removed: Boolean,
)

class InvocationExpansionUseCase(
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> String = { Instant.now().toString() },
) {
    enum class ValidationResult {
        READY,
        NOT_INVOCATION,
        MISSING_SIGNATURE,
    }

    fun validateInvocationNode(node: GraphNode): ValidationResult {
        if (node.type != NodeType.FLOW_ACTION || node.metadata["flow.kind"] != "INVOCATION") {
            return ValidationResult.NOT_INVOCATION
        }
        if (node.signature.isNullOrBlank()) {
            return ValidationResult.MISSING_SIGNATURE
        }
        return ValidationResult.READY
    }

    fun mergeExpansion(
        workspace: GraphDocument,
        sourceInvocationNode: GraphNode,
        targetGraph: GraphDocument,
        targetEntryNodeId: String,
        targetSignature: String,
    ): InvocationExpansionMergeResult {
        val expansionId = "invocation:${idGenerator()}"
        val existingNodeIds = workspace.nodes.mapTo(linkedSetOf(), GraphNode::id)
        val existingEdgeIds = workspace.edges.mapTo(linkedSetOf(), GraphEdge::id)
        val expansionMetadata = expansionMetadata(
            expansionId = expansionId,
            rootNodeId = targetEntryNodeId,
            sourceInvocationNodeId = sourceInvocationNode.id,
            targetSignature = targetSignature,
        )
        val nodesById = LinkedHashMap(workspace.nodes.associateBy(GraphNode::id))
        targetGraph.nodes.forEach { node ->
            if (node.id !in existingNodeIds) {
                nodesById[node.id] = node.copy(metadata = node.metadata + expansionMetadata)
            }
        }
        val edgesById = LinkedHashMap(workspace.edges.associateBy(GraphEdge::id))
        targetGraph.edges.forEach { edge ->
            if (edge.id !in existingEdgeIds) {
                edgesById[edge.id] = edge.copy(metadata = edge.metadata + expansionMetadata)
            }
        }
        val connector = GraphEdge(
            id = GraphEdge.stableId(EdgeType.CALL, sourceInvocationNode.id, targetEntryNodeId, expansionId),
            type = EdgeType.CALL,
            fromNodeId = sourceInvocationNode.id,
            toNodeId = targetEntryNodeId,
            label = "展开调用",
            metadata = expansionMetadata,
        )
        edgesById.putIfAbsent(connector.id, connector)
        return InvocationExpansionMergeResult(
            graph = GraphDocument(
                nodes = nodesById.values.sortedBy(GraphNode::id),
                edges = edgesById.values.sortedBy(GraphEdge::id),
                patch = workspace.patch,
            ),
            expansionId = expansionId,
        )
    }

    fun removeExpansion(
        graph: GraphDocument,
        expansionId: String,
    ): InvocationExpansionRemovalResult {
        val removedNodeIds = graph.nodes
            .filter { node -> node.metadata[EXPANSION_ID] == expansionId }
            .mapTo(linkedSetOf(), GraphNode::id)
        val removedEdgeIds = graph.edges
            .filter { edge -> edge.metadata[EXPANSION_ID] == expansionId }
            .mapTo(linkedSetOf(), GraphEdge::id)
        if (removedNodeIds.isEmpty() && removedEdgeIds.isEmpty()) {
            return InvocationExpansionRemovalResult(graph = graph, removed = false)
        }
        return InvocationExpansionRemovalResult(
            graph = GraphDocument(
                nodes = graph.nodes.filterNot { node -> node.id in removedNodeIds },
                edges = graph.edges.filterNot { edge ->
                    edge.id in removedEdgeIds ||
                        edge.fromNodeId in removedNodeIds ||
                        edge.toNodeId in removedNodeIds
                },
                patch = graph.patch,
            ),
            removed = true,
        )
    }

    private fun expansionMetadata(
        expansionId: String,
        rootNodeId: String,
        sourceInvocationNodeId: String,
        targetSignature: String,
    ): Map<String, String> = mapOf(
        EXPANSION_ID to expansionId,
        EXPANSION_ROOT_NODE_ID to rootNodeId,
        EXPANSION_SOURCE_INVOCATION_NODE_ID to sourceInvocationNodeId,
        EXPANSION_TARGET_SIGNATURE to targetSignature,
        EXPANSION_CREATED_AT to clock(),
        EXPANSION_KIND to EXPANSION_KIND_INVOCATION,
    )

    companion object {
        const val EXPANSION_ID = "linkGraph.expansion.id"
        const val EXPANSION_ROOT_NODE_ID = "linkGraph.expansion.rootNodeId"
        const val EXPANSION_SOURCE_INVOCATION_NODE_ID = "linkGraph.expansion.sourceInvocationNodeId"
        const val EXPANSION_TARGET_SIGNATURE = "linkGraph.expansion.targetSignature"
        const val EXPANSION_CREATED_AT = "linkGraph.expansion.createdAt"
        const val EXPANSION_KIND = "linkGraph.expansion.kind"
        const val EXPANSION_KIND_INVOCATION = "INVOCATION"
    }
}
