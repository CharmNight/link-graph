package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.GraphEditIssue
import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.workflow.FrontendGraphMutationSanitizer
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode

/**
 * 图编辑应用器。
 *
 * 把通过权限校验的编辑请求实际应用到工作图上。
 * 应用过程会：
 * - 用 [GraphEditResolution] 把投影 ID 翻译为规范 ID；
 * - 用 [FrontendGraphMutationSanitizer] 消毒新内容；
 * - 维护节点与边的可变映射，最后产出新图。
 *
 * 若 sanitizer 对某次 upsert 返回空节点列表，视为拒绝写入该 operation，
 * 在 [GraphEditApplierResult.issues] 中追加 [GraphEditIssueCode.SANITIZER_REJECTED_NODE]，
 * 不会把未消毒的原始节点落入图。
 */
internal class GraphEditApplier(
    private val frontendGraphMutationSanitizer: FrontendGraphMutationSanitizer = FrontendGraphMutationSanitizer(),
) {
    /**
     * 应用编辑请求。
     *
     * @param snapshot 当前快照，提供工作图与可信节点
     * @param request 编辑请求
     * @param resolution ID 映射表
     * @return 应用后的新图及 sanitizer 拒绝时产生的问题列表
     */
    fun apply(
        snapshot: WorkflowEditorSnapshot,
        request: GraphEditRequest,
        resolution: GraphEditResolution,
    ): GraphEditApplierResult {
        val workingGraph = snapshot.workspaceGraph
        val trustedNodes = snapshot.trustedNavigationNodes
        // 用 LinkedHashMap 保留插入顺序，便于多次刷新结果稳定
        val nodesById = LinkedHashMap(workingGraph.nodes.associateBy(GraphNode::id))
        val edgesById = LinkedHashMap(workingGraph.edges.associateBy(GraphEdge::id))
        val issues = mutableListOf<GraphEditIssue>()

        request.operations.forEachIndexed { index, operation ->
            when (operation) {
                is GraphEditOperation.UpsertNode -> {
                    // 把投影 ID 翻译为规范 ID
                    val targetNodeId = resolution.nodeTargetId(operation.node.id)
                    // 消毒：剥离不可信字段，保留前端可写字段
                    val sanitizedNodes = frontendGraphMutationSanitizer.sanitize(
                        snapshot,
                        GraphDocument(nodes = listOf(operation.node.copy(id = targetNodeId))),
                    ).nodes
                    if (sanitizedNodes.isEmpty()) {
                        // sanitizer 返回空列表视为拒绝写入该节点：不要 fallback 到原始节点，
                        // 否则前端不可信的 location/signature 等字段会绕过消毒直接落库。
                        issues += GraphEditIssue(
                            code = GraphEditIssueCode.SANITIZER_REJECTED_NODE,
                            message = "节点 ${operation.node.id} 被消毒器拒绝，未写入工作图。",
                            operationIndex = index,
                            targetId = operation.node.id,
                            retryable = false,
                        )
                        return@forEachIndexed
                    }
                    val sanitizedNode = sanitizedNodes.first()
                    // 若该节点在可信索引中，保留可信字段，只更新前端可改字段
                    val existingTrustedNode = trustedNodes[targetNodeId]
                    nodesById[targetNodeId] = existingTrustedNode?.copy(
                        title = sanitizedNode.title,
                        inputs = sanitizedNode.inputs,
                        outputs = sanitizedNode.outputs,
                        doc = sanitizedNode.doc,
                        // 元数据做 merge：trusted 侧的"受保护前缀"（source./jvm./hash./signature. 等）
                        // 永远以前端为非覆盖 —— 即便前端 metadata 携带了这些 key 也忽略，避免不可信字段
                        // 覆盖 PSI/索引得到的可信值（旧实现直接 `trusted + sanitized` 会让前端 signature=evil
                        // 覆盖可信签名）。
                        metadata = mergeTrustedMetadata(existingTrustedNode.metadata, sanitizedNode.metadata),
                    ) ?: sanitizedNode.copy(id = targetNodeId)
                }
                is GraphEditOperation.RemoveNode -> {
                    // 删除可能涉及多个规范节点（投影合并的情况）
                    val nodeIds = resolution.nodeRemovalIds(operation.nodeId)
                    nodeIds.forEach(nodesById::remove)
                    // 同时移除端点指向已删除节点的边
                    edgesById.entries.removeIf { (_, edge) -> edge.fromNodeId in nodeIds || edge.toNodeId in nodeIds }
                }
                is GraphEditOperation.UpsertEdge -> {
                    // 边的端点 ID 同样需要翻译
                    val targetEdgeId = resolution.edgeTargetId(operation.edge.id)
                    edgesById[targetEdgeId] = operation.edge.copy(
                        id = targetEdgeId,
                        fromNodeId = resolution.nodeTargetId(operation.edge.fromNodeId),
                        toNodeId = resolution.nodeTargetId(operation.edge.toNodeId),
                    )
                }
                is GraphEditOperation.RemoveEdge -> {
                    resolution.edgeRemovalIds(operation.edgeId).forEach(edgesById::remove)
                }
            }
        }

        val updatedGraph = workingGraph.copy(
            nodes = nodesById.values.toList(),
            edges = edgesById.values.toList(),
            // patch 不由编辑请求修改，保留原值
            patch = workingGraph.patch,
        )
        return GraphEditApplierResult(graph = updatedGraph, issues = issues)
    }
}

/**
 * 图编辑应用结果。
 *
 * @property graph 应用后的新图；若 sanitizer 拒绝了某些 operation，这些节点不会出现在图中。
 * @property issues sanitizer 在应用过程中产生的拒绝问题；空列表代表无拒绝。
 */
internal data class GraphEditApplierResult(
    val graph: GraphDocument,
    val issues: List<GraphEditIssue>,
)

/**
 * 受可信侧保护的元数据前缀：前端 metadata 不得覆盖这些前缀下的任何 key。
 *
 * 涵盖由后端 PSI/索引填充的源码定位、JVM 元信息、内容指纹与签名前缀，避免前端字段污染可信数据。
 */
private val TRUSTED_METADATA_PREFIXES = setOf(
    "source.",
    "jvm.",
    "hash.",
    "signature.",
)

/**
 * 受可信侧保护的精确 metadata key（无前缀的独立字段）。
 *
 * `signature` 虽然在 GraphNode 上是独立字段，但 mermaid 序列化等路径会同时把它写入 metadata，
 * 因此这里把裸 key 也保护起来，防止前端 metadata 覆盖。
 */
private val TRUSTED_METADATA_EXACT_KEYS = setOf(
    "signature",
)

/**
 * 合并 trusted + 前端 metadata：trusted 全保留，前端只允许写入非受保护前缀/精确 key 的字段。
 */
private fun mergeTrustedMetadata(
    trusted: Map<String, String>,
    frontend: Map<String, String>,
): Map<String, String> {
    val filtered = frontend.filterKeys { key ->
        key !in TRUSTED_METADATA_EXACT_KEYS &&
            TRUSTED_METADATA_PREFIXES.none { prefix -> key.startsWith(prefix) }
    }
    return trusted + filtered
}
