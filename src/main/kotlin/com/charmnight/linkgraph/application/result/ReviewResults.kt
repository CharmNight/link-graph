package com.charmnight.linkgraph.application.result

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.agent.model.GraphBeautificationResult
import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.ReplayableQaRequest
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

data class QaCompletedResult(
    val result: GraphPatchResult,
    val requestState: AsyncRequestState,
    val completedRequest: ReplayableQaRequest? = null,
    val draftValidationState: DraftValidationState?,
    val codeEligibilityDecision: StageEligibilityDecision?,
    val runtimeArtifacts: List<ApplicationRuntimeArtifactSummary>,
    val feedbackLevel: ApplicationFeedbackLevel? = null,
    val statusMessage: String? = null,
)

data class QaFailedResult(
    val message: String,
    val requestState: AsyncRequestState,
    val failedRequest: ReplayableQaRequest? = null,
    val runtimeArtifacts: List<ApplicationRuntimeArtifactSummary> = emptyList(),
    val feedbackLevel: ApplicationFeedbackLevel = ApplicationFeedbackLevel.ERROR,
    val preservePreviousStatusKind: Boolean = true,
)

data class DiffReviewCompletedResult(
    val result: GraphPatchResult,
    val requestState: AsyncRequestState,
    val feedbackLevel: ApplicationFeedbackLevel? = null,
    val statusMessage: String? = null,
    val selectedDiffItemIds: List<String> = emptyList(),
)

data class DiffReviewFailedResult(
    val message: String,
    val requestState: AsyncRequestState,
    val feedbackLevel: ApplicationFeedbackLevel = ApplicationFeedbackLevel.ERROR,
    val preservePreviousStatusKind: Boolean = true,
    val selectedDiffItemIds: List<String> = emptyList(),
)

data class BeautificationCompletedResult(
    val result: GraphBeautificationResult,
    val requestState: AsyncRequestState,
    val assistantIntent: AssistantIntent = AssistantIntent.EXPLAIN_CODE,
    val assistantActionId: AssistantActionId = AssistantActionId.EXPLAIN_FLOW,
    val feedbackLevel: ApplicationFeedbackLevel? = null,
    val statusMessage: String? = null,
)

data class BeautificationFailedResult(
    val message: String,
    val requestState: AsyncRequestState,
    val assistantIntent: AssistantIntent = AssistantIntent.EXPLAIN_CODE,
    val assistantActionId: AssistantActionId = AssistantActionId.EXPLAIN_FLOW,
    val feedbackLevel: ApplicationFeedbackLevel = ApplicationFeedbackLevel.ERROR,
    val preservePreviousStatusKind: Boolean = true,
)

enum class ReviewRequestScene {
    QA,
    DIFF_REVIEW,
    BEAUTIFICATION,
}

data class ReviewRequestStartedResult(
    val scene: ReviewRequestScene,
    val requestState: AsyncRequestState,
    val statusMessage: String,
    val assistantIntent: AssistantIntent? = null,
    val assistantActionId: AssistantActionId? = assistantIntent?.let(AssistantActionId::fromIntent),
    val submittedRequest: ReplayableQaRequest? = null,
    val clearRuntimeArtifactScene: String? = null,
    val selectedDiffItemIds: List<String> = emptyList(),
)
