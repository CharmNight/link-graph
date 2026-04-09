package com.charmnight.linkgraph.workbench

class DraftWorkbenchService {
    fun confirmCandidateChange(
        draft: DraftWorkbenchState,
        candidate: CandidateDraftChange,
    ): DraftConfirmationResult {
        val entry = DraftWorkbenchEntry(
            entryId = "draft-${candidate.changeId}",
            kind = DraftEntryKind.CHANGE,
            title = candidate.title,
            sourceChangeId = candidate.changeId,
            targetStepIds = candidate.targetStepIds,
            targetNodeIds = candidate.targetNodeIds,
            beforeState = candidate.beforeState,
            afterState = candidate.afterState,
            reason = candidate.reason,
            impactSummary = candidate.impactSummary,
        )
        val nextDraftChanges = draft.draftChanges
            .filterNot { existing -> existing.sourceChangeId == candidate.changeId }
            .plus(entry)
        return DraftConfirmationResult(
            draftState = draft.copy(draftChanges = nextDraftChanges),
            draftChanges = nextDraftChanges,
            graphChanged = true,
        )
    }
}
