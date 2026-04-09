package com.charmnight.linkgraph.ui

/**
 * 表示草稿补丁应用完成后的结果摘要。
 */
data class DraftPatchApplyResult(
    /** 保存本次应用结果的摘要文案。 */
    val summary: String,
    /** 保存成功应用的操作数量。 */
    val appliedOperationCount: Int,
    /** 保存本次应用涉及的节点标识列表。 */
    val appliedNodeIds: List<String> = emptyList(),
    /** 保存本次应用涉及的边标识列表。 */
    val appliedEdgeIds: List<String> = emptyList(),
    /** 保存界面需要聚焦的节点标识。 */
    val focusNodeId: String? = null,
    /** 保存本次应用影响的目标列表。 */
    val appliedTargets: List<String> = emptyList(),
)
