package com.charmnight.linkgraph.ui
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel

import com.charmnight.linkgraph.application.usecase.ApplyDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.ClearDraftPatchPreviewUseCaseResult
import com.charmnight.linkgraph.application.usecase.DraftPatchApplySummary
import com.charmnight.linkgraph.application.usecase.PreviewDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.RestoreDraftPatchPreviewUseCaseResult
import com.charmnight.linkgraph.application.usecase.UndoDraftPatchApplyUseCaseResult

/**
 * 草稿补丁状态展示器。
 *
 * 负责把用例（UseCase）返回的草稿补丁结果翻译为前端工作台可消费的状态变更：
 * 包括预览、应用、清空、恢复以及撤销。每次变更后会触发浏览器端同步回调，
 * 使 React 前端及时获得最新状态。
 */
class DraftPatchStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    /** 处理预览用例结果：把生成的草稿补丁标记为当前预览，并反馈操作成功消息。 */
    fun presentPreview(result: PreviewDraftPatchUseCaseResult) {
        when (result) {
            is PreviewDraftPatchUseCaseResult.Previewed -> {
                stateService.workbench.markDraftPatchPreview(result.patch)
                stateService.workbench.markOperationFeedback(
                    ApplicationFeedbackLevel.SUCCESS,
                    result.patch.summary ?: "已生成草稿 patch 预览。",
                )
                requestBrowserSync()
            }
        }
    }

    /** 处理应用用例结果：保存可撤销快照，更新当前工作图并清理预览。 */
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
                    ApplicationFeedbackLevel.SUCCESS,
                    "已将草稿 patch 应用到当前工作图。",
                )
                stateService.workbench.markDraftPatchApplyResult(result.applyResult.toUiResult())
                requestBrowserSync()
            }
        }
    }

    /** 处理清空预览用例结果：无预览时给出警告，已清空时给出提示。 */
    fun presentClear(result: ClearDraftPatchPreviewUseCaseResult) {
        when (result) {
            ClearDraftPatchPreviewUseCaseResult.MissingPreview -> {
                stateService.workbench.markOperationFeedback(
                    ApplicationFeedbackLevel.WARNING,
                    "当前没有可清空的草稿预览。",
                )
                requestBrowserSync()
            }
            ClearDraftPatchPreviewUseCaseResult.Cleared -> {
                stateService.workbench.clearDraftPatchPreview()
                stateService.workbench.markOperationFeedback(
                    ApplicationFeedbackLevel.INFO,
                    "已清空当前草稿预览。",
                )
                requestBrowserSync()
            }
        }
    }

    /** 处理恢复预览用例结果：把保留的草稿补丁重新挂回工作台作为当前预览。 */
    fun presentRestore(result: RestoreDraftPatchPreviewUseCaseResult) {
        when (result) {
            RestoreDraftPatchPreviewUseCaseResult.MissingPreview -> {
                stateService.workbench.markOperationFeedback(
                    ApplicationFeedbackLevel.WARNING,
                    "当前没有可恢复的草稿预览。",
                )
                requestBrowserSync()
            }
            is RestoreDraftPatchPreviewUseCaseResult.Restored -> {
                stateService.workbench.markDraftPatchPreview(result.patch)
                stateService.workbench.markOperationFeedback(
                    ApplicationFeedbackLevel.INFO,
                    "已恢复草稿预览。",
                )
                requestBrowserSync()
            }
        }
    }

    /** 处理撤销应用用例结果：把工作图回滚到应用前状态，并清空对应撤销快照。 */
    fun presentUndo(result: UndoDraftPatchApplyUseCaseResult) {
        when (result) {
            UndoDraftPatchApplyUseCaseResult.MissingUndo -> {
                stateService.workbench.markOperationFeedback(
                    ApplicationFeedbackLevel.WARNING,
                    "当前没有可撤销的草稿写回。",
                )
                requestBrowserSync()
            }
            is UndoDraftPatchApplyUseCaseResult.Undone -> {
                stateService.graph.markGraphChanged(result.graph)
                stateService.workbench.clearDraftPatchApplyUndo()
                result.patchPreview?.let(stateService.workbench::markDraftPatchPreview)
                stateService.workbench.markOperationFeedback(
                    ApplicationFeedbackLevel.SUCCESS,
                    "已撤销上次草稿写回，并恢复应用前工作图。",
                )
                stateService.graph.markLastMessageType("undoDraftPatchApply")
                requestBrowserSync()
            }
        }
    }

    /** 把用例层应用汇总对象转换为前端展示所需的草稿补丁应用结果。 */
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
