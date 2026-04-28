package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode

internal fun buildTrustedNavigationNodeIndex(vararg graphs: GraphDocument?): Map<String, GraphNode> {
    return buildMap {
        graphs.asSequence()
            .filterNotNull()
            .flatMap { graph -> graph.nodes.asSequence() }
            .forEach { node -> put(node.id, node) }
    }
}
