package com.charmnight.linkgraph.agent.tools

/**
 * 工具调用结果中单个节点的投影映射信息。
 *
 * @property projectedNodeId 投影后的节点 ID（工具输出端使用的 ID）
 * @property canonicalNodeIds 该投影节点对应的规范化节点 ID 列表；多个规范 ID 可能在投影后合并为一个节点
 */
data class ToolGraphProjectionNodeMapping(
    val projectedNodeId: String,
    val canonicalNodeIds: List<String> = emptyList(),
)

/**
 * 工具调用结果中图投影的索引信息。
 *
 * 工具产出的图可能使用与主图不同的节点 ID 体系（投影 ID），
 * 通过本索引可以反查投影 ID 对应的规范化节点 ID，
 * 让上游把工具结果与主图对齐。
 */
data class ToolGraphProjectionIndex(
    /** 投影节点 ID 到映射信息的全集。 */
    val nodeMappings: Map<String, ToolGraphProjectionNodeMapping> = emptyMap(),
) {
    /**
     * 按投影节点 ID 查询映射信息。
     *
     * @param nodeId 投影节点 ID
     * @return 映射信息；不存在时返回 null
     */
    fun nodeMapping(nodeId: String): ToolGraphProjectionNodeMapping? = nodeMappings[nodeId]

    companion object {
        /** 空索引的单例，避免重复构造。 */
        val EMPTY = ToolGraphProjectionIndex()
    }
}
