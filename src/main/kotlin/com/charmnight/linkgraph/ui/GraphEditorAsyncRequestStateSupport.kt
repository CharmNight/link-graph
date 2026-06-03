package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.AssistantTurnKind
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.ReplayableQaRequest

internal class GraphEditorAsyncRequestStateSupport(
    private val mutate: ((GraphEditorStateSnapshot) -> GraphEditorStateSnapshot) -> Unit,
) {
    fun markQaResult(
        result: GraphPatchResult,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
        completedRequest: ReplayableQaRequest? = null,
        appendAssistantTurn: Boolean = true,
    ) {
        mutate {
            val nextState = it.copy(
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
            if (appendAssistantTurn) {
                nextState.withAssistantTurnRef(
                    kind = AssistantTurnKind.QA,
                    activeIntent = result.toAssistantIntent(),
                    sourceMessageType = "qaResult",
                    resultId = result.assistantResultId("qa"),
                    createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
                )
            } else {
                nextState.withAssistantContextFromCurrentState(result.toAssistantIntent())
            }
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
            ).withAssistantContextFromCurrentState(requestState.toQaAssistantIntent())
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
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.QA,
                activeIntent = requestState.toQaAssistantIntent(),
                sourceMessageType = "requestQa",
                resultId = requestState.assistantFailureResultId("qa", message),
                createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
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
        selectedDiffItemIds: List<String> = emptyList(),
    ) {
        mutate {
            it.copy(
                diffReviewResult = result,
                diffReviewRequestState = requestState,
                lastMessageType = "requestDiffReview",
            ).withAssistantSelectedDiffItemIds(selectedDiffItemIds).withAssistantTurnRef(
                kind = AssistantTurnKind.CHECK_RESULT,
                activeIntent = AssistantIntent.CHECK_CHANGE,
                sourceMessageType = "requestDiffReview",
                resultId = result.assistantResultId("diff-review"),
                createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
            )
        }
    }

    fun beginDiffReviewRequest(
        requestState: AsyncRequestState = AsyncRequestState.running(),
        selectedDiffItemIds: List<String> = emptyList(),
    ) {
        mutate {
            it.copy(
                diffReviewResult = null,
                diffReviewRequestState = requestState,
                lastMessageType = "requestDiffReview",
            ).withAssistantSelectedDiffItemIds(selectedDiffItemIds)
                .withAssistantContextFromCurrentState(AssistantIntent.CHECK_CHANGE)
        }
    }

    fun markDiffReviewRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
        selectedDiffItemIds: List<String> = emptyList(),
    ) {
        mutate {
            it.copy(
                diffReviewResult = null,
                diffReviewRequestState = requestState,
                lastMessageType = "requestDiffReview",
            ).withAssistantSelectedDiffItemIds(selectedDiffItemIds).withAssistantTurnRef(
                kind = AssistantTurnKind.CHECK_RESULT,
                activeIntent = AssistantIntent.CHECK_CHANGE,
                sourceMessageType = "requestDiffReview",
                resultId = requestState.assistantFailureResultId("diff-review", message),
                createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
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
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.EXPLANATION,
                activeIntent = AssistantIntent.EXPLAIN_CODE,
                sourceMessageType = "graphBeautificationResult",
                resultId = result.assistantResultId(),
                createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
            )
        }
    }

    fun beginGraphBeautificationRequest(requestState: AsyncRequestState = AsyncRequestState.running()) {
        mutate {
            it.copy(
                graphBeautificationResult = null,
                graphBeautificationRequestState = requestState,
                lastMessageType = "requestGraphBeautification",
            ).withAssistantContextFromCurrentState(AssistantIntent.EXPLAIN_CODE)
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
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.EXPLANATION,
                activeIntent = AssistantIntent.EXPLAIN_CODE,
                sourceMessageType = "requestGraphBeautification",
                resultId = requestState.assistantFailureResultId("explanation", message),
                createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
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
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.GENERATION_PLAN,
                activeIntent = AssistantIntent.GENERATE_CODE,
                sourceMessageType = "requestGenerationPlan",
                resultId = plan.assistantResultId(),
                createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
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
            ).withAssistantContextFromCurrentState(AssistantIntent.GENERATE_CODE)
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
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.GENERATION_PLAN,
                activeIntent = AssistantIntent.GENERATE_CODE,
                sourceMessageType = "requestGenerationPlan",
                resultId = requestState.assistantFailureResultId("generation-plan", message),
                createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
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
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.GENERATION_PLAN,
                activeIntent = AssistantIntent.GENERATE_CODE,
                sourceMessageType = "requestGenerationPlanDiscussion",
                resultId = result.session.sessionId,
                createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
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
            ).withAssistantContextFromCurrentState(AssistantIntent.GENERATE_CODE)
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
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.GENERATION_PLAN,
                activeIntent = AssistantIntent.GENERATE_CODE,
                sourceMessageType = "requestGenerationPlanDiscussion",
                resultId = requestState.assistantFailureResultId("generation-discussion", message),
                createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
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
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.CODE_DRAFT,
                activeIntent = AssistantIntent.GENERATE_CODE,
                sourceMessageType = "requestCodeDrafts",
                resultId = drafts.firstOrNull()?.id,
                createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
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
            ).withAssistantContextFromCurrentState(AssistantIntent.GENERATE_CODE)
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
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.CODE_DRAFT,
                activeIntent = AssistantIntent.GENERATE_CODE,
                sourceMessageType = "requestCodeDrafts",
                resultId = requestState.assistantFailureResultId("code-draft", message),
                createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
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

private fun GraphPatchResult.toAssistantIntent(): AssistantIntent =
    if (requestedMode == QaMode.REVIEW || effectiveMode == QaMode.REVIEW) {
        AssistantIntent.CHECK_CHANGE
    } else {
        AssistantIntent.ASK_CODE
    }

private fun AsyncRequestState.toQaAssistantIntent(): AssistantIntent =
    if (requestedMode == QaMode.REVIEW || effectiveMode == QaMode.REVIEW) {
        AssistantIntent.CHECK_CHANGE
    } else {
        AssistantIntent.ASK_CODE
    }

private fun GraphPatchResult.assistantResultId(prefix: String): String =
    "$prefix:${assistantStableHash(question.trim() + ASSISTANT_RESULT_HASH_SEPARATOR + answer.trim())}"

private fun GraphBeautificationResult.assistantResultId(): String =
    "explanation:${assistantStableHash(granularity.name + ASSISTANT_RESULT_HASH_SEPARATOR + steps.joinToString("|") { it.stepId })}"

private fun GenerationPlan.assistantResultId(): String =
    "generation-plan:${assistantStableHash(summary.trim() + ASSISTANT_RESULT_HASH_SEPARATOR + items.joinToString("|") { it.id })}"

private fun AsyncRequestState.assistantFailureResultId(prefix: String, message: String): String =
    requestId?.let { "$prefix-failure:$it" }
        ?: "$prefix-failure:${assistantStableHash(message.trim())}"

private const val ASSISTANT_RESULT_HASH_SEPARATOR = "\u001F"
private const val FNV_32_OFFSET_BASIS = 0x811c9dc5L
private const val FNV_32_PRIME = 0x01000193L
private const val UINT_32_MASK = 0xffffffffL

private fun assistantStableHash(input: String): String {
    var hash = FNV_32_OFFSET_BASIS
    input.forEach { char ->
        hash = hash xor char.code.toLong()
        hash = (hash * FNV_32_PRIME) and UINT_32_MASK
    }
    return hash.toString(16).padStart(8, '0')
}
