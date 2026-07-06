package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import java.time.Instant
import java.util.UUID

/**
 * 调用展开目标类型的枚举。
 *
 * 描述被展开的调用节点所对应的实现来源所属的类别，便于在 UI 上区分展示与处理策略。
 */
enum class InvocationExpansionTargetKind {
    PROJECT_SOURCE,
    MULTIPLE_IMPLEMENTATIONS,
    NO_IMPLEMENTATION,
    EXTERNAL_JDK,
    EXTERNAL_LIBRARY,
    CROSS_SERVICE,
    NOT_FOUND,
}

/**
 * 调用展开目标信息。
 *
 * 表示一次调用展开所定位到的具体实现或失败原因，含目标签名、候选签名集合以及给用户的提示消息。
 */
data class InvocationExpansionTarget(
    val kind: InvocationExpansionTargetKind,
    val signature: String? = null,
    val candidateSignatures: List<String> = emptyList(),
    val message: String? = null,
)

/**
 * 调用展开合并操作的结果。
 *
 * 持有合并后的工作区图谱以及本次展开所生成的唯一标识，便于后续追踪与撤销。
 */
data class InvocationExpansionMergeResult(
    val graph: GraphDocument,
    val expansionId: String,
)

/**
 * 调用展开移除操作的结果。
 *
 * 包含移除展开后的图谱以及是否实际发生了移除动作的标志。
 */
data class InvocationExpansionRemovalResult(
    val graph: GraphDocument,
    val removed: Boolean,
)

/**
 * 调用展开用例。
 *
 * 封装对调用（INVOCATION）类型节点的展开与撤销行为：将目标实现对应的子图谱合并进当前工作区，
 * 并为新增节点/边打上统一的展开元数据标签以便后续整体移除。
 */
