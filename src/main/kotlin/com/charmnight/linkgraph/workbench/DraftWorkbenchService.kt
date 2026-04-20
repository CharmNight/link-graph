package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.model.GraphDocument

class DraftWorkbenchService {
    private val patchComposer = CandidateGraphPatchComposer()

    fun confirmCandidateChange(
        draft: DraftWorkbenchState,
        candidate: CandidateDraftChange,
        baseGraph: GraphDocument,
    ): DraftConfirmationResult {
        val normalizedCandidate = patchComposer.normalizeCandidate(candidate, baseGraph)
        val graphPatch = normalizedCandidate.graphPatch
        if (graphPatch == null || graphPatch.operations.isEmpty()) {
            return DraftConfirmationResult(
                draftState = draft,
                draftChanges = draft.draftChanges,
                graphChanged = false,
                failureReason = "当前候选变更没有形成可应用的真实图 patch，已拒绝写入草稿层。",
            )
        }
        val entry = DraftWorkbenchEntry(
            entryId = "draft-${candidate.changeId}",
            kind = DraftEntryKind.CHANGE,
            title = normalizedCandidate.title,
            sourceChangeId = normalizedCandidate.changeId,
            targetStepIds = normalizedCandidate.targetStepIds,
            targetNodeIds = normalizedCandidate.targetNodeIds,
            beforeState = normalizedCandidate.beforeState,
            afterState = normalizedCandidate.afterState,
            reason = normalizedCandidate.reason,
            impactSummary = normalizedCandidate.impactSummary,
            claimType = normalizedCandidate.claimType,
            evidence = normalizedCandidate.evidence,
            editScopes = normalizedCandidate.editScopes,
            patchIntent = normalizedCandidate.patchIntent,
            graphPatch = graphPatch,
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

    fun unconfirmCandidateChange(
        draft: DraftWorkbenchState,
        changeId: String,
    ): DraftRemovalResult {
        val removedEntry = draft.draftChanges.firstOrNull { entry -> entry.sourceChangeId == changeId }
        if (removedEntry == null) {
            return DraftRemovalResult(
                draftState = draft,
                removedEntry = null,
                graphChanged = false,
            )
        }
        val nextDraftChanges = draft.draftChanges.filterNot { entry -> entry.sourceChangeId == changeId }
        return DraftRemovalResult(
            draftState = draft.copy(draftChanges = nextDraftChanges),
            removedEntry = removedEntry,
            graphChanged = true,
        )
    }
}
