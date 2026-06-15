package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult
import com.charmnight.linkgraph.workbench.AssistantFailureResult
import com.charmnight.linkgraph.workbench.AssistantResultStoreEntry
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.AssistantActionId
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
        mutate { currentState ->
            val identity = currentState.allocateAssistantResultId("qa", requestState)
            val resultId = identity.resultId
            val stateWithResultId = identity.snapshot
            val nextState = stateWithResultId.copy(
                qaResult = result,
                qaRequestState = requestState,
                qaRequestRecoveryState = completedRequest?.let { request ->
                    stateWithResultId.qaRequestRecoveryState.copy(
                        lastSubmittedRequest = request,
                        lastFailedRequest = null,
                    )
                } ?: stateWithResultId.qaRequestRecoveryState,
                assistantResultStore = stateWithResultId.assistantResultStore.put(
                    resultId,
                    AssistantResultStoreEntry(
                        kind = AssistantTurnKind.QA,
                        qa = result,
                    ),
                ),
                lastMessageType = "qaResult",
            )
            if (appendAssistantTurn) {
                nextState.withAssistantTurnRef(
                    kind = AssistantTurnKind.QA,
                    activeIntent = result.toAssistantIntent(),
                    activeActionId = result.toAssistantActionId(),
                    sourceMessageType = "qaResult",
                    resultId = resultId,
                    createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
                )
            } else {
                nextState.withAssistantContextFromCurrentState(result.toAssistantIntent(), result.toAssistantActionId())
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
                lastMessageType = "requestAssistantTask",
            ).withAssistantContextFromCurrentState(requestState.toQaAssistantIntent(), requestState.toQaAssistantActionId())
        }
    }

    fun markQaRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
        failedRequest: ReplayableQaRequest? = null,
    ) {
        mutate { currentState ->
            val identity = currentState.allocateAssistantResultId("qa-failure", requestState)
            val resultId = identity.resultId
            val stateWithResultId = identity.snapshot
            stateWithResultId.copy(
                qaResult = null,
                qaRequestState = requestState,
                qaRequestRecoveryState = failedRequest?.let { request ->
                    stateWithResultId.qaRequestRecoveryState.copy(
                        lastSubmittedRequest = request,
                        lastFailedRequest = request,
                    )
                } ?: stateWithResultId.qaRequestRecoveryState,
                assistantResultStore = stateWithResultId.assistantResultStore.put(
                    resultId,
                    requestState.assistantFailureEntry(
                        kind = AssistantTurnKind.QA,
                        resultId = resultId,
                        message = message,
                        sourceMessageType = "requestAssistantTask",
                    ),
                ),
                lastMessageType = "requestAssistantTask",
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.QA,
                activeIntent = requestState.toQaAssistantIntent(),
                activeActionId = requestState.toQaAssistantActionId(),
                sourceMessageType = "requestAssistantTask",
                resultId = resultId,
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
                lastMessageType = "requestAssistantTask",
            )
        }
    }

    fun markDiffReviewResult(
        result: GraphPatchResult,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
        selectedDiffItemIds: List<String> = emptyList(),
    ) {
        mutate { currentState ->
            val identity = currentState.allocateAssistantResultId("diff-review", requestState)
            val resultId = identity.resultId
            val stateWithResultId = identity.snapshot
            stateWithResultId.copy(
                diffReviewResult = result,
                diffReviewRequestState = requestState,
                assistantResultStore = stateWithResultId.assistantResultStore.put(
                    resultId,
                    AssistantResultStoreEntry(
                        kind = AssistantTurnKind.CHECK_RESULT,
                        check = result,
                    ),
                ),
                lastMessageType = "diffReviewResult",
            ).withAssistantSelectedDiffItemIds(selectedDiffItemIds).withAssistantTurnRef(
                kind = AssistantTurnKind.CHECK_RESULT,
                activeIntent = AssistantIntent.CHECK_CHANGE,
                activeActionId = AssistantActionId.CHECK_CHANGE,
                sourceMessageType = "diffReviewResult",
                resultId = resultId,
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
                lastMessageType = "requestAssistantTask",
            ).withAssistantSelectedDiffItemIds(selectedDiffItemIds)
                .withAssistantContextFromCurrentState(AssistantIntent.CHECK_CHANGE, AssistantActionId.CHECK_CHANGE)
        }
    }

    fun markDiffReviewRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
        selectedDiffItemIds: List<String> = emptyList(),
    ) {
        mutate { currentState ->
            val identity = currentState.allocateAssistantResultId("diff-review-failure", requestState)
            val resultId = identity.resultId
            val stateWithResultId = identity.snapshot
            stateWithResultId.copy(
                diffReviewResult = null,
                diffReviewRequestState = requestState,
                assistantResultStore = stateWithResultId.assistantResultStore.put(
                    resultId,
                    requestState.assistantFailureEntry(
                        kind = AssistantTurnKind.CHECK_RESULT,
                        resultId = resultId,
                        message = message,
                        sourceMessageType = "requestAssistantTask",
                    ),
                ),
                lastMessageType = "requestAssistantTask",
            ).withAssistantSelectedDiffItemIds(selectedDiffItemIds).withAssistantTurnRef(
                kind = AssistantTurnKind.CHECK_RESULT,
                activeIntent = AssistantIntent.CHECK_CHANGE,
                activeActionId = AssistantActionId.CHECK_CHANGE,
                sourceMessageType = "requestAssistantTask",
                resultId = resultId,
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
                lastMessageType = "requestAssistantTask",
            )
        }
    }

    fun markGraphBeautificationResult(
        result: GraphBeautificationResult,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
        assistantIntent: AssistantIntent = AssistantIntent.EXPLAIN_CODE,
        assistantActionId: AssistantActionId = AssistantActionId.EXPLAIN_FLOW,
    ) {
        mutate { currentState ->
            val identity = currentState.allocateAssistantResultId("explanation", requestState)
            val resultId = identity.resultId
            val stateWithResultId = identity.snapshot
            stateWithResultId.copy(
                graphBeautificationResult = result,
                graphBeautificationRequestState = requestState,
                assistantResultStore = stateWithResultId.assistantResultStore.put(
                    resultId,
                    AssistantResultStoreEntry(
                        kind = AssistantTurnKind.EXPLANATION,
                        explanation = result,
                    ),
                ),
                lastMessageType = "graphBeautificationResult",
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.EXPLANATION,
                activeIntent = assistantIntent,
                activeActionId = assistantActionId,
                sourceMessageType = "graphBeautificationResult",
                resultId = resultId,
                createdAtEpochMillis = requestState.finishedAtEpochMillis ?: System.currentTimeMillis(),
            )
        }
    }

    fun beginGraphBeautificationRequest(
        requestState: AsyncRequestState = AsyncRequestState.running(),
        assistantIntent: AssistantIntent = AssistantIntent.EXPLAIN_CODE,
        assistantActionId: AssistantActionId? = AssistantActionId.EXPLAIN_FLOW,
    ) {
        mutate {
            it.copy(
                graphBeautificationResult = null,
                graphBeautificationRequestState = requestState,
                lastMessageType = "requestAssistantTask",
            ).withAssistantContextFromCurrentState(assistantIntent, assistantActionId)
        }
    }

    fun markGraphBeautificationRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
        assistantIntent: AssistantIntent = AssistantIntent.EXPLAIN_CODE,
        assistantActionId: AssistantActionId = AssistantActionId.EXPLAIN_FLOW,
    ) {
        mutate { currentState ->
            val identity = currentState.allocateAssistantResultId("explanation-failure", requestState)
            val resultId = identity.resultId
            val stateWithResultId = identity.snapshot
            stateWithResultId.copy(
                graphBeautificationResult = null,
                graphBeautificationRequestState = requestState,
                assistantResultStore = stateWithResultId.assistantResultStore.put(
                    resultId,
                    requestState.assistantFailureEntry(
                        kind = AssistantTurnKind.EXPLANATION,
                        resultId = resultId,
                        message = message,
                        sourceMessageType = "requestAssistantTask",
                    ),
                ),
                lastMessageType = "requestAssistantTask",
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.EXPLANATION,
                activeIntent = assistantIntent,
                activeActionId = assistantActionId,
                sourceMessageType = "requestAssistantTask",
                resultId = resultId,
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
                lastMessageType = "requestAssistantTask",
            )
        }
    }

    fun markGenerationPlan(
        plan: GenerationPlan,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
    ) {
        mutate { currentState ->
            val identity = currentState.allocateAssistantResultId("generation-plan", requestState)
            val resultId = identity.resultId
            val stateWithResultId = identity.snapshot
            stateWithResultId.copy(
                generationPlan = plan,
                generationPlanDraftVersion = stateWithResultId.draftVersion,
                generationPlanRequestState = requestState,
                generationPlanDiscussionSession = null,
                generationPlanDiscussionRequestState = AsyncRequestState(),
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftVersion = null,
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                assistantResultStore = stateWithResultId.assistantResultStore.put(
                    resultId,
                    AssistantResultStoreEntry(
                        kind = AssistantTurnKind.GENERATION_PLAN,
                        generationPlan = plan,
                    ),
                ),
                lastMessageType = "generationPlanResult",
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.GENERATION_PLAN,
                activeIntent = AssistantIntent.GENERATE_CODE,
                activeActionId = AssistantActionId.GENERATE_IMPLEMENTATION,
                sourceMessageType = "generationPlanResult",
                resultId = resultId,
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
                lastMessageType = "requestAssistantTask",
            ).withAssistantContextFromCurrentState(AssistantIntent.GENERATE_CODE, AssistantActionId.GENERATE_IMPLEMENTATION)
        }
    }

    fun markGenerationPlanRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
    ) {
        mutate { currentState ->
            val identity = currentState.allocateAssistantResultId("generation-plan-failure", requestState)
            val resultId = identity.resultId
            val stateWithResultId = identity.snapshot
            stateWithResultId.copy(
                generationPlan = null,
                generationPlanDraftVersion = null,
                generationPlanRequestState = requestState,
                generationPlanDiscussionSession = null,
                generationPlanDiscussionRequestState = AsyncRequestState(),
                assistantResultStore = stateWithResultId.assistantResultStore.put(
                    resultId,
                    requestState.assistantFailureEntry(
                        kind = AssistantTurnKind.GENERATION_PLAN,
                        resultId = resultId,
                        message = message,
                        sourceMessageType = "requestAssistantTask",
                    ),
                ),
                lastMessageType = "requestAssistantTask",
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.GENERATION_PLAN,
                activeIntent = AssistantIntent.GENERATE_CODE,
                activeActionId = AssistantActionId.GENERATE_IMPLEMENTATION,
                sourceMessageType = "requestAssistantTask",
                resultId = resultId,
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
                lastMessageType = "requestAssistantTask",
            )
        }
    }

    fun markGenerationPlanDiscussion(
        result: GenerationPlanDiscussionResult,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
    ) {
        mutate { currentState ->
            val session = result.session.copy(promptPreview = result.promptPreview)
            val identity = currentState.allocateAssistantResultId("generation-discussion", requestState)
            val resultId = identity.resultId
            val stateWithResultId = identity.snapshot
            stateWithResultId.copy(
                generationPlanDiscussionSession = session,
                generationPlanDiscussionRequestState = requestState,
                assistantResultStore = stateWithResultId.assistantResultStore.put(
                    resultId,
                    AssistantResultStoreEntry(
                        kind = AssistantTurnKind.GENERATION_PLAN,
                        generationPlan = stateWithResultId.generationPlan,
                        generationDiscussionSession = session,
                    ),
                ),
                lastMessageType = "generationPlanDiscussionResult",
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.GENERATION_PLAN,
                activeIntent = AssistantIntent.GENERATE_CODE,
                activeActionId = AssistantActionId.GENERATE_IMPLEMENTATION,
                sourceMessageType = "generationPlanDiscussionResult",
                resultId = resultId,
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
                lastMessageType = "requestAssistantTask",
            ).withAssistantContextFromCurrentState(AssistantIntent.GENERATE_CODE, AssistantActionId.GENERATE_IMPLEMENTATION)
        }
    }

    fun markGenerationPlanDiscussionRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
    ) {
        mutate { currentState ->
            val identity = currentState.allocateAssistantResultId("generation-discussion-failure", requestState)
            val resultId = identity.resultId
            val stateWithResultId = identity.snapshot
            stateWithResultId.copy(
                generationPlanDiscussionRequestState = requestState,
                assistantResultStore = stateWithResultId.assistantResultStore.put(
                    resultId,
                    requestState.assistantFailureEntry(
                        kind = AssistantTurnKind.GENERATION_PLAN,
                        resultId = resultId,
                        message = message,
                        sourceMessageType = "requestAssistantTask",
                        generationPlan = stateWithResultId.generationPlan,
                        generationDiscussionSession = stateWithResultId.generationPlanDiscussionSession,
                    ),
                ),
                lastMessageType = "requestAssistantTask",
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.GENERATION_PLAN,
                activeIntent = AssistantIntent.GENERATE_CODE,
                activeActionId = AssistantActionId.GENERATE_IMPLEMENTATION,
                sourceMessageType = "requestAssistantTask",
                resultId = resultId,
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
                lastMessageType = "requestAssistantTask",
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
            val identity = currentState.allocateAssistantResultId("code-draft", requestState)
            val resultId = identity.resultId
            val stateWithResultId = identity.snapshot
            stateWithResultId.copy(
                generatedCodeDrafts = drafts,
                generatedCodeDraftVersion = stateWithResultId.draftVersion,
                generatedCodeDraftWarnings = warnings,
                generatedCodeDraftSource = source,
                generatedCodeDraftPromptPreview = promptPreview,
                codeDraftRequestState = requestState,
                generatedCodeDraftWriteReport = null,
                assistantResultStore = stateWithResultId.assistantResultStore.put(
                    resultId,
                    AssistantResultStoreEntry(
                        kind = AssistantTurnKind.CODE_DRAFT,
                        generationPlan = stateWithResultId.generationPlan,
                        generationDiscussionSession = stateWithResultId.generationPlanDiscussionSession,
                        codeDrafts = drafts,
                        codeDraftWarnings = warnings,
                    ),
                ),
                lastMessageType = "requestCodeDrafts",
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.CODE_DRAFT,
                activeIntent = AssistantIntent.GENERATE_CODE,
                activeActionId = AssistantActionId.GENERATE_IMPLEMENTATION,
                sourceMessageType = "requestCodeDrafts",
                resultId = resultId,
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
            ).withAssistantContextFromCurrentState(AssistantIntent.GENERATE_CODE, AssistantActionId.GENERATE_IMPLEMENTATION)
        }
    }

    fun markCodeDraftRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
    ) {
        mutate { currentState ->
            val identity = currentState.allocateAssistantResultId("code-draft-failure", requestState)
            val resultId = identity.resultId
            val stateWithResultId = identity.snapshot
            stateWithResultId.copy(
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftVersion = null,
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                codeDraftRequestState = requestState,
                assistantResultStore = stateWithResultId.assistantResultStore.put(
                    resultId,
                    requestState.assistantFailureEntry(
                        kind = AssistantTurnKind.CODE_DRAFT,
                        resultId = resultId,
                        message = message,
                        sourceMessageType = "requestCodeDrafts",
                        generationPlan = stateWithResultId.generationPlan,
                        generationDiscussionSession = stateWithResultId.generationPlanDiscussionSession,
                    ),
                ),
                lastMessageType = "requestCodeDrafts",
            ).withAssistantTurnRef(
                kind = AssistantTurnKind.CODE_DRAFT,
                activeIntent = AssistantIntent.GENERATE_CODE,
                activeActionId = AssistantActionId.GENERATE_IMPLEMENTATION,
                sourceMessageType = "requestCodeDrafts",
                resultId = resultId,
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

private fun GraphPatchResult.toAssistantActionId(): AssistantActionId =
    if (requestedMode == QaMode.REVIEW || effectiveMode == QaMode.REVIEW) {
        AssistantActionId.CHECK_CHANGE
    } else {
        AssistantActionId.ASK_CONTEXT
    }

private fun AsyncRequestState.toQaAssistantIntent(): AssistantIntent =
    if (requestedMode == QaMode.REVIEW || effectiveMode == QaMode.REVIEW) {
        AssistantIntent.CHECK_CHANGE
    } else {
        AssistantIntent.ASK_CODE
    }

private fun AsyncRequestState.toQaAssistantActionId(): AssistantActionId =
    if (requestedMode == QaMode.REVIEW || effectiveMode == QaMode.REVIEW) {
        AssistantActionId.CHECK_CHANGE
    } else {
        AssistantActionId.ASK_CONTEXT
    }

private data class AssistantResultIdentity(
    val resultId: String,
    val snapshot: GraphEditorStateSnapshot,
)

private fun GraphEditorStateSnapshot.allocateAssistantResultId(
    prefix: String,
    requestState: AsyncRequestState,
): AssistantResultIdentity {
    requestState.requestId?.let { requestId ->
        return AssistantResultIdentity(
            resultId = "$prefix:request:$requestId",
            snapshot = this,
        )
    }

    val sequence = assistantSessionState.nextResultSequence.coerceAtLeast(1)
    return AssistantResultIdentity(
        resultId = "$prefix:local:$sequence",
        snapshot = copy(
            assistantSessionState = assistantSessionState.copy(
                nextResultSequence = sequence + 1,
            ),
        ),
    )
}

private fun AsyncRequestState.assistantFailureEntry(
    kind: AssistantTurnKind,
    resultId: String,
    message: String,
    sourceMessageType: String,
    generationPlan: GenerationPlan? = null,
    generationDiscussionSession: GenerationPlanDiscussionSession? = null,
): AssistantResultStoreEntry =
    AssistantResultStoreEntry(
        kind = kind,
        failure = AssistantFailureResult(
            resultId = resultId,
            message = errorMessage?.takeIf { it.isNotBlank() } ?: message,
            detailMessage = detailMessage,
            phase = phase.name,
            requestId = requestId,
            sourceMessageType = sourceMessageType,
            createdAtEpochMillis = finishedAtEpochMillis,
        ),
        generationPlan = generationPlan,
        generationDiscussionSession = generationDiscussionSession,
    )
