package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.workbench.DraftWorkbenchState

/**
 * 应用层状态快照：把所有需要持久化或跨层传递的状态字段打包为不可变数据。
 *
 * 快照化的目的是让上层（UI、桥接层）拿到一份一致的状态视图，
 * 而不是分别读取多个可变字段。任何状态变更都通过"旧快照 → 新快照"的形式发生。
 */
data class ApplicationSnapshot(
    /** 工作台基线图（不含草稿补丁）。 */
    val workspaceBaseGraph: GraphDocument = GraphDocument(),
    /** 当前工作台图（基线 + 已应用草稿）。 */
    val workspaceGraph: GraphDocument = GraphDocument(),
    /** 当前选中的方法签名；用于跨场景保留选中上下文。 */
    val selectedMethodSignature: String? = null,
    /** 草稿工作台状态。 */
    val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    /** 待应用的草稿补丁预览。 */
    val draftPatchPreview: GraphPatch? = null,
    /** 草稿补丁撤销栈。 */
    val draftPatchUndo: DraftPatchUndo? = null,
    /** 最近一次 QA 结果。 */
    val qaResult: GraphPatchResult? = null,
    /** 最近一次差异审查结果。 */
    val diffReviewResult: GraphPatchResult? = null,
)

/**
 * 把应用快照投影为风险化解快照。
 * 风险化解流程只关心草稿状态与 QA 结果，所以只取这两个字段。
 */
fun ApplicationSnapshot.toRiskResolutionSnapshot(): RiskResolutionSnapshot {
    return RiskResolutionSnapshot(
        draftWorkbenchState = draftWorkbenchState,
        qaResult = qaResult,
    )
}

/**
 * 草稿补丁撤销信息：记录"应用前的图"和"被应用的预览补丁"，
 * 让用户可以撤销最近一次应用。
 */
data class DraftPatchUndo(
    /** 应用前的图文档，撤销时恢复到此状态。 */
    val graphBeforeApply: GraphDocument,
    /** 被应用的预览补丁；为空表示补丁已丢弃。 */
    val patchPreview: GraphPatch? = null,
)
