package com.charmnight.linkgraph.model.diagnostics

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch

/**
 * 图补丁诊断工具。
 *
 * 把补丁、节点状态等结构化数据压缩为单行字符串，
 * 用于日志、错误信息等需要紧凑展示的场景。
 * 内部使用统一的截断与归一化逻辑保证输出可读。
 */
internal object GraphPatchDiagnostics {
    /**
     * 把图补丁摘要为单行字符串。
     * 包含 summary、操作数、增删节点/边数与操作目标标题样本。
     */
    fun summarizeGraphPatch(patch: GraphPatch?): String {
        if (patch == null) {
            return "null"
        }
        return buildString {
            append("summary=").append(trimmed(patch.summary))
            append(", operations=").append(patch.operations.size)
            append(", opTargets=").append(
                entryTitles(
                    patch.operations.map { operation ->
                        buildString {
                            append(operation.action.name)
                            append(':')
                            append(operation.elementId)
                            operation.node?.title?.takeIf(String::isNotBlank)?.let {
                                append(':').append(trimmed(it))
                            }
                        }
                    },
                ),
            )
            append(", addedNodes=").append(entryTitles(patch.addedNodeIds))
            append(", removedNodes=").append(entryTitles(patch.removedNodeIds))
            append(", addedEdges=").append(entryTitles(patch.addedEdgeIds))
            append(", removedEdges=").append(entryTitles(patch.removedEdgeIds))
        }
    }

    /**
     * 摘要一组节点的当前状态。
     * 缺失节点标记为 `<missing>`，存在节点附上标题。
     */
    fun summarizeNodeStates(
        graph: GraphDocument,
        nodeIds: Collection<String>,
    ): String {
        if (nodeIds.isEmpty()) {
            return "[]"
        }
        val nodesById = graph.nodes.associateBy { it.id }
        val values = nodeIds
            .filter { it.isNotBlank() }
            .distinct()
            .map { nodeId ->
                val node = nodesById[nodeId]
                if (node == null) {
                    "$nodeId:<missing>"
                } else {
                    "$nodeId:${trimmed(node.title)}"
                }
            }
        return entryTitles(values)
    }

    /**
     * 把任意字符串列表摘要为紧凑形式。
     * 超过 3 个元素时只展示前 3 个并加 ", ..."。
     */
    fun entryTitles(values: List<String>): String {
        if (values.isEmpty()) {
            return "[]"
        }
        return values.take(3).joinToString(
            prefix = "[",
            postfix = if (values.size > 3) ", ...]" else "]",
        ) { trimmed(it) }
    }

    /**
     * 把任意字符串归一化为诊断友好的单行片段。
     * 处理：换行替换为空格、合并多余空白、截断到 96 字符。
     * 空字符串返回 "-"。
     */
    fun trimmed(value: String?): String {
        val normalized = value?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return "-"
        }
        // 96 字符是经验值：足够看出问题，又不会让日志过长
        return if (normalized.length <= 96) normalized else normalized.take(93) + "..."
    }
}
