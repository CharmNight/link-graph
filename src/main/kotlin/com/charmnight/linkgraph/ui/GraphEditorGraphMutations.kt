package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.workbench.DraftWorkbenchState

internal fun preservedConfirmedDraftState(
    currentState: GraphEditorStateSnapshot,
    nextSelectedMethodSignature: String?,
): DraftWorkbenchState {
    if (currentState.draftWorkbenchState.draftChanges.isEmpty()) {
        return DraftWorkbenchState()
    }
    val currentSignature = currentState.selectedMethodSignature
    if (currentSignature.isNullOrBlank() || nextSelectedMethodSignature.isNullOrBlank()) {
        return DraftWorkbenchState()
    }
    return if (currentSignature == nextSelectedMethodSignature) currentState.draftWorkbenchState else DraftWorkbenchState()
}

internal fun reapplyConfirmedDraftGraph(
    baseGraph: GraphDocument,
    draftState: DraftWorkbenchState,
    graphPatchApplyService: GraphPatchApplyService,
): GraphDocument {
    return draftState.draftChanges.fold(baseGraph) { currentGraph, entry ->
        val patch = entry.graphPatch ?: return@fold currentGraph
        graphPatchApplyService.apply(currentGraph, patch)
    }
}
