package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.agent.model.GraphBeautificationResult
import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult
import com.charmnight.linkgraph.workbench.AssistantFailureResult
import com.charmnight.linkgraph.workbench.AssistantResultStoreEntry
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantTurnKind
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.ReplayableQaRequest

/**
 * 编辑器状态机中"异步请求态"的统一变更入口：为 QA / Diff 复核 / 链路讲解 /
 * 实现计划 / 计划讨论 / 代码草稿六类子流程提供成对的 begin / mark / markFailed /
 * updatePreview 方法，并在内部维护助手结果仓与回放信息。
 *
 * 所有方法都不直接持有状态，仅通过传入的 [mutate] 回调把变更应用到上层状态快照。
 */
internal class GraphEditorAsyncRequestStateSupport(
    /** 把状态变更函数应用到当前编辑器状态快照的回调（通常委托给 GraphEditor ViewModel）。 */
    private val mutate: ((GraphEditorStateSnapshot) -> GraphEditorStateSnapshot) -> Unit,
) {
    /**
     * 记录一次问答结果：写入 qaResult、更新请求态、登记到结果仓；
     * 可选追加一条助手轮次引用，否则只更新当前助手上下文。
     */
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
                    qaMode = result.requestedMode,
                )
            } else {
                nextState.withAssistantContextFromCurrentState(
                    activeIntent = result.toAssistantIntent(),
                    activeActionId = result.toAssistantActionId(),
                    qaMode = result.requestedMode,
                )
            }
        }
    }

    /**
     * 标记一次问答请求已开始：清空上次结果、写入新的运行中请求态，
     * 并登记可重放请求以便失败后重试。
     */
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
            ).withAssistantContextFromCurrentState(
                activeIntent = requestState.toQaAssistantIntent(),
                activeActionId = requestState.toQaAssistantActionId(),
                qaMode = requestState.requestedMode ?: submittedRequest?.mode,
            )
        }
    }

    /**
     * 标记一次问答请求失败：写入失败请求态，把失败请求记入 recovery（供重试），
     * 并追加一条 QA 类型助手轮次引用，附带失败详情。
     */
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
                qaMode = requestState.requestedMode ?: failedRequest?.mode,
            )
        }
    }

    /**
     * 更新问答流式预览：仅当下发的 requestId 与当前请求态匹配时才覆盖预览文本，
     * 同时标记是否处于"结构化结果收尾"阶段。
     */
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

    /**
     * 记录一次 Diff 复核结果：写入 diffReviewResult 与请求态，
     * 关联当前选中的 diff 项，并追加一条 CHECK_RESULT 类型助手轮次。
     */
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

    /** 标记一次 Diff 复核请求已开始：清空旧结果，写入运行中请求态并同步选中 diff 项。 */
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

    /** 标记一次 Diff 复核请求失败：写入失败请求态，并登记失败详情到结果仓与助手轮次。 */
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

    /** 更新 Diff 复核的流式预览，仅在 requestId 匹配时覆盖。 */
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

    /**
     * 记录一次链路讲解（图美化）结果：写入讲解结果与请求态，
     * 并按指定的助手意图/动作登记一条 EXPLANATION 类型助手轮次。
     */
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

    /** 标记一次链路讲解请求已开始：清空讲解结果，写入运行中请求态并更新助手上下文。 */
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

    /** 标记一次链路讲解请求失败：写入失败请求态并登记失败详情到结果仓与助手轮次。 */
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

    /** 更新链路讲解的流式预览，仅在 requestId 匹配时覆盖。 */
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

    /**
     * 记录一次实现计划生成结果：写入新计划，同时清空讨论会话与代码草稿相关字段，
     * 让"计划→讨论→草稿"的链路重新开始，并追加 GENERATION_PLAN 类型助手轮次。
     */
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

    /** 标记一次实现计划请求已开始：清空计划、讨论与代码草稿，写入运行中请求态。 */
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

    /** 标记一次实现计划请求失败：写入失败请求态并登记失败详情到结果仓与助手轮次。 */
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

    /** 更新实现计划请求的流式预览，仅在 requestId 匹配时覆盖。 */
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

    /**
     * 记录一次计划讨论（追问/澄清）结果：把结果中的 promptPreview 合并回讨论会话，
     * 写入新的讨论态并登记到结果仓，沿用 GENERATION_PLAN 类型助手轮次。
     */
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

    /** 标记一次计划讨论请求已开始：写入运行中讨论请求态。 */
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

    /** 标记一次计划讨论请求失败：登记失败详情并沿用当前计划/讨论会话上下文。 */
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

    /** 更新计划讨论请求的流式预览，仅在 requestId 匹配时覆盖。 */
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

    /**
     * 记录一次代码草稿生成结果：写入草稿列表、警告、来源与 prompt 预览，
     * 关联当前计划与讨论会话，并追加 CODE_DRAFT 类型助手轮次。
     */
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

    /** 标记一次代码草稿请求已开始：清空草稿与相关元数据，写入运行中请求态。 */
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

    /** 标记一次代码草稿请求失败：登记失败详情，并保留当前计划/讨论会话上下文以便复用。 */
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

    /** 更新代码草稿请求的流式预览，仅在 requestId 匹配时覆盖。 */
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

/** 把问答结果映射为助手意图：REVIEW 模式视为变更复核，其他视为代码问答。 */
private fun GraphPatchResult.toAssistantIntent(): AssistantIntent =
    if (requestedMode == QaMode.REVIEW || effectiveMode == QaMode.REVIEW) {
        AssistantIntent.CHECK_CHANGE
    } else {
        AssistantIntent.ASK_CODE
    }

/** 把问答结果映射为助手动作：REVIEW 模式走变更复核动作，其他走上下文问答动作。 */
private fun GraphPatchResult.toAssistantActionId(): AssistantActionId =
    if (requestedMode == QaMode.REVIEW || effectiveMode == QaMode.REVIEW) {
        AssistantActionId.CHECK_CHANGE
    } else {
        AssistantActionId.ASK_CONTEXT
    }

/** 把问答运行中的请求态映射为助手意图，便于在请求开始时就已经能确定 UI 上下文。 */
private fun AsyncRequestState.toQaAssistantIntent(): AssistantIntent =
    if (requestedMode == QaMode.REVIEW || effectiveMode == QaMode.REVIEW) {
        AssistantIntent.CHECK_CHANGE
    } else {
        AssistantIntent.ASK_CODE
    }

/** 把问答运行中的请求态映射为助手动作，规则与意图保持一致。 */
private fun AsyncRequestState.toQaAssistantActionId(): AssistantActionId =
    if (requestedMode == QaMode.REVIEW || effectiveMode == QaMode.REVIEW) {
        AssistantActionId.CHECK_CHANGE
    } else {
        AssistantActionId.ASK_CONTEXT
    }

/** "分配结果 ID"操作的结果：既包含新分配的 resultId，也带回更新后的快照。 */
private data class AssistantResultIdentity(
    val resultId: String,
    val snapshot: GraphEditorStateSnapshot,
)

/**
 * 为一次结果分配稳定的 ID：
 * - 若请求携带 requestId，则按"前缀:request:请求ID"格式直接生成；
 * - 否则取本地自增序列号，按"前缀:local:序号"生成，同时把自增序列写回快照。
 */
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

/**
 * 把当前请求态转换为结果仓中的失败条目：优先使用请求态自带的 errorMessage，
 * 同时携带 phase、requestId 等运行信息以及可选的计划/讨论上下文。
 */
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
