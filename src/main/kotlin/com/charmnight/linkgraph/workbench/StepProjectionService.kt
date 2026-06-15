package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.normalizeStableComponent
import com.charmnight.linkgraph.model.sourceLocation

class StepProjectionService {
    fun buildSteps(
        factGraph: GraphDocument,
        draftEntries: List<DraftWorkbenchEntry>,
        granularity: StepGranularity,
    ): StepProjectionResult {
        val steps = when (granularity) {
            StepGranularity.BUSINESS -> factGraph.nodes
                .asSequence()
                .filter(::isBusinessStepNode)
                .sortedBy(::sourceStartLine)
                .map { node ->
                    WorkbenchStep(
                        stepId = node.metadata["workbench.businessStepId"] ?: fallbackStepId(node),
                        title = node.metadata["workbench.businessStepTitle"] ?: fallbackBusinessTitle(node),
                        granularity = StepGranularity.BUSINESS,
                        kind = stepKindFor(node),
                        description = "",
                        nodeRefs = listOf(node.id),
                    )
                }
                .toList()

            StepGranularity.METHOD_CALL -> buildMethodCallSteps(factGraph)
            StepGranularity.CODE_SEMANTIC -> buildCodeSemanticSteps(factGraph)
        }
        return StepProjectionResult(steps = steps)
    }

    private fun buildMethodCallSteps(factGraph: GraphDocument): List<WorkbenchStep> {
        val invocationNodes = factGraph.nodes.filter { node ->
            node.type == NodeType.FLOW_ACTION && node.metadata["flow.kind"] == "INVOCATION"
        }
        val invocationLines = invocationNodes.map(::sourceStartLine).toSet()
        val calledMethodIds = factGraph.edges
            .filter { edge -> edge.type.name == "CALL" }
            .map { edge -> edge.toNodeId }
            .toSet()
        return factGraph.nodes
            .asSequence()
            .filter { node ->
                when (node.type) {
                    NodeType.FLOW_ACTION -> node.metadata["flow.kind"] == "INVOCATION"
                    NodeType.METHOD -> calledMethodIds.contains(node.id) || invocationLines.contains(sourceStartLine(node))
                    else -> false
                }
            }
            .sortedBy(::sourceStartLine)
            .map { node ->
                WorkbenchStep(
                    stepId = "method-call-${normalizeStableComponent(node.id)}",
                    title = node.title,
                    granularity = StepGranularity.METHOD_CALL,
                    kind = StepKind.METHOD_CALL,
                    description = "",
                    nodeRefs = listOf(node.id),
                )
            }
            .toList()
    }

    private fun buildCodeSemanticSteps(factGraph: GraphDocument): List<WorkbenchStep> {
        return factGraph.nodes
            .asSequence()
            .filter { node ->
                node.type == NodeType.FLOW_SCOPE || node.type == NodeType.FLOW_ACTION || node.type == NodeType.TERMINAL
            }
            .sortedBy(::sourceStartLine)
            .map { node ->
                WorkbenchStep(
                    stepId = "code-semantic-${normalizeStableComponent(node.id)}",
                    title = node.title,
                    granularity = StepGranularity.CODE_SEMANTIC,
                    kind = when (node.type) {
                        NodeType.FLOW_SCOPE -> StepKind.CONDITION
                        NodeType.TERMINAL -> StepKind.RETURN
                        else -> StepKind.BUSINESS_ACTION
                    },
                    description = "",
                    nodeRefs = listOf(node.id),
                )
            }
            .toList()
    }

    private fun isBusinessStepNode(node: GraphNode): Boolean {
        return node.type == NodeType.FLOW_ACTION || node.type == NodeType.TERMINAL
    }

    private fun sourceStartLine(node: GraphNode): Int {
        return node.sourceLocation().startLine ?: Int.MAX_VALUE
    }

    private fun stepKindFor(node: GraphNode): StepKind {
        return when (node.type) {
            NodeType.TERMINAL -> StepKind.RETURN
            else -> StepKind.BUSINESS_ACTION
        }
    }

    private fun fallbackStepId(node: GraphNode): String {
        return "step-${normalizeStableComponent(node.title)}"
    }

    private fun fallbackBusinessTitle(node: GraphNode): String {
        if (node.type == NodeType.TERMINAL) {
            return "返回结果"
        }
        val methodName = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
            .findAll(node.title)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?: node.title.substringAfterLast('.')
        val words = splitCamelCase(methodName)
        if (words.isEmpty()) {
            return node.title
        }
        val verb = words.first()
        val objectText = words.drop(1).map(::translateWord).joinToString("")
        return when (verb.lowercase()) {
            "get", "fetch" -> "获取${objectText.ifBlank { methodName }}"
            "read", "load", "resolve" -> "读取${objectText.ifBlank { methodName }}"
            "save", "write", "persist" -> "保存${objectText.ifBlank { methodName }}"
            "build" -> "拼接${objectText.ifBlank { methodName }}"
            "transfer", "upload", "copy" -> "执行${translateWord(verb).ifBlank { methodName }}"
            else -> "执行${translateWord(verb).ifBlank { methodName }}"
        }
    }

    private fun splitCamelCase(text: String): List<String> {
        val normalized = text
            .replace(Regex("[^A-Za-z0-9]"), " ")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .trim()
        return normalized.split(Regex("\\s+")).filter(String::isNotBlank)
    }

    private fun translateWord(word: String): String {
        return when (word.lowercase()) {
            "upload" -> "上传"
            "path" -> "路径"
            "dir" -> "目录"
            "directory" -> "目录"
            "file" -> "文件"
            "url" -> "URL"
            "config" -> "配置"
            "result" -> "结果"
            "save" -> "保存"
            "write" -> "写入"
            "build" -> "拼接"
            "transfer" -> "传输"
            "copy" -> "复制"
            "load" -> "加载"
            "read" -> "读取"
            "resolve" -> "解析"
            "get" -> "获取"
            else -> word.replaceFirstChar { char -> char.uppercase() }
        }
    }
}
