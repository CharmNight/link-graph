package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.normalizeStableComponent
import com.charmnight.linkgraph.model.sourceLocation

/**
 * 步骤投影服务：把事实图按指定粒度（业务/方法调用/代码语义）投影为工作台步骤列表。
 */
class StepProjectionService {
    /** 根据粒度从事实图构建工作台步骤列表。 */
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

    /** 以方法调用为粒度构建步骤：纳入调用动作节点，以及被调用或有调用动作同行的普通方法节点。 */
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

    /** 以代码语义为粒度构建步骤：把流程作用域/动作/终态节点映射为条件/动作/返回等语义步骤。 */
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

    /** 判断节点是否可以作为业务步骤候选（流程动作或终态节点）。 */
    private fun isBusinessStepNode(node: GraphNode): Boolean {
        return node.type == NodeType.FLOW_ACTION || node.type == NodeType.TERMINAL
    }

    /** 取节点源码起始行号，缺失时返回 Int.MAX_VALUE 以确保排序时排在末尾。 */
    private fun sourceStartLine(node: GraphNode): Int {
        return node.sourceLocation().startLine ?: Int.MAX_VALUE
    }

    /** 把节点类型映射到对应的步骤种类，终态返回 RETURN，其余视为业务动作。 */
    private fun stepKindFor(node: GraphNode): StepKind {
        return when (node.type) {
            NodeType.TERMINAL -> StepKind.RETURN
            else -> StepKind.BUSINESS_ACTION
        }
    }

    /** 当节点未携带业务步骤 ID 元数据时，基于标题生成稳定的兜底步骤 ID。 */
    private fun fallbackStepId(node: GraphNode): String {
        return "step-${normalizeStableComponent(node.title)}"
    }

    /** 当节点未携带业务步骤标题元数据时，从节点标题中提取方法名并尝试翻译为中文业务动作描述。 */
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

    /** 拆分驼峰与分隔符，把方法名归一化为词列表，便于后续翻译与组装业务标题。 */
    private fun splitCamelCase(text: String): List<String> {
        val normalized = text
            .replace(Regex("[^A-Za-z0-9]"), " ")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .trim()
        return normalized.split(Regex("\\s+")).filter(String::isNotBlank)
    }

    /** 把常见英文动词/名词翻译为中文，未命中词典时按首字母大写形式原样返回。 */
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
