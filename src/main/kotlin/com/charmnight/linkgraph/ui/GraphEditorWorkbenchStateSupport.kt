package com.charmnight.linkgraph.ui
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel

import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

/**
 * 工作台状态变更辅助类。
 *
 * 把各种"工作台事件"封装为对统一快照的修改动作，
 * 让上层调用方不必直接关心如何安全地更新快照，只需描述意图即可。
 */
internal class GraphEditorWorkbenchStateSupport(
    /** 用于把"旧快照 -> 新快照"的转换函数应用回外层存储。 */
    private val mutate: ((GraphEditorStateSnapshot) -> GraphEditorStateSnapshot) -> Unit,
) {
    /**
     * 标记当前存在一份待预览的草稿补丁。
     */
    fun markDraftPatchPreview(patch: GraphPatch) {
        mutate { currentState -> currentState.withDraftPatchPreview(patch) }
    }

    /**
     * 更新草稿工作台状态，可选择是否推进草稿版本号。
     */
    fun markDraftWorkbenchState(
        state: DraftWorkbenchState,
        advanceDraftVersion: Boolean = true,
    ) {
        mutate { currentState ->
            currentState.withDraftWorkbenchState(
                state = state,
                advanceDraftVersion = advanceDraftVersion,
            )
        }
    }

    /**
     * 清除当前的草稿补丁预览。
     */
    fun clearDraftPatchPreview() {
        mutate {
            it.copy(
                draftPatchPreview = null,
                lastMessageType = "draftPatchPreviewCleared",
            )
        }
    }

    /**
     * 记录"草稿补丁已经应用、可撤销"的回退信息。
     *
     * 保存应用前的图以及（可选）补丁预览，便于撤销时还原。
     */
    fun markDraftPatchApplyUndo(
        graphBeforeApply: com.charmnight.linkgraph.model.GraphDocument,
        patchPreview: GraphPatch? = null,
        summary: String? = null,
    ) {
        mutate {
            it.copy(
                draftPatchUndoState = DraftPatchUndoState(
                    graphBeforeApply = graphBeforeApply,
                    patchPreview = patchPreview,
                ),
                lastMessageType = "draftPatchApplyUndo",
            )
        }
    }

    /**
     * 清除"草稿补丁应用撤销"相关的回退状态。
     */
    fun clearDraftPatchApplyUndo() {
        mutate {
            it.copy(
                draftPatchUndoState = null,
                lastMessageType = "draftPatchApplyUndoCleared",
            )
        }
    }

    /**
     * 记录最近一次草稿补丁应用结果。
     */
    fun markDraftPatchApplyResult(result: DraftPatchApplyResult) {
        mutate {
            it.copy(
                lastDraftPatchApplyResult = result,
                lastMessageType = "draftPatchApplied",
            )
        }
    }

    /**
     * 更新草稿校验状态（传 null 表示清除）。
     */
    fun markDraftValidationState(state: DraftValidationState?) {
        mutate {
            it.withDraftValidationState(state)
        }
    }

    /**
     * 更新代码生成阶段的能力判定结果（传 null 表示清除）。
     */
    fun markCodeEligibilityDecision(decision: StageEligibilityDecision?) {
        mutate {
            it.withCodeEligibilityDecision(decision)
        }
    }

    /**
     * 在指定场景下追加运行时产物摘要。
     */
    fun markRuntimeArtifactSummaries(
        scene: String,
        summaries: List<RuntimeArtifactSummary>,
    ) {
        mutate {
            it.copy(
                runtimeArtifactSummaries = it.runtimeArtifactSummaries + (scene to summaries),
                lastMessageType = "runtimeArtifacts",
            )
        }
    }

    /**
     * 触发一次同步预览请求，把待比较的项写入状态。
     */
    fun requestSyncPreview(items: List<SyncPreviewItem>) {
        mutate {
            it.copy(
                syncPreviewItems = items,
                syncPreviewRequested = true,
                lastMessageType = "requestSyncPreview",
            )
        }
    }

    /**
     * 记录最近一次生成的代码草稿写入报告。
     */
    fun markGeneratedCodeDraftWriteReport(report: GeneratedCodeDraftWriteReport) {
        mutate {
            it.copy(
                generatedCodeDraftWriteReport = report,
                lastMessageType = "applyCodeDrafts",
            )
        }
    }

    /**
     * 记录一次操作反馈消息，可选择保留此前状态种类，避免覆盖更有意义的最近状态标识。
     */
    fun markOperationFeedback(
        level: ApplicationFeedbackLevel,
        message: String,
        preservePreviousStatusKind: Boolean = false,
    ) {
        mutate {
            it.copy(
                operationFeedback = OperationFeedback(level = level, message = message),
                lastMessageType = if (preservePreviousStatusKind) {
                    it.lastMessageType
                } else {
                    "operationFeedback"
                },
            )
        }
    }

}
