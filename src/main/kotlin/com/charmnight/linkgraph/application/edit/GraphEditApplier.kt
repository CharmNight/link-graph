package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.workflow.FrontendGraphMutationSanitizer
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode

internal class GraphEditApplier(
    private val frontendGraphMutationSanitizer: FrontendGraphMutationSanitizer = FrontendGraphMutationSanitizer(),
) {
    fun apply(
        snapshot: WorkflowEditorSnapshot,
        request: GraphEditRequest,
        resolution: GraphEditResolution,
    ): GraphDocument {
        val workingGraph = snapshot.workspaceGraph
        val trustedNodes = snapshot.trustedNavigationNodes
        val nodesById = LinkedHashMap(workingGraph.nodes.associateBy(GraphNode::id))
        val edgesById = LinkedHashMap(workingGraph.edges.associateBy(GraphEdge::id))

        request.operations.forEach { operation ->
            when (operation) {
                is GraphEditOperation.UpsertNode -> {
                    val targetNodeId = resolution.nodeTargetId(operation.node.id)
                    val sanitizedNode = frontendGraphMutationSanitizer.sanitize(
                        snapshot,
                        GraphDocument(nodes = listOf(operation.node.copy(id = targetNodeId))),
                    ).nodes.firstOrNull() ?: operation.node.copy(id = targetNodeId)
                    val existingTrustedNode = trustedNodes[targetNodeId]
                    nodesById[targetNodeId] = existingTrustedNode?.copy(
                        title = sanitizedNode.title,
                        inputs = sanitizedNode.inputs,
                        outputs = sanitizedNode.outputs,
                        doc = sanitizedNode.doc,
                        metadata = existingTrustedNode.metadata + sanitizedNode.metadata,
                    ) ?: sanitizedNode.copy(id = targetNodeId)
                }
                is GraphEditOperation.RemoveNode -> {
                    val nodeIds = resolution.nodeRemovalIds(operation.nodeId)
                    nodeIds.forEach(nodesById::remove)
                    edgesById.entries.removeIf { (_, edge) -> edge.fromNodeId in nodeIds || edge.toNodeId in nodeIds }
                }
                is GraphEditOperation.UpsertEdge -> {
                    val targetEdgeId = resolution.edgeTargetId(operation.edge.id)
                    edgesById[targetEdgeId] = operation.edge.copy(
                        id = targetEdgeId,
                        fromNodeId = resolution.nodeTargetId(operation.edge.fromNodeId),
                        toNodeId = resolution.nodeTargetId(operation.edge.toNodeId),
                    )
                }
                is GraphEditOperation.RemoveEdge -> {
                    resolution.edgeRemovalIds(operation.edgeId).forEach(edgesById::remove)
                }
            }
        }

        return workingGraph.copy(
            nodes = nodesById.values.toList(),
            edges = edgesById.values.toList(),
            patch = workingGraph.patch,
        )
    }
}
