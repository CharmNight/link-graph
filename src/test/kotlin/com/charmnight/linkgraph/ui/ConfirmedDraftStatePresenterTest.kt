package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.usecase.ConfirmDraftChangeUseCaseResult
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.testing.testSnapshot
import com.charmnight.linkgraph.ui.toApplicationSnapshot
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.DraftValidationStatus
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.StageEligibilityTarget
import com.charmnight.linkgraph.workbench.StageEligibilityDecision
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfirmedDraftStatePresenterTest {
    @Test
    fun mapsConfirmedResultToDraftGraphAuditAndFeedbackState() {
        val stateService = GraphEditorStateService()
        val presenter = ConfirmedDraftStatePresenter(
            stateService = stateService,
            draftValidationEvaluator = {
                DraftValidationState(
                    status = DraftValidationStatus.READY,
                    message = "ready",
                )
            },
            codeEligibilityEvaluator = {
                StageEligibilityDecision(
                    target = StageEligibilityTarget.CODE,
                    allowed = true,
                    message = "allowed",
                )
            },
        )
        val result = ConfirmDraftChangeUseCaseResult.Confirmed(
            candidate = CandidateDraftChange(
                changeId = "change-1",
                status = com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus.CONFIRMED,
                title = "change",
            ),
            observedNodeIds = listOf("node-1"),
            confirmedEntry = DraftWorkbenchEntry(entryId = "entry-1", kind = com.charmnight.linkgraph.workbench.DraftEntryKind.CHANGE, title = "entry"),
            draftState = DraftWorkbenchState(
                draftChanges = listOf(
                    DraftWorkbenchEntry(entryId = "entry-1", kind = com.charmnight.linkgraph.workbench.DraftEntryKind.CHANGE, title = "entry"),
                ),
            ),
            rebuiltGraph = GraphDocument(),
            updatedAuditResult = com.charmnight.linkgraph.llm.GraphPatchResult(
                source = com.charmnight.linkgraph.llm.LlmResultSource.LOCAL_RULE,
                question = "q",
                answer = "a",
                promptPreview = "p",
            ),
        )

        presenter.presentConfirmation(result, testSnapshot().toApplicationSnapshot())

        val snapshot = stateService.snapshot()
        assertEquals(1, snapshot.draftWorkbenchState.draftChanges.size)
        assertEquals("q", snapshot.auditResult?.question)
        assertEquals(DraftValidationStatus.READY, snapshot.draftValidationState?.status)
        assertEquals(true, snapshot.codeEligibilityDecision?.allowed)
        assertEquals(OperationFeedbackLevel.SUCCESS, snapshot.operationFeedback?.level)
    }
}
