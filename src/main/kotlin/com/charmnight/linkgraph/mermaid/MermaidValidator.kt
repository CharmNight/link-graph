package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.model.GraphBinding
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.NodeType

/**
 * Mermaid 导入后的二次校验器。
 * 解析能成功不代表设计可用，这里继续补结构、语义和代码绑定层面的告警。
 */
class MermaidValidator {
    /**
     * 校验导入后的 Mermaid 图文档，并返回问题列表。
     */
    fun validate(
        document: GraphDocument,
        parseIssues: List<MermaidIssue> = emptyList(),
    ): List<MermaidIssue> {
        // 先把解析阶段的问题并入最终问题列表。
        val issues = mutableListOf<MermaidIssue>()
        issues += parseIssues

        // 节点标识必须唯一，否则后续绑定和 diff 都会失真。
        val seenNodeIds = mutableSetOf<String>()
        for (node in document.nodes) {
            if (!seenNodeIds.add(node.id)) {
                issues += MermaidIssue(
                    category = MermaidIssue.Category.STRUCTURE,
                    code = "duplicate-node-id",
                    message = "节点 ID '${node.id}' 重复。",
                    nodeId = node.id,
                )
            }
        }

        // 边引用的起止节点必须真实存在。
        val nodeIds = document.nodes.map { it.id }.toSet()
        for (edge in document.edges) {
            if (edge.fromNodeId !in nodeIds || edge.toNodeId !in nodeIds) {
                issues += MermaidIssue(
                    category = MermaidIssue.Category.STRUCTURE,
                    code = "edge-references-missing-node",
                    message = "边 '${edge.id}' 引用了不存在的节点。",
                    edgeId = edge.id,
                )
            }
        }

        // 针对不同节点类型补充语义层和绑定层校验。
        for (node in document.nodes) {
            when (node.type) {
                NodeType.METHOD -> {
                    if (node.signature.isNullOrBlank()) {
                        issues += MermaidIssue(
                            category = MermaidIssue.Category.SEMANTIC,
                            code = "missing-method-signature",
                            message = "方法节点 '${node.id}' 缺少 signature 元数据。",
                            nodeId = node.id,
                        )
                    }
                }

                NodeType.HTTP_ENDPOINT -> {
                    if (node.metadata["path"].isNullOrBlank()) {
                        issues += MermaidIssue(
                            category = MermaidIssue.Category.SEMANTIC,
                            code = "missing-http-path",
                            message = "HTTP 接口节点 '${node.id}' 缺少 path 元数据。",
                            nodeId = node.id,
                        )
                    }
                }

                NodeType.MQ_TOPIC -> {
                    if (node.metadata["topic"].isNullOrBlank()) {
                        issues += MermaidIssue(
                            category = MermaidIssue.Category.SEMANTIC,
                            code = "missing-mq-topic",
                            message = "MQ 主题节点 '${node.id}' 缺少 topic 元数据。",
                            nodeId = node.id,
                        )
                    }
                }

                else -> Unit
            }

            // 绑定标记和绑定状态都可能暴露出设计图与代码图的不一致。
            val marker = node.metadata[MermaidBindingService.BINDING_KEY]?.trim()?.uppercase()
            when {
                marker == "UNMATCHED" -> issues += MermaidIssue(
                    category = MermaidIssue.Category.BINDING,
                    code = "binding-unmatched",
                    message = "节点 '${node.id}' 未匹配到代码。",
                    nodeId = node.id,
                )

                marker == "MULTI_CANDIDATE" -> issues += MermaidIssue(
                    category = MermaidIssue.Category.BINDING,
                    code = "binding-multi-candidate",
                    message = "节点 '${node.id}' 匹配到多个代码候选项。",
                    nodeId = node.id,
                )

                marker == "CONFLICTED" || node.binding == GraphBinding.CONFLICTED -> issues += MermaidIssue(
                    category = MermaidIssue.Category.BINDING,
                    code = "binding-conflicted",
                    message = "节点 '${node.id}' 的绑定结果冲突。",
                    nodeId = node.id,
                )
            }
        }

        return issues
    }
}
