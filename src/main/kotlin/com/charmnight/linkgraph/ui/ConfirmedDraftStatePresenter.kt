package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.usecase.ConfirmDraftChangeUseCaseResult
import com.charmnight.linkgraph.application.usecase.UnconfirmDraftChangeUseCaseResult
import com.charmnight.linkgraph.application.model.ApplicationSnapshot
import com.charmnight.linkgraph.application.model.RiskResolutionSnapshot

class ConfirmedDraftStatePresenter(
    private val stateService: GraphEditorStateService,
    private val draftValidationEvaluator: (RiskResolutionSnapshot) -> com.charmnight.linkgraph.workbench.DraftValidationState?,
    private val codeEligibilityEvaluator: (RiskResolutionSnapshot) -> com.charmnight.linkgraph.workbench.StageEligibilityDecision?,
    private val requestBrowserSync: () -> Unit = {},
) {
    fun presentConfirmation(
        result: ConfirmDraftChangeUseCaseResult,
        baseSnapshot: ApplicationSnapshot,
    ) {
        when (result) {
            ConfirmDraftChangeUseCaseResult.MissingCandidate -> Unit
            is ConfirmDraftChangeUseCaseResult.Rejected -> {
                stateService.workbench.markOperationFeedback(
                    OperationFeedbackLevel.WARNING,
                    result.reason,
                )
            }
            is ConfirmDraftChangeUseCaseResult.Confirmed -> {
                stateService.workbench.markDraftWorkbenchState(
                    state = result.draftState,
                    advanceDraftVersion = true,
                )
                stateService.graph.markGraphChanged(
                    graph = result.rebuiltGraph,
                    selectedMethodSignature = baseSnapshot.selectedMethodSignature,
                    workingGraphDirty = result.draftState.draftChanges.isNotEmpty(),
                )
                stateService.asyncRequests.markQaResult(
                    result.updatedQaResult,
                    stateService.snapshot().qaRequestState,
                )
                val refreshedSnapshot = RiskResolutionSnapshot(
                    draftWorkbenchState = result.draftState,
                    qaResult = result.updatedQaResult,
                )
                stateService.workbench.markDraftValidationState(draftValidationEvaluator(refreshedSnapshot))
                stateService.workbench.markCodeEligibilityDecision(codeEligibilityEvaluator(refreshedSnapshot))
                stateService.workbench.markOperationFeedback(
                    OperationFeedbackLevel.SUCCESS,
                    "已确认候选变更，并写入草稿层。",
                )
                requestBrowserSync()
            }
        }
    }

    fun presentUnconfirmation(
        result: UnconfirmDraftChangeUseCaseResult,
        baseSnapshot: ApplicationSnapshot,
    ) {
        when (result) {
            UnconfirmDraftChangeUseCaseResult.MissingEntry -> Unit
            is UnconfirmDraftChangeUseCaseResult.Unconfirmed -> {
                stateService.workbench.markDraftWorkbenchState(
                    state = result.draftState,
                    advanceDraftVersion = true,
                )
                stateService.graph.markGraphChanged(
                    graph = result.rebuiltGraph,
                    selectedMethodSignature = baseSnapshot.selectedMethodSignature,
                    workingGraphDirty = result.draftState.draftChanges.isNotEmpty(),
                )
                stateService.asyncRequests.markQaResult(
                    result.updatedQaResult,
                    stateService.snapshot().qaRequestState,
                )
                val refreshedSnapshot = RiskResolutionSnapshot(
                    draftWorkbenchState = result.draftState,
                    qaResult = result.updatedQaResult,
                )
                stateService.workbench.markDraftValidationState(draftValidationEvaluator(refreshedSnapshot))
                stateService.workbench.markCodeEligibilityDecision(codeEligibilityEvaluator(refreshedSnapshot))
                stateService.workbench.markOperationFeedback(
                    OperationFeedbackLevel.INFO,
                    "已取消确认该候选变更，并从草稿层移除。",
                )
                requestBrowserSync()
            }
        }
    }
}
