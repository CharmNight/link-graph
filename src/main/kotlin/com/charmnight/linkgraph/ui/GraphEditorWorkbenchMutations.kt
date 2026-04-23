package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

internal fun GraphEditorStateSnapshot.withDraftWorkbenchState(
    state: DraftWorkbenchState,
    advanceDraftVersion: Boolean,
): GraphEditorStateSnapshot {
    return copy(
        draftWorkbenchState = state,
        draftVersion = if (advanceDraftVersion) draftVersion + 1 else draftVersion,
        lastMessageType = "draftWorkbenchState",
    )
}

internal fun GraphEditorStateSnapshot.withDraftValidationState(
    state: DraftValidationState?,
): GraphEditorStateSnapshot {
    return copy(
        draftValidationState = state,
        lastMessageType = "draftValidation",
    )
}

internal fun GraphEditorStateSnapshot.withCodeEligibilityDecision(
    decision: StageEligibilityDecision?,
): GraphEditorStateSnapshot {
    return copy(
        codeEligibilityDecision = decision,
        lastMessageType = "codeEligibility",
    )
}
