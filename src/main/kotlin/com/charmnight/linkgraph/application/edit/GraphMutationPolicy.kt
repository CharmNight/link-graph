package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.GraphEdgeEditInput
import com.charmnight.linkgraph.application.model.GraphEditRequestSource
import com.charmnight.linkgraph.application.model.GraphNodeEditInput
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphProvenance

/** 为不可信编辑意图分配领域信任字段的唯一策略。 */
internal class GraphMutationPolicy {
    fun newNode(input: GraphNodeEditInput, source: GraphEditRequestSource): GraphNode =
        GraphNode(
            id = input.id,
            type = input.type,
            title = input.title,
            inputs = input.inputs,
            outputs = input.outputs,
            doc = input.doc,
            metadata = input.metadata,
            provenance = provenanceFor(source),
        )

    fun newEdge(input: GraphEdgeEditInput, source: GraphEditRequestSource): GraphEdge =
        GraphEdge(
            id = input.id,
            type = input.type,
            fromNodeId = input.fromNodeId,
            toNodeId = input.toNodeId,
            label = input.label,
            metadata = input.metadata,
            provenance = provenanceFor(source),
        )

    private fun provenanceFor(source: GraphEditRequestSource): GraphProvenance = when (source) {
        GraphEditRequestSource.AI_TOOL -> GraphProvenance.AI_DRAFT
        GraphEditRequestSource.FRONTEND,
        GraphEditRequestSource.DEBUG_AUTOMATION,
        -> GraphProvenance.USER_DRAFT
    }
}
