package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode

internal class FrontendGraphMutationSanitizer {
    fun sanitize(
        snapshot: WorkflowEditorSnapshot,
        graph: GraphDocument,
    ): GraphDocument {
        val trustedNodes = snapshot.trustedNavigationNodes
        return graph.copy(
            nodes = graph.nodes.map { node ->
                trustedNodes[node.id]
                    ?.let { trustedNode -> sanitizeExistingNode(node, trustedNode) }
                    ?: sanitizeNewNode(node)
            },
        )
    }

    private fun sanitizeExistingNode(
        node: GraphNode,
        trustedNode: GraphNode,
    ): GraphNode {
        return trustedNode.copy(
            title = node.title,
            inputs = node.inputs,
            outputs = node.outputs,
            doc = node.doc,
        )
    }

    private fun sanitizeNewNode(node: GraphNode): GraphNode {
        return node.copy(
            location = null,
            signature = null,
        )
    }
}
