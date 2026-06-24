package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.workbench.DraftWorkbenchState

/**
 * 计算在场景切换时应保留多少已确认草稿状态。
 *
 * 切换到另一个方法签名时，旧签名下的草稿不再适用，应当清空；
 * 同一签名内切换视图（如从事实图切到类图）时，草稿仍然适用，应当保留。
 * 任一签名为空都视为无法判断，清空草稿避免误用。
 */
internal fun preservedConfirmedDraftState(
    currentState: GraphEditorStateSnapshot,
    nextSelectedMethodSignature: String?,
): DraftWorkbenchState {
    // 当前没有任何已确认草稿：直接返回空状态
    if (currentState.draftWorkbenchState.draftChanges.isEmpty()) {
        return DraftWorkbenchState()
    }
    val currentSignature = currentState.selectedMethodSignature
    // 任一签名缺失都视为跨场景，清空草稿
    if (currentSignature.isNullOrBlank() || nextSelectedMethodSignature.isNullOrBlank()) {
        return DraftWorkbenchState()
    }
    // 签名相同才保留，否则切换到新方法时草稿不再适用
    return if (currentSignature == nextSelectedMethodSignature) currentState.draftWorkbenchState else DraftWorkbenchState()
}

/**
 * 把工作台草稿依次应用到基线图，得到最终的工作图。
 *
 * 跳过没有补丁的条目（视为无效），其余按列表顺序应用。
 * 顺序敏感：草稿之间可能有依赖，必须按用户确认顺序应用。
 */
internal fun reapplyConfirmedDraftGraph(
    baseGraph: GraphDocument,
    draftState: DraftWorkbenchState,
    graphPatchApplyService: GraphPatchApplyService,
): GraphDocument {
    return draftState.draftChanges.fold(baseGraph) { currentGraph, entry ->
        // 草稿条目没有补丁：保留当前图不变
        val patch = entry.graphPatch ?: return@fold currentGraph
        graphPatchApplyService.apply(currentGraph, patch)
    }
}
