package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.diff.GraphNormalizationService
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode

/**
 * 负责把 Mermaid 设计图中的节点和边绑定到代码事实图。
 */
class MermaidBindingService(
    /** 保存图规范化服务，用于生成查找键。 */
    private val normalizationService: GraphNormalizationService = GraphNormalizationService(),
) {
    /**
     * 根据节点元数据中的绑定标记回填绑定状态。
     */
    fun apply(document: GraphDocument): GraphDocument {
        return document.copy(
            nodes = document.nodes.map { apply(it) },
        )
    }

    /**
     * 基于 Mermaid 图和代码图执行节点、边绑定。
     */
    fun bind(
        mermaidGraph: GraphDocument,
        codeGraph: GraphDocument,
    ): GraphDocument {
        // 先按代码节点标识构建直接索引，优先命中精确绑定。
        val codeNodesById = codeGraph.nodes.associateBy { it.id.trim() }
        // 再构建多键查找索引，支持按标题、路径、签名等模糊匹配。
        val lookupIndex = buildLookupIndex(codeGraph.nodes)

        // 逐个为 Mermaid 节点解析绑定结果，并写回绑定状态与元数据。
        val boundNodes = mermaidGraph.nodes.map { node ->
            val resolution = resolveBinding(node, codeNodesById, lookupIndex)
            apply(node, resolution)
        }
        // 记录 Mermaid 节点到绑定后目标节点的映射，后续边绑定要依赖它。
        val boundNodeIds = boundNodes.associate { node ->
            node.id to (node.metadata[BOUND_NODE_ID_KEY] ?: node.id)
        }

        // 根据节点绑定结果推导边的绑定状态和目标节点标识。
        val boundEdges = mermaidGraph.edges.map { edge ->
            val boundFromNodeId = boundNodeIds[edge.fromNodeId] ?: edge.fromNodeId
            val boundToNodeId = boundNodeIds[edge.toNodeId] ?: edge.toNodeId
            // 复制并扩展原有元数据，记录绑定后的两端节点标识。
            val metadata = linkedMapOf<String, String>()
            metadata.putAll(edge.metadata)
            if (boundFromNodeId != edge.fromNodeId) {
                metadata[BOUND_FROM_NODE_ID_KEY] = boundFromNodeId
            }
            if (boundToNodeId != edge.toNodeId) {
                metadata[BOUND_TO_NODE_ID_KEY] = boundToNodeId
            }

            edge.copy(
                bindingStatus = when {
                    boundFromNodeId != edge.fromNodeId && boundToNodeId != edge.toNodeId -> BindingStatus.BOUND
                    boundFromNodeId != edge.fromNodeId || boundToNodeId != edge.toNodeId -> BindingStatus.PARTIALLY_SYNCED
                    else -> edge.bindingStatus
                },
                metadata = metadata,
            )
        }

        // 返回绑定后的新图，并保留原始补丁信息。
        return GraphDocument(
            nodes = boundNodes,
            edges = boundEdges,
            patch = mermaidGraph.patch,
        )
    }

    /**
     * 根据节点元数据中的绑定标记修正绑定状态。
     */
    fun apply(node: GraphNode): GraphNode {
        // 读取持久化在元数据里的绑定标记，优先用于恢复状态。
        val marker = node.metadata[BINDING_KEY]?.trim()?.uppercase()
        val nextStatus = when (marker) {
            "MATCHED" -> BindingStatus.BOUND
            "UNMATCHED" -> BindingStatus.DESIGN_ONLY
            "MULTI_CANDIDATE", "CONFLICTED" -> BindingStatus.CONFLICTED
            null -> if (node.metadata[BOUND_NODE_ID_KEY].isNullOrBlank()) node.bindingStatus else BindingStatus.BOUND
            else -> node.bindingStatus
        }
        return node.copy(bindingStatus = nextStatus)
    }

    /**
     * 把绑定解析结果写回节点。
     */
    private fun apply(
        node: GraphNode,
        resolution: BindingResolution,
    ): GraphNode {
        // 复制旧元数据后增量写入绑定结果，避免覆盖其他扩展字段。
        val metadata = linkedMapOf<String, String>()
        metadata.putAll(node.metadata)
        metadata[BINDING_KEY] = resolution.marker
        resolution.boundNodeId?.let { metadata[BOUND_NODE_ID_KEY] = it }
        if (resolution.candidateIds.isNotEmpty()) {
            metadata[BINDING_CANDIDATES_KEY] = resolution.candidateIds.joinToString(",")
        }
        return node.copy(
            bindingStatus = resolution.status,
            metadata = metadata,
        )
    }

    /**
     * 为单个 Mermaid 节点解析最合适的绑定结果。
     */
    private fun resolveBinding(
        node: GraphNode,
        codeNodesById: Map<String, GraphNode>,
        lookupIndex: Map<String, List<GraphNode>>,
    ): BindingResolution {
        // 若节点已记录绑定目标且代码侧仍存在该目标，则直接视为已绑定。
        node.metadata[BOUND_NODE_ID_KEY]
            ?.takeIf { it.isNotBlank() }
            ?.trim()
            ?.let { boundId ->
                codeNodesById[boundId]?.let {
                    return BindingResolution(
                        status = BindingStatus.BOUND,
                        marker = "MATCHED",
                        boundNodeId = it.id,
                    )
                }
            }

        // 节点标识完全一致时也直接视为已绑定。
        codeNodesById[node.id.trim()]?.let {
            return BindingResolution(
                status = BindingStatus.BOUND,
                marker = "MATCHED",
                boundNodeId = it.id,
            )
        }

        // 按签名、路径、主题、标题等多种键依次查找候选代码节点。
        val candidates = firstNonEmpty(
            lookup(node.signature, lookupIndex),
            lookup(node.metadata["path"], lookupIndex),
            lookup(node.metadata["topic"], lookupIndex),
            lookup(node.metadata["key"], lookupIndex),
            lookup(node.metadata["statementId"], lookupIndex),
            lookup(node.location, lookupIndex),
            lookup(node.title, lookupIndex),
            normalizationService.lookupKeys(node).mapNotNull { lookupIndex[it] }.flatten().distinctBy { it.id },
        )

        // 单候选视为绑定成功，多候选视为冲突，无候选视为设计侧独有。
        return when {
            candidates.size == 1 -> BindingResolution(
                status = BindingStatus.BOUND,
                marker = "MATCHED",
                boundNodeId = candidates.single().id,
            )

            candidates.size > 1 -> BindingResolution(
                status = BindingStatus.CONFLICTED,
                marker = "MULTI_CANDIDATE",
                candidateIds = candidates.map { it.id }.sorted(),
            )

            else -> BindingResolution(
                status = BindingStatus.DESIGN_ONLY,
                marker = "UNMATCHED",
            )
        }
    }

    /**
     * 根据代码节点构建多键查找索引。
     */
    private fun buildLookupIndex(nodes: List<GraphNode>): Map<String, List<GraphNode>> {
        // 同一查找键可能命中多个节点，因此索引值使用列表保存。
        val index = linkedMapOf<String, MutableList<GraphNode>>()
        nodes.forEach { node ->
            normalizationService.lookupKeys(node).forEach { key ->
                index.getOrPut(key) { mutableListOf() } += node
            }
        }
        return index
    }

    /**
     * 按原始键从查找索引中获取候选节点。
     */
    private fun lookup(
        rawKey: String?,
        lookupIndex: Map<String, List<GraphNode>>,
    ): List<GraphNode> {
        // 查找键统一去空白、压缩空格并转小写，保证匹配规则稳定。
        val key = rawKey?.trim()?.replace(MULTI_WHITESPACE, " ")?.lowercase()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return lookupIndex[key].orEmpty()
    }

    /**
     * 返回第一组非空候选，并按节点标识去重。
     */
    private fun firstNonEmpty(vararg candidates: List<GraphNode>): List<GraphNode> {
        return candidates.firstOrNull { it.isNotEmpty() }?.distinctBy { it.id }.orEmpty()
    }

    /**
     * 表示单个节点的绑定解析结果。
     */
    private data class BindingResolution(
        /** 保存解析后的绑定状态。 */
        val status: BindingStatus,
        /** 保存持久化到元数据中的绑定标记。 */
        val marker: String,
        /** 保存绑定到的目标节点标识。 */
        val boundNodeId: String? = null,
        /** 保存候选节点标识列表。 */
        val candidateIds: List<String> = emptyList(),
    )

    companion object {
        /** 匹配连续空白字符，用于查找键规范化。 */
        private val MULTI_WHITESPACE = Regex("\\s+")

        /** 元数据中的绑定状态键。 */
        const val BINDING_KEY: String = "binding"
        /** 元数据中的绑定节点标识键。 */
        const val BOUND_NODE_ID_KEY: String = "boundNodeId"
        /** 元数据中的候选节点列表键。 */
        const val BINDING_CANDIDATES_KEY: String = "bindingCandidates"
        /** 元数据中的绑定起点节点键。 */
        const val BOUND_FROM_NODE_ID_KEY: String = "boundFromNodeId"
        /** 元数据中的绑定终点节点键。 */
        const val BOUND_TO_NODE_ID_KEY: String = "boundToNodeId"
    }
}
