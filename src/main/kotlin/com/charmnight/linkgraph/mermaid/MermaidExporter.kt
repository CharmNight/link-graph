package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

class MermaidExporter {
    fun export(document: GraphDocument): String {
        val lines = mutableListOf<String>()
        lines += "graph TD"
        document.nodes
            .sortedBy { it.id }
            .forEach { node ->
                val nodeId = stableNodeId(node)
                lines += """$nodeId["${buildNodeBody(node)}"]"""
            }
        document.edges
            .sortedBy { it.id }
            .forEach { edge ->
                lines += "${edge.fromNodeId} -- ${edge.type.name} --> ${edge.toNodeId}"
            }
        return lines.joinToString("\n")
    }

    private fun stableNodeId(node: GraphNode): String {
        if (node.id.isNotBlank()) {
            return node.id
        }
        val key = if (node.type == NodeType.METHOD) {
            node.signature ?: node.title
        } else {
            node.title
        }
        return com.charmnight.linkgraph.model.GraphNode.stableId(node.type, key)
    }

    private fun buildNodeBody(node: GraphNode): String {
        val segments = mutableListOf<String>()
        segments += node.type.name
        segments += node.title

        val emitted = mutableSetOf<String>()
        if (!node.signature.isNullOrBlank()) {
            segments += "signature=${node.signature}"
            emitted += "signature"
        }

        node.metadata
            .toSortedMap()
            .forEach { (key, value) ->
                if (key !in emitted) {
                    segments += "$key=$value"
                }
            }
        return segments.joinToString("|")
    }
}
