package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.usecase.ApplyDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.ClearDraftPatchPreviewUseCaseResult
import com.charmnight.linkgraph.application.usecase.DraftPatchApplySummary
import com.charmnight.linkgraph.application.usecase.PreviewDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.RestoreDraftPatchPreviewUseCaseResult
import com.charmnight.linkgraph.application.usecase.UndoDraftPatchApplyUseCaseResult

class DraftPatchStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    fun presentPreview(result: PreviewDraftPatchUseCaseResult) {
        when (result) {
            is PreviewDraftPatchUseCaseResult.Previewed -> {
                stateService.workbench.markDraftPatchPreview(result.patch)
                stateService.workbench.markOperationFeedback(
                    OperationFeedbackLevel.SUCCESS,
                    result.patch.summary ?: "已生成草稿 patch 预览。",
                )
                requestBrowserSync()
            }
        }
    }

    fun presentApply(result: ApplyDraftPatchUseCaseResult) {
        when (result) {
            ApplyDraftPatchUseCaseResult.MissingPreview -> Unit
            is ApplyDraftPatchUseCaseResult.Applied -> {
                stateService.workbench.markDraftPatchApplyUndo(result.graphBeforeApply, result.patch)
                stateService.graph.markGraphChanged(
                    graph = result.graph,
                    preserveDraftPatchUndo = true,
                )
                stateService.workbench.clearDraftPatchPreview()
                stateService.workbench.markOperationFeedback(
                    OperationFeedbackLevel.SUCCESS,
                    "已将草稿 patch 应用到当前工作图。",
                )
                stateService.workbench.markDraftPatchApplyResult(result.applyResult.toUiResult())
                requestBrowserSync()
            }
        }
    }

    fun presentClear(result: ClearDraftPatchPreviewUseCaseResult) {
        when (result) {
            ClearDraftPatchPreviewUseCaseResult.MissingPreview -> {
                stateService.workbench.markOperationFeedback(
                    OperationFeedbackLevel.WARNING,
                    "当前没有可清空的草稿预览。",
                )
                requestBrowserSync()
            }
            ClearDraftPatchPreviewUseCaseResult.Cleared -> {
                stateService.workbench.clearDraftPatchPreview()
                stateService.workbench.markOperationFeedback(
                    OperationFeedbackLevel.INFO,
                    "已清空当前草稿预览。",
                )
                requestBrowserSync()
            }
        }
    }

    fun presentRestore(result: RestoreDraftPatchPreviewUseCaseResult) {
        when (result) {
            RestoreDraftPatchPreviewUseCaseResult.MissingPreview -> {
                stateService.workbench.markOperationFeedback(
                    OperationFeedbackLevel.WARNING,
                    "当前没有可恢复的草稿预览。",
                )
                requestBrowserSync()
            }
            is RestoreDraftPatchPreviewUseCaseResult.Restored -> {
                stateService.workbench.markDraftPatchPreview(result.patch)
                stateService.workbench.markOperationFeedback(
                    OperationFeedbackLevel.INFO,
                    "已恢复草稿预览。",
                )
                requestBrowserSync()
            }
        }
    }

    fun presentUndo(result: UndoDraftPatchApplyUseCaseResult) {
        when (result) {
            UndoDraftPatchApplyUseCaseResult.MissingUndo -> {
                stateService.workbench.markOperationFeedback(
                    OperationFeedbackLevel.WARNING,
                    "当前没有可撤销的草稿写回。",
                )
                requestBrowserSync()
            }
            is UndoDraftPatchApplyUseCaseResult.Undone -> {
                stateService.graph.markGraphChanged(result.graph)
                stateService.workbench.clearDraftPatchApplyUndo()
                result.patchPreview?.let(stateService.workbench::markDraftPatchPreview)
                stateService.workbench.markOperationFeedback(
                    OperationFeedbackLevel.SUCCESS,
                    "已撤销上次草稿写回，并恢复应用前工作图。",
                )
                stateService.graph.markLastMessageType("undoDraftPatchApply")
                requestBrowserSync()
            }
        }
    }

    private fun DraftPatchApplySummary.toUiResult(): DraftPatchApplyResult {
        return DraftPatchApplyResult(
            summary = summary,
            appliedOperationCount = appliedOperationCount,
            appliedNodeIds = appliedNodeIds,
            appliedEdgeIds = appliedEdgeIds,
            focusNodeId = focusNodeId,
            appliedTargets = appliedTargets,
        )
    }
}
