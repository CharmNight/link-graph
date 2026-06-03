package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult
import com.charmnight.linkgraph.workbench.ReplayableQaRequest

internal class GraphEditorAsyncRequestStateSupport(
    private val mutate: ((GraphEditorStateSnapshot) -> GraphEditorStateSnapshot) -> Unit,
) {
    fun markQaResult(
        result: GraphPatchResult,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
        completedRequest: ReplayableQaRequest? = null,
    ) {
        mutate {
            it.copy(
                qaResult = result,
                qaRequestState = requestState,
                qaRequestRecoveryState = completedRequest?.let { request ->
                    it.qaRequestRecoveryState.copy(
                        lastSubmittedRequest = request,
                        lastFailedRequest = null,
                    )
                } ?: it.qaRequestRecoveryState,
                lastMessageType = "qaResult",
            )
        }
    }

    fun beginQaRequest(
        requestState: AsyncRequestState = AsyncRequestState.running(),
        submittedRequest: ReplayableQaRequest? = null,
    ) {
        mutate {
            it.copy(
                qaResult = null,
                qaRequestState = requestState,
                qaRequestRecoveryState = submittedRequest?.let { request ->
                    it.qaRequestRecoveryState.copy(
                        lastSubmittedRequest = request,
                        lastFailedRequest = null,
                    )
                } ?: it.qaRequestRecoveryState,
                lastMessageType = "requestQa",
            )
        }
    }

    fun markQaRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
        failedRequest: ReplayableQaRequest? = null,
    ) {
        mutate {
            it.copy(
                qaResult = null,
                qaRequestState = requestState,
                qaRequestRecoveryState = failedRequest?.let { request ->
                    it.qaRequestRecoveryState.copy(
                        lastSubmittedRequest = request,
                        lastFailedRequest = request,
                    )
                } ?: it.qaRequestRecoveryState,
                lastMessageType = "requestQa",
            )
        }
    }

    fun updateQaRequestPreview(
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean = false,
    ) {
        mutate { currentState ->
            val nextRequestState = currentState.qaRequestState.updatedPreviewOrNull(
                requestId = requestId,
                previewText = previewText,
                finalizingStructuredResult = finalizingStructuredResult,
            ) ?: return@mutate currentState
            currentState.copy(
                qaRequestState = nextRequestState,
                lastMessageType = "requestQa",
            )
        }
    }

    fun markDiffReviewResult(
        result: GraphPatchResult,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
    ) {
        mutate {
            it.copy(
                diffReviewResult = result,
                diffReviewRequestState = requestState,
                lastMessageType = "requestDiffReview",
            )
        }
    }

    fun beginDiffReviewRequest(requestState: AsyncRequestState = AsyncRequestState.running()) {
        mutate {
            it.copy(
                diffReviewResult = null,
                diffReviewRequestState = requestState,
                lastMessageType = "requestDiffReview",
            )
        }
    }

    fun markDiffReviewRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
    ) {
        mutate {
            it.copy(
                diffReviewResult = null,
                diffReviewRequestState = requestState,
                lastMessageType = "requestDiffReview",
            )
        }
    }

    fun updateDiffReviewRequestPreview(
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean = false,
    ) {
        mutate { currentState ->
            val nextRequestState = currentState.diffReviewRequestState.updatedPreviewOrNull(
                requestId = requestId,
                previewText = previewText,
                finalizingStructuredResult = finalizingStructuredResult,
            ) ?: return@mutate currentState
            currentState.copy(
                diffReviewRequestState = nextRequestState,
                lastMessageType = "requestDiffReview",
            )
        }
    }

    fun markGraphBeautificationResult(
        result: GraphBeautificationResult,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
    ) {
        mutate {
            it.copy(
                graphBeautificationResult = result,
                graphBeautificationRequestState = requestState,
                lastMessageType = "graphBeautificationResult",
            )
        }
    }

    fun beginGraphBeautificationRequest(requestState: AsyncRequestState = AsyncRequestState.running()) {
        mutate {
            it.copy(
                graphBeautificationResult = null,
                graphBeautificationRequestState = requestState,
                lastMessageType = "requestGraphBeautification",
            )
        }
    }

    fun markGraphBeautificationRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
    ) {
        mutate {
            it.copy(
                graphBeautificationResult = null,
                graphBeautificationRequestState = requestState,
                lastMessageType = "requestGraphBeautification",
            )
        }
    }

    fun updateGraphBeautificationRequestPreview(
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean = false,
    ) {
        mutate { currentState ->
            val nextRequestState = currentState.graphBeautificationRequestState.updatedPreviewOrNull(
                requestId = requestId,
                previewText = previewText,
                finalizingStructuredResult = finalizingStructuredResult,
            ) ?: return@mutate currentState
            currentState.copy(
                graphBeautificationRequestState = nextRequestState,
                lastMessageType = "requestGraphBeautification",
            )
        }
    }

    fun markGenerationPlan(
        plan: GenerationPlan,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
    ) {
        mutate { currentState ->
            currentState.copy(
                generationPlan = plan,
                generationPlanDraftVersion = currentState.draftVersion,
                generationPlanRequestState = requestState,
                generationPlanDiscussionSession = null,
                generationPlanDiscussionRequestState = AsyncRequestState(),
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftVersion = null,
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                lastMessageType = "requestGenerationPlan",
            )
        }
    }

    fun beginGenerationPlanRequest(requestState: AsyncRequestState = AsyncRequestState.running()) {
        mutate {
            it.copy(
                generationPlan = null,
                generationPlanDraftVersion = null,
                generationPlanRequestState = requestState,
                generationPlanDiscussionSession = null,
                generationPlanDiscussionRequestState = AsyncRequestState(),
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftVersion = null,
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                lastMessageType = "requestGenerationPlan",
            )
        }
    }

    fun markGenerationPlanRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
    ) {
        mutate {
            it.copy(
                generationPlan = null,
                generationPlanDraftVersion = null,
                generationPlanRequestState = requestState,
                generationPlanDiscussionSession = null,
                generationPlanDiscussionRequestState = AsyncRequestState(),
                lastMessageType = "requestGenerationPlan",
            )
        }
    }

    fun updateGenerationPlanRequestPreview(
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean = false,
    ) {
        mutate { currentState ->
            val nextRequestState = currentState.generationPlanRequestState.updatedPreviewOrNull(
                requestId = requestId,
                previewText = previewText,
                finalizingStructuredResult = finalizingStructuredResult,
            ) ?: return@mutate currentState
            currentState.copy(
                generationPlanRequestState = nextRequestState,
                lastMessageType = "requestGenerationPlan",
            )
        }
    }

    fun markGenerationPlanDiscussion(
        result: GenerationPlanDiscussionResult,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
    ) {
        mutate {
            it.copy(
                generationPlanDiscussionSession = result.session.copy(promptPreview = result.promptPreview),
                generationPlanDiscussionRequestState = requestState,
                lastMessageType = "requestGenerationPlanDiscussion",
            )
        }
    }

    fun beginGenerationPlanDiscussionRequest(
        requestState: AsyncRequestState = AsyncRequestState.running(),
    ) {
        mutate {
            it.copy(
                generationPlanDiscussionRequestState = requestState,
                lastMessageType = "requestGenerationPlanDiscussion",
            )
        }
    }

    fun markGenerationPlanDiscussionRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
    ) {
        mutate {
            it.copy(
                generationPlanDiscussionRequestState = requestState,
                lastMessageType = "requestGenerationPlanDiscussion",
            )
        }
    }

    fun updateGenerationPlanDiscussionRequestPreview(
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean = false,
    ) {
        mutate { currentState ->
            val nextRequestState = currentState.generationPlanDiscussionRequestState.updatedPreviewOrNull(
                requestId = requestId,
                previewText = previewText,
                finalizingStructuredResult = finalizingStructuredResult,
            ) ?: return@mutate currentState
            currentState.copy(
                generationPlanDiscussionRequestState = nextRequestState,
                lastMessageType = "requestGenerationPlanDiscussion",
            )
        }
    }

    fun markGeneratedCodeDrafts(
        drafts: List<GeneratedCodeDraft>,
        warnings: List<String>,
        source: LlmResultSource,
        promptPreview: String?,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
    ) {
        mutate { currentState ->
            currentState.copy(
                generatedCodeDrafts = drafts,
                generatedCodeDraftVersion = currentState.draftVersion,
                generatedCodeDraftWarnings = warnings,
                generatedCodeDraftSource = source,
                generatedCodeDraftPromptPreview = promptPreview,
                codeDraftRequestState = requestState,
                generatedCodeDraftWriteReport = null,
                lastMessageType = "requestCodeDrafts",
            )
        }
    }

    fun beginCodeDraftRequest(requestState: AsyncRequestState = AsyncRequestState.running()) {
        mutate {
            it.copy(
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftVersion = null,
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                codeDraftRequestState = requestState,
                lastMessageType = "requestCodeDrafts",
            )
        }
    }

    fun markCodeDraftRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
    ) {
        mutate {
            it.copy(
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftVersion = null,
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                codeDraftRequestState = requestState,
                lastMessageType = "requestCodeDrafts",
            )
        }
    }

    fun updateCodeDraftRequestPreview(
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean = false,
    ) {
        mutate { currentState ->
            val nextRequestState = currentState.codeDraftRequestState.updatedPreviewOrNull(
                requestId = requestId,
                previewText = previewText,
                finalizingStructuredResult = finalizingStructuredResult,
            ) ?: return@mutate currentState
            currentState.copy(
                codeDraftRequestState = nextRequestState,
                lastMessageType = "requestCodeDrafts",
            )
        }
    }
}
