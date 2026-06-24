package com.charmnight.linkgraph.application.edit

/**
 * 图编辑"投影 ↔ 规范"映射表。
 *
 * 投影过程会把多个规范节点合并为一个投影节点（或反向展开），
 * 因此"对投影节点的编辑"可能要落到一个或多个规范节点上。本类封装这种映射，
 * 让上层只关心投影节点，由本类做"投影 ID → 规范 ID 列表"的翻译。
 *
 * 四种映射都使用 Map 而非函数，便于序列化与单元测试。
 */
data class GraphEditResolution(
    /** 投影节点 ID → 单个规范节点 ID（用于更新类操作）。 */
    private val nodeTargetIds: Map<String, String> = emptyMap(),
    /** 投影节点 ID → 多个规范节点 ID 集合（用于删除类操作，可能涉及被合并的多个节点）。 */
    private val nodeRemovalIds: Map<String, Set<String>> = emptyMap(),
    /** 投影边 ID → 单个规范边 ID。 */
    private val edgeTargetIds: Map<String, String> = emptyMap(),
    /** 投影边 ID → 多个规范边 ID 集合。 */
    private val edgeRemovalIds: Map<String, Set<String>> = emptyMap(),
) {
    /** 取投影节点对应的单个目标规范节点 ID；无映射时原样返回。 */
    fun nodeTargetId(projectedNodeId: String): String = nodeTargetIds[projectedNodeId] ?: projectedNodeId

    /** 取投影节点对应的全部规范节点 ID（用于删除）；无映射时返回单元素集合。 */
    fun nodeRemovalIds(projectedNodeId: String): Set<String> = nodeRemovalIds[projectedNodeId] ?: setOf(projectedNodeId)

    /** 取投影边对应的单个目标规范边 ID；无映射时原样返回。 */
    fun edgeTargetId(projectedEdgeId: String): String = edgeTargetIds[projectedEdgeId] ?: projectedEdgeId

    /** 取投影边对应的全部规范边 ID（用于删除）；无映射时返回单元素集合。 */
    fun edgeRemovalIds(projectedEdgeId: String): Set<String> = edgeRemovalIds[projectedEdgeId] ?: setOf(projectedEdgeId)

    /**
     * 构造映射表的 Builder。
     * 用链式 add 方式填充映射，最后 build 出不可变实例。
     */
    class Builder {
        private val nodeTargetIds = linkedMapOf<String, String>()
        private val nodeRemovalIds = linkedMapOf<String, Set<String>>()
        private val edgeTargetIds = linkedMapOf<String, String>()
        private val edgeRemovalIds = linkedMapOf<String, Set<String>>()

        /** 登记投影节点 → 规范节点的更新目标。 */
        fun nodeTargetId(projectedNodeId: String, canonicalNodeId: String) {
            nodeTargetIds[projectedNodeId] = canonicalNodeId
        }

        /** 登记投影节点 → 多个规范节点的删除目标。 */
        fun nodeRemovalIds(projectedNodeId: String, canonicalNodeIds: Set<String>) {
            nodeRemovalIds[projectedNodeId] = canonicalNodeIds
        }

        /** 登记投影边 → 规范边的更新目标。 */
        fun edgeTargetId(projectedEdgeId: String, canonicalEdgeId: String) {
            edgeTargetIds[projectedEdgeId] = canonicalEdgeId
        }

        /** 登记投影边 → 多个规范边的删除目标。 */
        fun edgeRemovalIds(projectedEdgeId: String, canonicalEdgeIds: Set<String>) {
            edgeRemovalIds[projectedEdgeId] = canonicalEdgeIds
        }

        /** 构造不可变映射表。 */
        fun build(): GraphEditResolution =
            GraphEditResolution(
                nodeTargetIds = nodeTargetIds.toMap(),
                nodeRemovalIds = nodeRemovalIds.toMap(),
                edgeTargetIds = edgeTargetIds.toMap(),
                edgeRemovalIds = edgeRemovalIds.toMap(),
            )
    }

    companion object {
        /** 恒等映射：所有投影 ID 直接对应自身规范 ID。 */
        fun identity(): GraphEditResolution = GraphEditResolution()
    }
}

/**
 * 图编辑权限判定结果。
 *
 * - [issues] 列出所有阻止编辑的问题（例如节点不存在、超出 edit scope）；
 * - [resolution] 是通过校验后的映射表，调用方据此把投影编辑翻译为规范编辑。
 */
data class GraphEditPermissionDecision(
    val issues: List<com.charmnight.linkgraph.application.model.GraphEditIssue>,
    val resolution: GraphEditResolution,
)
