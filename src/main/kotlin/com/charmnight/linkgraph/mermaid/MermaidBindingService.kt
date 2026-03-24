package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode

class MermaidBindingService {
    fun apply(document: GraphDocument): GraphDocument {
        return document.copy(
            nodes = document.nodes.map { apply(it) },
        )
    }

    fun apply(node: GraphNode): GraphNode {
        val marker = node.metadata[BINDING_KEY]?.trim()?.uppercase() ?: return node
        val nextStatus = when (marker) {
            "UNMATCHED", "MULTI_CANDIDATE" -> BindingStatus.DESIGN_ONLY
            "CONFLICTED" -> BindingStatus.CONFLICTED
            else -> node.bindingStatus
        }
        return node.copy(bindingStatus = nextStatus)
    }

    companion object {
        const val BINDING_KEY: String = "binding"
    }
}
