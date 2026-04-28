package com.charmnight.linkgraph.llm.context

import com.charmnight.linkgraph.model.GraphDocument

/**
 * 图上下文选择器。
 */
class GraphContextSelector : ContextSelector<GraphDocument> {
    override fun select(input: GraphDocument): ContextSelectionResult {
        val sections = buildList {
            if (input.nodes.isNotEmpty()) {
                add("nodes=${input.nodes.size}")
            }
            if (input.edges.isNotEmpty()) {
                add("edges=${input.edges.size}")
            }
        }
        return ContextSelectionResult(
            sections = sections,
            summary = "graph nodes=${input.nodes.size}, edges=${input.edges.size}",
        )
    }
}
