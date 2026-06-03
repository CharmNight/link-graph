package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.port.ApplicationSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.usecase.ApplyDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.DraftPatchUseCase
import com.charmnight.linkgraph.application.usecase.RestoreDraftPatchPreviewSource
import com.charmnight.linkgraph.application.usecase.RestoreDraftPatchPreviewUseCaseResult
import com.charmnight.linkgraph.application.usecase.UndoDraftPatchApplyUseCaseResult
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.application.model.DraftPatchPreviewSource
import com.charmnight.linkgraph.sync.GraphPatchApplyService

/**
 * Temporary project-bound entrypoint for draft patch commands while the router is migrated to application APIs.
 */
internal class DraftPatchWorkflow(
    private val snapshotProvider: ApplicationSnapshotProvider,
    private val eventSink: GraphEditorApplicationEventSink,
    graphPatchApplyService: GraphPatchApplyService,
) {
    private val useCase = DraftPatchUseCase(graphPatchApplyService)

    fun previewDraftPatch(patch: GraphPatch) {
        eventSink.emit(GraphEditorApplicationEvent.DraftPatchPreviewReady(useCase.previewDraftPatch(patch)))
    }

    fun applyDraftPatchPreview(operationIds: Set<String>? = null): GraphDocument? {
        val result = useCase.applyDraftPatchPreview(
            snapshot = snapshotProvider.snapshot(),
            operationIds = operationIds,
        )
        eventSink.emit(GraphEditorApplicationEvent.DraftPatchApplied(result))
        return (result as? ApplyDraftPatchUseCaseResult.Applied)?.graph
    }

    fun clearDraftPatchPreview() {
        val result = useCase.clearDraftPatchPreview(snapshotProvider.snapshot())
        eventSink.emit(GraphEditorApplicationEvent.DraftPatchCleared(result))
    }

    fun restoreDraftPatchPreview(source: DraftPatchPreviewSource): GraphPatch? {
        val result = useCase.restoreDraftPatchPreview(
            snapshot = snapshotProvider.snapshot(),
            source = source.toUseCaseSource(),
        )
        eventSink.emit(GraphEditorApplicationEvent.DraftPatchRestored(result))
        return (result as? RestoreDraftPatchPreviewUseCaseResult.Restored)?.patch
    }

    fun undoLastDraftPatchApply(): GraphDocument? {
        val result = useCase.undoLastDraftPatchApply(snapshotProvider.snapshot())
        eventSink.emit(GraphEditorApplicationEvent.DraftPatchUndone(result))
        return (result as? UndoDraftPatchApplyUseCaseResult.Undone)?.graph
    }

    private fun DraftPatchPreviewSource.toUseCaseSource(): RestoreDraftPatchPreviewSource {
        return when (this) {
            DraftPatchPreviewSource.QA -> RestoreDraftPatchPreviewSource.QA
            DraftPatchPreviewSource.DIFF_REVIEW -> RestoreDraftPatchPreviewSource.DIFF_REVIEW
            DraftPatchPreviewSource.LAST_APPLIED -> RestoreDraftPatchPreviewSource.LAST_APPLIED
        }
    }
}
