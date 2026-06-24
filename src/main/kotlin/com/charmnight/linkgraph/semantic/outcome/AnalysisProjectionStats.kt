package com.charmnight.linkgraph.semantic.outcome

/**
 * 记录分析结果投影阶段的统计信息。
 *
 * 投影过程会把完整的语义结果裁剪到人类可读的规模（节点/边数量限制），
 * 本结构记录这个过程中被裁掉的内容数量与是否触发截断，
 * 让 UI 可以提示用户"当前视图省略了 N 个节点"。
 */
data class AnalysisProjectionStats(
    /** 记录被隐藏的节点数量。 */
    val hiddenNodeCount: Int = 0,
    /** 记录被隐藏的边数量。 */
    val hiddenEdgeCount: Int = 0,
    /** 标记结果是否因为预算限制而被截断。true 表示还有更多内容被裁掉。 */
    val truncated: Boolean = false,
)
