package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.workbench.DraftWorkbenchState

data class ApplicationSnapshot(
    val workspaceBaseGraph: GraphDocument = GraphDocument(),
    val workspaceGraph: GraphDocument = GraphDocument(),
    val selectedMethodSignature: String? = null,
    val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    val draftPatchPreview: GraphPatch? = null,
    val draftPatchUndo: DraftPatchUndo? = null,
    val qaResult: GraphPatchResult? = null,
    val diffReviewResult: GraphPatchResult? = null,
)

fun ApplicationSnapshot.toRiskResolutionSnapshot(): RiskResolutionSnapshot {
    return RiskResolutionSnapshot(
        draftWorkbenchState = draftWorkbenchState,
        qaResult = qaResult,
    )
}

data class DraftPatchUndo(
    val graphBeforeApply: GraphDocument,
    val patchPreview: GraphPatch? = null,
)
