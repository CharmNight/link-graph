package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.NodeType

class MermaidValidator {
    fun validate(
        document: GraphDocument,
        parseIssues: List<MermaidIssue> = emptyList(),
    ): List<MermaidIssue> {
        val issues = mutableListOf<MermaidIssue>()
        issues += parseIssues

        val seenNodeIds = mutableSetOf<String>()
        for (node in document.nodes) {
            if (!seenNodeIds.add(node.id)) {
                issues += MermaidIssue(
                    category = MermaidIssue.Category.STRUCTURE,
                    code = "duplicate-node-id",
                    message = "Duplicate node id '${node.id}'.",
                    nodeId = node.id,
                )
            }
        }

        val nodeIds = document.nodes.map { it.id }.toSet()
        for (edge in document.edges) {
            if (edge.fromNodeId !in nodeIds || edge.toNodeId !in nodeIds) {
                issues += MermaidIssue(
                    category = MermaidIssue.Category.STRUCTURE,
                    code = "edge-references-missing-node",
                    message = "Edge '${edge.id}' references a missing node.",
                    edgeId = edge.id,
                )
            }
        }

        for (node in document.nodes) {
            when (node.type) {
                NodeType.METHOD -> {
                    if (node.signature.isNullOrBlank()) {
                        issues += MermaidIssue(
                            category = MermaidIssue.Category.SEMANTIC,
                            code = "missing-method-signature",
                            message = "METHOD node '${node.id}' is missing signature metadata.",
                            nodeId = node.id,
                        )
                    }
                }

                NodeType.HTTP_ENDPOINT -> {
                    if (node.metadata["path"].isNullOrBlank()) {
                        issues += MermaidIssue(
                            category = MermaidIssue.Category.SEMANTIC,
                            code = "missing-http-path",
                            message = "HTTP_ENDPOINT node '${node.id}' is missing path metadata.",
                            nodeId = node.id,
                        )
                    }
                }

                NodeType.MQ_TOPIC -> {
                    if (node.metadata["topic"].isNullOrBlank()) {
                        issues += MermaidIssue(
                            category = MermaidIssue.Category.SEMANTIC,
                            code = "missing-mq-topic",
                            message = "MQ_TOPIC node '${node.id}' is missing topic metadata.",
                            nodeId = node.id,
                        )
                    }
                }

                else -> Unit
            }

            val marker = node.metadata[MermaidBindingService.BINDING_KEY]?.trim()?.uppercase()
            when {
                marker == "UNMATCHED" -> issues += MermaidIssue(
                    category = MermaidIssue.Category.BINDING,
                    code = "binding-unmatched",
                    message = "Node '${node.id}' has no code match.",
                    nodeId = node.id,
                )

                marker == "MULTI_CANDIDATE" -> issues += MermaidIssue(
                    category = MermaidIssue.Category.BINDING,
                    code = "binding-multi-candidate",
                    message = "Node '${node.id}' has multiple code candidates.",
                    nodeId = node.id,
                )

                marker == "CONFLICTED" || node.bindingStatus == BindingStatus.CONFLICTED -> issues += MermaidIssue(
                    category = MermaidIssue.Category.BINDING,
                    code = "binding-conflicted",
                    message = "Node '${node.id}' has conflicted binding.",
                    nodeId = node.id,
                )
            }
        }

        return issues
    }
}
