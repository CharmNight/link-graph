package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

internal class GraphEditorWorkbenchStateSupport(
    private val mutate: ((GraphEditorStateSnapshot) -> GraphEditorStateSnapshot) -> Unit,
) {
    fun markDraftPatchPreview(patch: GraphPatch) {
        mutate { currentState -> currentState.withDraftPatchPreview(patch) }
    }

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

    fun clearDraftPatchPreview() {
        mutate {
            it.copy(
                draftPatchPreview = null,
                lastMessageType = "draftPatchPreviewCleared",
            )
        }
    }

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

    fun clearDraftPatchApplyUndo() {
        mutate {
            it.copy(
                draftPatchUndoState = null,
                lastMessageType = "draftPatchApplyUndoCleared",
            )
        }
    }

    fun markDraftPatchApplyResult(result: DraftPatchApplyResult) {
        mutate {
            it.copy(
                lastDraftPatchApplyResult = result,
                lastMessageType = "draftPatchApplied",
            )
        }
    }

    fun markDraftValidationState(state: DraftValidationState?) {
        mutate {
            it.withDraftValidationState(state)
        }
    }

    fun markCodeEligibilityDecision(decision: StageEligibilityDecision?) {
        mutate {
            it.withCodeEligibilityDecision(decision)
        }
    }

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

    fun requestSyncPreview(items: List<SyncPreviewItem>) {
        mutate {
            it.copy(
                syncPreviewItems = items,
                syncPreviewRequested = true,
                lastMessageType = "requestSyncPreview",
            )
        }
    }

    fun markGeneratedCodeDraftWriteReport(report: GeneratedCodeDraftWriteReport) {
        mutate {
            it.copy(
                generatedCodeDraftWriteReport = report,
                lastMessageType = "applyCodeDrafts",
            )
        }
    }

    fun markOperationFeedback(
        level: OperationFeedbackLevel,
        message: String,
        preserveLastMessageType: Boolean = false,
    ) {
        mutate {
            it.copy(
                operationFeedback = OperationFeedback(level = level, message = message),
                lastMessageType = if (preserveLastMessageType) {
                    it.lastMessageType
                } else {
                    "operationFeedback"
                },
            )
        }
    }

    fun markWorkbenchSectionPreferences(preferences: Map<String, Boolean>) {
        mutate {
            it.copy(
                workbenchSectionPreferences = LinkedHashMap(preferences),
                lastMessageType = "workbenchSectionPreferences",
            )
        }
    }
}
