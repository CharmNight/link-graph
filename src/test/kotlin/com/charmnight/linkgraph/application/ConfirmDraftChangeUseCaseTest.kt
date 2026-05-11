package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.usecase.ConfirmDraftChangeUseCase
import com.charmnight.linkgraph.application.usecase.ConfirmDraftChangeUseCaseResult
import com.charmnight.linkgraph.application.model.ApplicationSnapshot
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
import com.charmnight.linkgraph.llm.ResultEvidenceReference
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.DraftWorkbenchService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConfirmDraftChangeUseCaseTest {
    @Test
    fun confirmsEligibleCandidateWithoutMutatingUiState() {
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:download",
                    type = NodeType.METHOD,
                    title = "download",
                    signature = "com.example.Controller.download():void",
                ),
            ),
        )
        val snapshot = ApplicationSnapshot(workspaceGraph = baseGraph, workspaceBaseGraph = baseGraph)
        val result = ConfirmDraftChangeUseCase(
            DraftWorkbenchService(),
            GraphPatchApplyService(),
        ).confirm(
            snapshot = snapshot,
            auditResult = GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "q",
                answer = "a",
                promptPreview = "p",
                candidateChanges = listOf(eligibleCandidate()),
            ),
            changeId = "change-download",
        )

        val confirmed = assertIs<ConfirmDraftChangeUseCaseResult.Confirmed>(result)
        assertEquals("change-download", confirmed.confirmedEntry?.sourceChangeId)
        assertEquals(1, confirmed.draftState.draftChanges.size)
        assertEquals(CandidateDraftChangeStatus.CONFIRMED, confirmed.updatedAuditResult.candidateChanges.single().status)
        assertEquals(0, snapshot.draftWorkbenchState.draftChanges.size)
    }

    @Test
    fun rejectsCandidateWithoutDirectEvidence() {
        val result = ConfirmDraftChangeUseCase(
            DraftWorkbenchService(),
            GraphPatchApplyService(),
        ).confirm(
            snapshot = ApplicationSnapshot(),
            auditResult = GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "q",
                answer = "a",
                promptPreview = "p",
                candidateChanges = listOf(
                    eligibleCandidate().copy(
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "finding-weak",
                                claim = "未观察到直接源码。",
                                evidenceLevel = ResultEvidenceLevel.NOT_OBSERVED,
                            ),
                        ),
                    ),
                ),
            ),
            changeId = "change-download",
        )

        assertIs<ConfirmDraftChangeUseCaseResult.Rejected>(result)
    }

    private fun eligibleCandidate(): CandidateDraftChange {
        return CandidateDraftChange(
            changeId = "change-download",
            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
            title = "调整下载逻辑",
            targetNodeIds = listOf("method:download"),
            beforeState = "old",
            afterState = "new",
            reason = "r",
            impactSummary = "i",
            claimType = "CODE_FACT",
            evidence = listOf(
                ResultEvidenceFinding(
                    id = "finding-direct",
                    claim = "直接源码证据。",
                    evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                    references = listOf(ResultEvidenceReference(nodeId = "method:download")),
                ),
            ),
        )
    }
}
