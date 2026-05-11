package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.ApplicationRuntimeArtifactSummary
import com.charmnight.linkgraph.application.port.QaCompletedPresentation
import com.charmnight.linkgraph.application.port.BeautificationCompletedPresentation
import com.charmnight.linkgraph.application.port.DiffReviewCompletedPresentation
import com.charmnight.linkgraph.application.port.ReviewRequestScene
import com.charmnight.linkgraph.application.port.ReviewRequestStartedPresentation
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.DraftValidationStatus
import com.charmnight.linkgraph.workbench.StageEligibilityDecision
import com.charmnight.linkgraph.workbench.StageEligibilityTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewStatePresenterTest {
    @Test
    fun mapsQaCompletedToResultEligibilityArtifactsAndFeedback() {
        val stateService = GraphEditorStateService()
        val presenter = ReviewStatePresenter(stateService)
        val result = GraphPatchResult(
            source = LlmResultSource.LOCAL_RULE,
            question = "q",
            answer = "a",
            promptPreview = "p",
        )

        presenter.presentQaCompleted(
            QaCompletedPresentation(
                result = result,
                requestState = AsyncRequestState.succeeded(scene = "问答", statusMessage = "问答完成。"),
                draftValidationState = DraftValidationState(DraftValidationStatus.READY, "ready"),
                codeEligibilityDecision = StageEligibilityDecision(StageEligibilityTarget.CODE, allowed = true, message = "allowed"),
                runtimeArtifacts = listOf(ApplicationRuntimeArtifactSummary("artifact-1", "qa", "QA")),
                feedbackLevel = ApplicationFeedbackLevel.SUCCESS,
                feedbackMessage = "问答完成。",
            ),
        )

        val snapshot = stateService.snapshot()
        assertEquals(result, snapshot.qaResult)
        assertEquals("QA", snapshot.runtimeArtifactSummaries["qa"]?.single()?.title)
        assertEquals(DraftValidationStatus.READY, snapshot.draftValidationState?.status)
        assertEquals(true, snapshot.codeEligibilityDecision?.allowed)
        assertEquals(OperationFeedbackLevel.SUCCESS, snapshot.operationFeedback?.level)
    }

    @Test
    fun mapsDiffReviewCompletedToResultPreviewAndFeedback() {
        val stateService = GraphEditorStateService()
        val presenter = ReviewStatePresenter(stateService)
        val patch = GraphPatch(summary = "patch")
        val result = GraphPatchResult(
            source = LlmResultSource.LOCAL_RULE,
            question = "q",
            answer = "a",
            promptPreview = "p",
            patch = patch,
        )

        presenter.presentDiffReviewCompleted(
            DiffReviewCompletedPresentation(
                result = result,
                requestState = AsyncRequestState.succeeded(scene = "差异分析", statusMessage = "差异分析完成。"),
                feedbackLevel = ApplicationFeedbackLevel.SUCCESS,
                feedbackMessage = "差异分析完成。",
            ),
        )

        val snapshot = stateService.snapshot()
        assertEquals(result, snapshot.diffReviewResult)
        assertEquals(patch, snapshot.draftPatchPreview)
        assertEquals(OperationFeedbackLevel.SUCCESS, snapshot.operationFeedback?.level)
    }

    @Test
    fun mapsBeautificationCompletedToResultAndFeedback() {
        val stateService = GraphEditorStateService()
        val presenter = ReviewStatePresenter(stateService)
        val result = GraphBeautificationResult(source = LlmResultSource.LOCAL_RULE)

        presenter.presentBeautificationCompleted(
            BeautificationCompletedPresentation(
                result = result,
                requestState = AsyncRequestState.succeeded(scene = "链路讲解", statusMessage = "链路讲解完成。"),
                feedbackLevel = ApplicationFeedbackLevel.SUCCESS,
                feedbackMessage = "链路讲解完成。",
            ),
        )

        val snapshot = stateService.snapshot()
        assertEquals(result, snapshot.graphBeautificationResult)
        assertEquals(OperationFeedbackLevel.SUCCESS, snapshot.operationFeedback?.level)
    }

    @Test
    fun mapsStreamingPreviewThroughReviewRequestScene() {
        val stateService = GraphEditorStateService()
        var browserSyncCount = 0
        val presenter = ReviewStatePresenter(stateService) {
            browserSyncCount += 1
        }

        presenter.presentRequestStarted(
            ReviewRequestStartedPresentation(
                scene = ReviewRequestScene.DIFF_REVIEW,
                requestState = AsyncRequestState.running(requestId = 77L, scene = "差异分析", streaming = true),
                feedbackMessage = "正在分析差异。",
            ),
        )

        presenter.presentStreamingPreview(
            scene = ReviewRequestScene.DIFF_REVIEW,
            requestId = 77L,
            previewText = "partial review",
            finalizingStructuredResult = true,
        )

        val snapshot = stateService.snapshot()
        assertEquals("partial review", snapshot.diffReviewRequestState.previewText)
        assertEquals("FINALIZING", snapshot.diffReviewRequestState.streamPhase)
        assertEquals(true, snapshot.diffReviewRequestState.finalizingStructuredResult)
        assertEquals(2, browserSyncCount)
    }
}
