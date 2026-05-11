package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.artifact.ConfirmedDraftArtifactWriter
import com.charmnight.linkgraph.application.port.ApplicationSnapshotProvider
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.usecase.ConfirmDraftChangeUseCase
import com.charmnight.linkgraph.application.usecase.ConfirmDraftChangeUseCaseResult
import com.charmnight.linkgraph.application.usecase.UnconfirmDraftChangeUseCaseResult
import com.charmnight.linkgraph.application.diagnostics.GenerationDiagnostics
import com.charmnight.linkgraph.application.diagnostics.GraphDiagnosticsLogger
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchService
import com.intellij.openapi.diagnostic.Logger

internal class ConfirmedDraftChangeCoordinator(
    private val snapshotProvider: ApplicationSnapshotProvider,
    private val eventSink: GraphEditorApplicationEventSink,
    draftWorkbenchService: DraftWorkbenchService,
    graphPatchApplyService: GraphPatchApplyService,
    private val graphDiagnosticsLogger: GraphDiagnosticsLogger,
    artifactWriter: ConfirmedDraftArtifactWriter,
    private val invalidateQaRequests: () -> Unit,
    private val logger: Logger,
    private val runtimeTrace: ((String) -> Unit)?,
) {
    private val useCase = ConfirmDraftChangeUseCase(draftWorkbenchService, graphPatchApplyService)
    private val artifactWriter = artifactWriter

    fun confirm(changeId: String): DraftWorkbenchEntry? {
        val snapshot = snapshotProvider.snapshot()
        val qaResult = snapshot.qaResult ?: return null
        return when (val confirmation = useCase.confirm(snapshot, qaResult, changeId)) {
            ConfirmDraftChangeUseCaseResult.MissingCandidate -> null
            is ConfirmDraftChangeUseCaseResult.Rejected -> {
                eventSink.emit(GraphEditorApplicationEvent.DraftChangeConfirmed(confirmation, snapshot))
                null
            }

            is ConfirmDraftChangeUseCaseResult.Confirmed -> {
                debugLazy(logger.isDebugEnabled, logger::debug) {
                    "确认问答候选变更: ${GenerationDiagnostics.summarizeCandidateChange(confirmation.candidate)}"
                }
                runtimeTrace?.invoke(
                    "运行时确认候选变更: ${GenerationDiagnostics.summarizeCandidateChange(confirmation.candidate)}, " +
                        "graphPatch=${GenerationDiagnostics.summarizeGraphPatch(confirmation.candidate.graphPatch)}, " +
                        "baseTargets=${GenerationDiagnostics.summarizeNodeStates(snapshot.workspaceGraph, confirmation.observedNodeIds)}",
                )
                runtimeTrace?.invoke(
                    "运行时确认候选变更后图状态: changeId=${confirmation.candidate.changeId}, " +
                        "draftCount=${confirmation.draftState.draftChanges.size}, " +
                        "rebuiltTargets=${GenerationDiagnostics.summarizeNodeStates(confirmation.rebuiltGraph, confirmation.observedNodeIds)}",
                )
                graphDiagnosticsLogger.log("confirmQaCandidateChange:$changeId", confirmation.rebuiltGraph)
                invalidateQaRequests()
                eventSink.emit(GraphEditorApplicationEvent.DraftChangeConfirmed(confirmation, snapshot))
                confirmation.confirmedEntry?.let { entry ->
                    artifactWriter.recordConfirmedEntry(entry)
                    debugLazy(logger.isDebugEnabled, logger::debug) {
                        "问答候选变更已写入草稿层: ${GenerationDiagnostics.summarizeDraftEntry(entry)}"
                    }
                }
                confirmation.confirmedEntry
            }
        }
    }

    fun unconfirm(changeId: String): DraftWorkbenchEntry? {
        val snapshot = snapshotProvider.snapshot()
        val qaResult = snapshot.qaResult ?: return null
        return when (val removal = useCase.unconfirm(snapshot, qaResult, changeId)) {
            UnconfirmDraftChangeUseCaseResult.MissingEntry -> null
            is UnconfirmDraftChangeUseCaseResult.Unconfirmed -> {
                debugLazy(logger.isDebugEnabled, logger::debug) {
                    "取消确认问答候选变更: ${GenerationDiagnostics.summarizeDraftEntry(removal.removedEntry)}"
                }
                graphDiagnosticsLogger.log("unconfirmQaCandidateChange:$changeId", removal.rebuiltGraph)
                invalidateQaRequests()
                eventSink.emit(GraphEditorApplicationEvent.DraftChangeUnconfirmed(removal, snapshot))
                artifactWriter.removeConfirmedEntry(removal.removedEntry.entryId)
                removal.removedEntry
            }
        }
    }
}
