package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.workbench.DraftWorkbenchState

data class RiskResolutionSnapshot(
    val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    val qaResult: GraphPatchResult? = null,
)
