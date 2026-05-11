package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.application.model.ApplicationSnapshot
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.sync.GraphPatchApplyService

data class DraftPatchApplySummary(
    val summary: String,
    val appliedOperationCount: Int,
    val appliedNodeIds: List<String> = emptyList(),
    val appliedEdgeIds: List<String> = emptyList(),
    val focusNodeId: String? = null,
    val appliedTargets: List<String> = emptyList(),
)

sealed interface PreviewDraftPatchUseCaseResult {
    data class Previewed(val patch: GraphPatch) : PreviewDraftPatchUseCaseResult
}

sealed interface ApplyDraftPatchUseCaseResult {
    data object MissingPreview : ApplyDraftPatchUseCaseResult

    data class Applied(
        val graphBeforeApply: GraphDocument,
        val patch: GraphPatch,
        val graph: GraphDocument,
        val applyResult: DraftPatchApplySummary,
    ) : ApplyDraftPatchUseCaseResult
}

sealed interface ClearDraftPatchPreviewUseCaseResult {
    data object MissingPreview : ClearDraftPatchPreviewUseCaseResult
    data object Cleared : ClearDraftPatchPreviewUseCaseResult
}

enum class RestoreDraftPatchPreviewSource {
    QA,
    DIFF_REVIEW,
    LAST_APPLIED,
}

sealed interface RestoreDraftPatchPreviewUseCaseResult {
    data object MissingPreview : RestoreDraftPatchPreviewUseCaseResult

    data class Restored(
        val patch: GraphPatch,
    ) : RestoreDraftPatchPreviewUseCaseResult
}

sealed interface UndoDraftPatchApplyUseCaseResult {
    data object MissingUndo : UndoDraftPatchApplyUseCaseResult

    data class Undone(
        val graph: GraphDocument,
        val patchPreview: GraphPatch?,
    ) : UndoDraftPatchApplyUseCaseResult
}

class DraftPatchUseCase(
    private val graphPatchApplyService: GraphPatchApplyService,
) {
    fun previewDraftPatch(patch: GraphPatch): PreviewDraftPatchUseCaseResult {
        return PreviewDraftPatchUseCaseResult.Previewed(patch)
    }

    fun applyDraftPatchPreview(
        snapshot: ApplicationSnapshot,
        operationIds: Set<String>? = null,
    ): ApplyDraftPatchUseCaseResult {
        val patch = snapshot.draftPatchPreview ?: return ApplyDraftPatchUseCaseResult.MissingPreview
        val selectedOperations = patch.operations.filter { operationIds == null || it.id in operationIds }
        val applied = graphPatchApplyService.apply(snapshot.workspaceGraph, patch, operationIds)
        return ApplyDraftPatchUseCaseResult.Applied(
            graphBeforeApply = snapshot.workspaceGraph,
            patch = patch,
            graph = applied,
            applyResult = buildDraftPatchApplyResult(selectedOperations, applied),
        )
    }

    fun clearDraftPatchPreview(snapshot: ApplicationSnapshot): ClearDraftPatchPreviewUseCaseResult {
        return if (snapshot.draftPatchPreview == null) {
            ClearDraftPatchPreviewUseCaseResult.MissingPreview
        } else {
            ClearDraftPatchPreviewUseCaseResult.Cleared
        }
    }

    fun restoreDraftPatchPreview(
        snapshot: ApplicationSnapshot,
        source: RestoreDraftPatchPreviewSource,
    ): RestoreDraftPatchPreviewUseCaseResult {
        val patch = when (source) {
            RestoreDraftPatchPreviewSource.QA -> snapshot.qaResult?.patch
            RestoreDraftPatchPreviewSource.DIFF_REVIEW -> snapshot.diffReviewResult?.patch
            RestoreDraftPatchPreviewSource.LAST_APPLIED -> snapshot.draftPatchUndo?.patchPreview
        } ?: return RestoreDraftPatchPreviewUseCaseResult.MissingPreview
        return RestoreDraftPatchPreviewUseCaseResult.Restored(patch)
    }

    fun undoLastDraftPatchApply(snapshot: ApplicationSnapshot): UndoDraftPatchApplyUseCaseResult {
        val undo = snapshot.draftPatchUndo ?: return UndoDraftPatchApplyUseCaseResult.MissingUndo
        return UndoDraftPatchApplyUseCaseResult.Undone(
            graph = undo.graphBeforeApply,
            patchPreview = undo.patchPreview,
        )
    }

    private fun buildDraftPatchApplyResult(
        operations: List<GraphPatchOperation>,
        graph: GraphDocument,
    ): DraftPatchApplySummary {
        val appliedNodeIds = operations.mapNotNull { operation ->
            when (operation.action) {
                GraphPatchAction.ADD_NODE,
                GraphPatchAction.UPDATE_NODE,
                GraphPatchAction.ADD_ANNOTATION,
                GraphPatchAction.MARK_UNCERTAIN -> operation.node?.id ?: operation.elementId.takeIf { operation.elementKind == GraphDiffElementKind.NODE }
                GraphPatchAction.DELETE_NODE -> operation.elementId.takeIf { operation.elementKind == GraphDiffElementKind.NODE }
                else -> null
            }
        }.distinct()
        val appliedEdgeIds = operations.mapNotNull { operation ->
            when (operation.action) {
                GraphPatchAction.ADD_EDGE,
                GraphPatchAction.UPDATE_EDGE,
                GraphPatchAction.DELETE_EDGE -> operation.edge?.id ?: operation.elementId.takeIf { operation.elementKind == GraphDiffElementKind.EDGE }
                else -> null
            }
        }.distinct()
        val focusNodeId = appliedNodeIds.firstOrNull { nodeId -> graph.nodes.any { node -> node.id == nodeId } }
        return DraftPatchApplySummary(
            summary = "已应用 ${operations.size} 条草稿图变更。",
            appliedOperationCount = operations.size,
            appliedNodeIds = appliedNodeIds,
            appliedEdgeIds = appliedEdgeIds,
            focusNodeId = focusNodeId,
            appliedTargets = operations.mapNotNull(::resolveDraftPatchApplyTarget).distinct(),
        )
    }

    private fun resolveDraftPatchApplyTarget(operation: GraphPatchOperation): String? {
        operation.node?.let { node ->
            return node.signature?.substringBefore('(') ?: node.title
        }
        operation.edge?.let { edge ->
            return edge.label ?: "${edge.fromNodeId} -> ${edge.toNodeId}"
        }
        return operation.title ?: operation.summary ?: operation.elementId
    }
}
