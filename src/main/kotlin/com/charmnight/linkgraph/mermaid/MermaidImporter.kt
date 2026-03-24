package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

class MermaidImporter(
    private val bindingService: MermaidBindingService = MermaidBindingService(),
) {
    fun import(mermaid: String): MermaidParseResult {
        val issues = mutableListOf<MermaidIssue>()
        val nodesById = linkedMapOf<String, GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        var sawHeader = false

        mermaid.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("%%")) {
                return@forEachIndexed
            }
            if (!sawHeader) {
                if (line.startsWith("graph ")) {
                    sawHeader = true
                    return@forEachIndexed
                }
                issues += MermaidIssue(
                    category = MermaidIssue.Category.SYNTAX,
                    code = "missing-graph-header",
                    message = "Mermaid input must start with graph header.",
                    line = lineNumber,
                )
                sawHeader = true
            }

            val nodeMatch = NODE_PATTERN.matchEntire(line)
            if (nodeMatch != null) {
                val node = parseNode(
                    id = nodeMatch.groupValues[1],
                    body = nodeMatch.groupValues[2],
                    line = lineNumber,
                    issues = issues,
                )
                if (nodesById.containsKey(node.id)) {
                    issues += MermaidIssue(
                        category = MermaidIssue.Category.STRUCTURE,
                        code = "duplicate-node-id",
                        message = "Duplicate node id '${node.id}'.",
                        line = lineNumber,
                        nodeId = node.id,
                    )
                } else {
                    nodesById[node.id] = node
                }
                return@forEachIndexed
            }

            val edgeMatch = EDGE_PATTERN.matchEntire(line)
            if (edgeMatch != null) {
                val fromNodeId = edgeMatch.groupValues[1]
                val edgeTypeRaw = edgeMatch.groupValues[2]
                val toNodeId = edgeMatch.groupValues[3]
                val edgeType = EdgeType.entries.firstOrNull { it.name == edgeTypeRaw }
                if (edgeType == null) {
                    issues += MermaidIssue(
                        category = MermaidIssue.Category.SYNTAX,
                        code = "unknown-edge-type",
                        message = "Unknown edge type '$edgeTypeRaw'.",
                        line = lineNumber,
                    )
                    return@forEachIndexed
                }
                edges += GraphEdge(
                    id = GraphEdge.stableId(edgeType, fromNodeId, toNodeId),
                    type = edgeType,
                    fromNodeId = fromNodeId,
                    toNodeId = toNodeId,
                )
                return@forEachIndexed
            }

            issues += MermaidIssue(
                category = MermaidIssue.Category.SYNTAX,
                code = "unsupported-line",
                message = "Unsupported Mermaid line: '$line'.",
                line = lineNumber,
            )
        }

        for (edge in edges) {
            ensureNodeExists(edge.fromNodeId, nodesById, issues)
            ensureNodeExists(edge.toNodeId, nodesById, issues)
        }

        val document = bindingService.apply(
            GraphDocument(
                nodes = nodesById.values.toList(),
                edges = edges.distinctBy { it.id },
            ),
        )
        return MermaidParseResult(document = document, issues = issues)
    }

    private fun parseNode(
        id: String,
        body: String,
        line: Int,
        issues: MutableList<MermaidIssue>,
    ): GraphNode {
        val parts = body.split('|').map { it.trim() }
        if (parts.size < 2) {
            issues += MermaidIssue(
                category = MermaidIssue.Category.SYNTAX,
                code = "invalid-node-body",
                message = "Node '$id' must include TYPE|TITLE.",
                line = line,
                nodeId = id,
            )
            return GraphNode(id = id, type = NodeType.UNCERTAIN_LINK, title = id)
        }

        val type = NodeType.entries.firstOrNull { it.name == parts[0] }
        if (type == null) {
            issues += MermaidIssue(
                category = MermaidIssue.Category.SYNTAX,
                code = "unknown-node-type",
                message = "Unknown node type '${parts[0]}' for node '$id'.",
                line = line,
                nodeId = id,
            )
        }
        val metadata = linkedMapOf<String, String>()
        for (segment in parts.drop(2)) {
            val delimiterIndex = segment.indexOf('=')
            if (delimiterIndex <= 0 || delimiterIndex >= segment.length - 1) {
                issues += MermaidIssue(
                    category = MermaidIssue.Category.SYNTAX,
                    code = "invalid-node-attribute",
                    message = "Invalid attribute '$segment' in node '$id'. Expected key=value.",
                    line = line,
                    nodeId = id,
                )
                continue
            }
            val key = segment.substring(0, delimiterIndex).trim()
            val value = segment.substring(delimiterIndex + 1).trim()
            metadata[key] = value
        }

        return GraphNode(
            id = id,
            type = type ?: NodeType.UNCERTAIN_LINK,
            title = parts[1].ifBlank { id },
            signature = metadata["signature"],
            metadata = metadata,
        )
    }

    private fun ensureNodeExists(
        nodeId: String,
        nodesById: MutableMap<String, GraphNode>,
        issues: MutableList<MermaidIssue>,
    ) {
        if (nodesById.containsKey(nodeId)) {
            return
        }
        issues += MermaidIssue(
            category = MermaidIssue.Category.STRUCTURE,
            code = "edge-references-missing-node",
            message = "Edge references undeclared node '$nodeId'.",
            nodeId = nodeId,
        )
        nodesById[nodeId] = GraphNode(
            id = nodeId,
            type = NodeType.UNCERTAIN_LINK,
            title = nodeId,
            metadata = mapOf(MermaidBindingService.BINDING_KEY to "UNMATCHED"),
        )
    }

    private companion object {
        val NODE_PATTERN = Regex("""^([A-Za-z0-9:_-]+)\["([^"]*)"]$""")
        val EDGE_PATTERN = Regex("""^([A-Za-z0-9:_-]+)\s*--\s*([A-Z_]+)\s*-->\s*([A-Za-z0-9:_-]+)$""")
    }
}
