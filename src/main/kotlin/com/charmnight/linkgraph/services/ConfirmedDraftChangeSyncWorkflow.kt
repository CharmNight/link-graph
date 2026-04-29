package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.llm.artifact.ArtifactStore
import com.charmnight.linkgraph.llm.artifact.ConfirmedIntentArtifact
import com.charmnight.linkgraph.ui.GraphEditorStateMutationContext
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.OperationFeedbackLevel
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.intellij.openapi.diagnostic.Logger

internal class ConfirmedDraftChangeSyncWorkflow(
    private val stateServiceProvider: () -> GraphEditorStateService,
    private val confirmedDraftChangeWorkflow: ConfirmedDraftChangeWorkflow,
    private val riskResolutionService: RiskResolutionService,
    private val graphDiagnosticsLogger: GraphDiagnosticsLogger,
    private val artifactStoreProvider: () -> ArtifactStore,
    private val invalidateAuditRequests: () -> Unit,
    private val mutateEditorStateBatch: (GraphEditorStateMutationContext.() -> Unit) -> Unit,
    private val logger: Logger,
    private val runtimeTrace: ((String) -> Unit)?,
) {
    fun confirm(changeId: String): DraftWorkbenchEntry? {
        val stateService = stateServiceProvider()
        val snapshot = stateService.snapshot()
        val auditResult = snapshot.auditResult ?: return null
        when (val confirmation = confirmedDraftChangeWorkflow.confirm(snapshot, auditResult, changeId)) {
            ConfirmedDraftChangeWorkflow.ConfirmationResult.MissingCandidate -> return null
            is ConfirmedDraftChangeWorkflow.ConfirmationResult.Rejected -> {
                mutateEditorStateBatch {
                    apply {
                        workbench.markOperationFeedback(
                            OperationFeedbackLevel.WARNING,
                            confirmation.reason,
                        )
                    }
                }
                return null
            }

            is ConfirmedDraftChangeWorkflow.ConfirmationResult.Confirmed -> {
                debugLazy(logger.isDebugEnabled, logger::debug) {
                    "确认问答候选变更: ${GenerationDiagnostics.summarizeCandidateChange(confirmation.candidate)}"
                }
                runtimeTrace?.invoke(
                    "运行时确认候选变更: ${GenerationDiagnostics.summarizeCandidateChange(confirmation.candidate)}, " +
                        "graphPatch=${GenerationDiagnostics.summarizeGraphPatch(confirmation.candidate.graphPatch)}, " +
                        "baseTargets=${GenerationDiagnostics.summarizeNodeStates(currentWorkingGraph(snapshot), confirmation.observedNodeIds)}",
                )
                runtimeTrace?.invoke(
                    "运行时确认候选变更后图状态: changeId=${confirmation.candidate.changeId}, " +
                        "draftCount=${confirmation.draftState.draftChanges.size}, " +
                        "rebuiltTargets=${GenerationDiagnostics.summarizeNodeStates(confirmation.rebuiltGraph, confirmation.observedNodeIds)}",
                )
                graphDiagnosticsLogger.log("confirmAuditCandidateChange:$changeId", confirmation.rebuiltGraph)
                invalidateAuditRequests()
                mutateEditorStateBatch {
                    apply {
                        workbench.markDraftWorkbenchState(
                            state = confirmation.draftState,
                            advanceDraftVersion = true,
                        )
                    }
                    apply {
                        markGraphChanged(
                            graph = confirmation.rebuiltGraph,
                            selectedMethodSignature = snapshot.selectedMethodSignature,
                            workingGraphDirty = confirmation.draftState.draftChanges.isNotEmpty(),
                        )
                    }
                    apply {
                        asyncRequests.markAuditResult(
                            confirmation.updatedAuditResult,
                            snapshot.auditRequestState,
                        )
                    }
                    apply {
                        val refreshedSnapshot = snapshot.copy(
                            draftWorkbenchState = confirmation.draftState,
                            draftVersion = snapshot.draftVersion + 1,
                            auditResult = confirmation.updatedAuditResult,
                        )
                        workbench.markDraftValidationState(riskResolutionService.evaluateDraftValidation(refreshedSnapshot))
                        workbench.markCodeEligibilityDecision(riskResolutionService.evaluateCodeEligibility(refreshedSnapshot))
                    }
                    apply {
                        workbench.markOperationFeedback(
                            OperationFeedbackLevel.SUCCESS,
                            "已确认候选变更，并写入草稿层。",
                        )
                    }
                }
                confirmation.confirmedEntry?.let { entry ->
                    artifactStoreProvider().save(
                        ConfirmedIntentArtifact(
                            artifactId = "confirmed-${entry.entryId}",
                            entry = entry,
                        ),
                    )
                    debugLazy(logger.isDebugEnabled, logger::debug) {
                        "问答候选变更已写入草稿层: ${GenerationDiagnostics.summarizeDraftEntry(entry)}"
                    }
                }
                return confirmation.confirmedEntry
            }
        }
    }

    fun unconfirm(changeId: String): DraftWorkbenchEntry? {
        val snapshot = stateServiceProvider().snapshot()
        val auditResult = snapshot.auditResult ?: return null
        return when (val removal = confirmedDraftChangeWorkflow.unconfirm(snapshot, auditResult, changeId)) {
            ConfirmedDraftChangeWorkflow.UnconfirmationResult.MissingEntry -> null
            is ConfirmedDraftChangeWorkflow.UnconfirmationResult.Unconfirmed -> {
                debugLazy(logger.isDebugEnabled, logger::debug) {
                    "取消确认问答候选变更: ${GenerationDiagnostics.summarizeDraftEntry(removal.removedEntry)}"
                }
                graphDiagnosticsLogger.log("unconfirmAuditCandidateChange:$changeId", removal.rebuiltGraph)
                invalidateAuditRequests()
                mutateEditorStateBatch {
                    apply {
                        workbench.markDraftWorkbenchState(
                            state = removal.draftState,
                            advanceDraftVersion = true,
                        )
                    }
                    apply {
                        markGraphChanged(
                            graph = removal.rebuiltGraph,
                            selectedMethodSignature = snapshot.selectedMethodSignature,
                            workingGraphDirty = removal.draftState.draftChanges.isNotEmpty(),
                        )
                    }
                    apply {
                        asyncRequests.markAuditResult(
                            removal.updatedAuditResult,
                            snapshot.auditRequestState,
                        )
                    }
                    apply {
                        val refreshedSnapshot = snapshot.copy(
                            draftWorkbenchState = removal.draftState,
                            draftVersion = snapshot.draftVersion + 1,
                            auditResult = removal.updatedAuditResult,
                        )
                        workbench.markDraftValidationState(riskResolutionService.evaluateDraftValidation(refreshedSnapshot))
                        workbench.markCodeEligibilityDecision(riskResolutionService.evaluateCodeEligibility(refreshedSnapshot))
                    }
                    apply {
                        workbench.markOperationFeedback(
                            OperationFeedbackLevel.INFO,
                            "已取消确认该候选变更，并从草稿层移除。",
                        )
                    }
                }
                artifactStoreProvider().remove("confirmed-${removal.removedEntry.entryId}")
                removal.removedEntry
            }
        }
    }
}