class InvocationExpansionUseCase(
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> String = { Instant.now().toString() },
) {
    /**
     * 调用节点校验结果枚举。
     *
     * 表示节点是否可作为展开的输入，以及不能展开时的具体原因。
     */
    enum class ValidationResult {
        READY,
        NOT_INVOCATION,
        MISSING_SIGNATURE,
    }

    /**
     * 校验给定节点是否可被作为调用展开的源节点。
     *
     * 仅接受类型为流动作且流种类为调用（INVOCATION）的节点，并要求其携带有效的签名信息。
     */
    fun validateInvocationNode(node: GraphNode): ValidationResult {
        if (node.type != NodeType.FLOW_ACTION || node.metadata["flow.kind"] != "INVOCATION") {
            return ValidationResult.NOT_INVOCATION
        }
        if (node.signature.isNullOrBlank()) {
            return ValidationResult.MISSING_SIGNATURE
        }
        return ValidationResult.READY
    }

    /**
     * 将目标子图谱合并到工作区中以完成一次调用展开。
     *
     * 为新增节点和边追加展开元数据，避免覆盖工作区已有内容；同时生成一条连接调用节点
     * 与目标入口节点的调用边，并返回携带新图谱与展开标识的结果。
     */
    fun mergeExpansion(
        workspace: GraphDocument,
        sourceInvocationNode: GraphNode,
        targetGraph: GraphDocument,
        targetEntryNodeId: String,
        targetSignature: String,
    ): InvocationExpansionMergeResult {
        val expansionId = "invocation:${idGenerator()}"
        val existingNodeIds = workspace.nodes.mapTo(linkedSetOf(), GraphNode::id)
        val existingEdgeIds = workspace.edges.mapTo(linkedSetOf(), GraphEdge::id)
        val expansionMetadata = expansionMetadata(
            expansionId = expansionId,
            rootNodeId = targetEntryNodeId,
            sourceInvocationNodeId = sourceInvocationNode.id,
            targetSignature = targetSignature,
        )
        val nodesById = LinkedHashMap(workspace.nodes.associateBy(GraphNode::id))
        targetGraph.nodes.forEach { node ->
            if (node.id !in existingNodeIds) {
                nodesById[node.id] = node.copy(metadata = node.metadata + expansionMetadata)
            }
        }
        val edgesById = LinkedHashMap(workspace.edges.associateBy(GraphEdge::id))
        targetGraph.edges.forEach { edge ->
            if (edge.id !in existingEdgeIds) {
                edgesById[edge.id] = edge.copy(metadata = edge.metadata + expansionMetadata)
            }
        }
        val connector = GraphEdge(
            id = GraphEdge.stableId(EdgeType.CALL, sourceInvocationNode.id, targetEntryNodeId, expansionId),
            type = EdgeType.CALL,
            fromNodeId = sourceInvocationNode.id,
            toNodeId = targetEntryNodeId,
            label = "展开调用",
            metadata = expansionMetadata,
        )
        edgesById.putIfAbsent(connector.id, connector)
        return InvocationExpansionMergeResult(
            graph = GraphDocument(
                nodes = nodesById.values.sortedBy(GraphNode::id),
                edges = edgesById.values.sortedBy(GraphEdge::id),
                patch = workspace.patch,
            ),
            expansionId = expansionId,
        )
    }

    /**
     * 根据展开标识撤销一次调用展开。
     *
     * 移除所有由该展开生成并打上元数据标签的节点和边，以及任何引用了被移除节点的边；
     * 若图谱中没有任何对应内容则返回未发生移除的结果。
     */
    fun removeExpansion(
        graph: GraphDocument,
        expansionId: String,
    ): InvocationExpansionRemovalResult {
        val expansionRegistry = buildExpansionRemovalRegistry(graph)
        if (expansionId !in expansionRegistry.entriesById) {
            return InvocationExpansionRemovalResult(graph = graph, removed = false)
        }
        val removalExpansionIds = linkedSetOf<String>()
        fun collect(expansionId: String) {
            if (!removalExpansionIds.add(expansionId)) {
                return
            }
            expansionRegistry.entriesById[expansionId]?.childExpansionIds.orEmpty().forEach(::collect)
        }
        collect(expansionId)

        val removedNodeIds = graph.nodes
            .filter { node -> node.metadata[EXPANSION_ID] in removalExpansionIds }
            .mapTo(linkedSetOf(), GraphNode::id)
        val removedEdgeIds = graph.edges
            .filter { edge -> edge.metadata[EXPANSION_ID] in removalExpansionIds }
            .mapTo(linkedSetOf(), GraphEdge::id)
        if (removedNodeIds.isEmpty() && removedEdgeIds.isEmpty()) {
            return InvocationExpansionRemovalResult(graph = graph, removed = false)
        }
        return InvocationExpansionRemovalResult(
            graph = GraphDocument(
                nodes = graph.nodes.filterNot { node -> node.id in removedNodeIds },
                edges = graph.edges.filterNot { edge ->
                    edge.id in removedEdgeIds ||
                        edge.fromNodeId in removedNodeIds ||
                        edge.toNodeId in removedNodeIds
                },
                patch = graph.patch,
            ),
            removed = true,
        )
    }

    private data class ExpansionRemovalEntry(
        val expansionId: String,
        val sourceInvocationNodeId: String?,
        val ownedNodeIds: Set<String>,
        val childExpansionIds: List<String>,
    )

    private data class ExpansionRemovalRegistry(
        val entriesById: Map<String, ExpansionRemovalEntry>,
    )

    private data class ExpansionRemovalDraft(
        val expansionId: String,
        val taggedNodes: MutableList<GraphNode> = mutableListOf(),
        val taggedEdges: MutableList<GraphEdge> = mutableListOf(),
        val ownedNodeIds: MutableSet<String> = linkedSetOf(),
    )

    private fun buildExpansionRemovalRegistry(graph: GraphDocument): ExpansionRemovalRegistry {
        val draftsById = linkedMapOf<String, ExpansionRemovalDraft>()
        fun draftFor(expansionId: String): ExpansionRemovalDraft =
            draftsById.getOrPut(expansionId) { ExpansionRemovalDraft(expansionId) }

        graph.nodes.forEach { node ->
            val expansionId = node.metadata[EXPANSION_ID]?.takeIf(String::isNotBlank) ?: return@forEach
            draftFor(expansionId).also { draft ->
                draft.taggedNodes += node
                draft.ownedNodeIds += node.id
            }
        }
        graph.edges.forEach { edge ->
            val expansionId = edge.metadata[EXPANSION_ID]?.takeIf(String::isNotBlank) ?: return@forEach
            draftFor(expansionId).taggedEdges += edge
        }

        val ownerExpansionIdByNodeId = linkedMapOf<String, String>()
        draftsById.values.forEach { draft ->
            draft.ownedNodeIds.forEach { nodeId -> ownerExpansionIdByNodeId[nodeId] = draft.expansionId }
        }
        val childExpansionIdsByParentId = linkedMapOf<String, MutableList<String>>()
        val parentExpansionIdByExpansionId = linkedMapOf<String, String?>()
        draftsById.values.forEach { draft ->
            val sourceInvocationNodeId = firstExpansionMetadataValue(draft, EXPANSION_SOURCE_INVOCATION_NODE_ID)
            val parentExpansionId = sourceInvocationNodeId?.let(ownerExpansionIdByNodeId::get)
                ?.takeUnless { parentExpansionId -> parentExpansionId == draft.expansionId }
            parentExpansionIdByExpansionId[draft.expansionId] = parentExpansionId
            if (parentExpansionId != null) {
                childExpansionIdsByParentId.getOrPut(parentExpansionId) { mutableListOf() } += draft.expansionId
            }
        }

        return ExpansionRemovalRegistry(
            entriesById = draftsById.values.associate { draft ->
                draft.expansionId to ExpansionRemovalEntry(
                    expansionId = draft.expansionId,
                    sourceInvocationNodeId = firstExpansionMetadataValue(draft, EXPANSION_SOURCE_INVOCATION_NODE_ID),
                    ownedNodeIds = draft.ownedNodeIds.toSet(),
                    childExpansionIds = childExpansionIdsByParentId[draft.expansionId].orEmpty(),
                )
            },
        )
    }

    private fun firstExpansionMetadataValue(
        draft: ExpansionRemovalDraft,
        key: String,
    ): String? =
        draft.taggedNodes.asSequence()
            .mapNotNull { node -> node.metadata[key]?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: draft.taggedEdges.asSequence()
                .mapNotNull { edge -> edge.metadata[key]?.takeIf(String::isNotBlank) }
                .firstOrNull()

    /**
     * 构造单次展开所用的元数据标签映射。
     *
     * 将展开标识、根节点、源调用节点、目标签名、创建时间与展开种类等信息组装为统一的元数据集合，
     * 用于标记本次展开所新增的节点和边。
     */
    private fun expansionMetadata(
        expansionId: String,
        rootNodeId: String,
        sourceInvocationNodeId: String,
        targetSignature: String,
    ): Map<String, String> = mapOf(
        EXPANSION_ID to expansionId,
        EXPANSION_ROOT_NODE_ID to rootNodeId,
        EXPANSION_SOURCE_INVOCATION_NODE_ID to sourceInvocationNodeId,
        EXPANSION_TARGET_SIGNATURE to targetSignature,
        EXPANSION_CREATED_AT to clock(),
        EXPANSION_KIND to EXPANSION_KIND_INVOCATION,
    )

    /**
     * 展开元数据相关的常量定义集合。
     */
    companion object {
        const val EXPANSION_ID = "linkGraph.expansion.id"
        const val EXPANSION_ROOT_NODE_ID = "linkGraph.expansion.rootNodeId"
        const val EXPANSION_SOURCE_INVOCATION_NODE_ID = "linkGraph.expansion.sourceInvocationNodeId"
        const val EXPANSION_TARGET_SIGNATURE = "linkGraph.expansion.targetSignature"
        const val EXPANSION_CREATED_AT = "linkGraph.expansion.createdAt"
        const val EXPANSION_KIND = "linkGraph.expansion.kind"
        const val EXPANSION_KIND_INVOCATION = "INVOCATION"
    }
}
