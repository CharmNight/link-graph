package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.CodeEditOperation
import com.charmnight.linkgraph.codegen.PreparedCodeEdit
import com.charmnight.linkgraph.agent.model.EvidenceTraceEntry
import com.charmnight.linkgraph.agent.model.GraphBeautificationResult
import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidatePatchIntent
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.InvestigationTurnOutcome
import com.charmnight.linkgraph.workbench.QaConversationMessage
import com.charmnight.linkgraph.workbench.QaConversationSession
import com.charmnight.linkgraph.workbench.RiskResolution

/**
 * PageRenderer DTO 工厂函数集合（P2-6 拆分自 PageRendererDtos.kt）。
 *
 * 把领域对象（workbench / llm / codegen 包的数据类）转换为对应的 DTO。
 * 函数命名约定：`{domainType}ToDto`，与 DTO 类型一一对应。
 *
 * 这些函数都不修改原对象，纯函数；Gson 序列化由调用方负责。
 */

/** 把异步请求状态转换为 DTO。 */
internal fun asyncRequestStateToDto(
    state: AsyncRequestState,
    hasPromptPreview: Boolean = state.promptPreviewAvailable,
): AsyncRequestStateDto = AsyncRequestStateDto(
    phase = state.phase,
    requestId = state.requestId,
    scene = state.scene,
    executionMode = state.executionMode,
    statusMessage = state.statusMessage,
    errorMessage = state.errorMessage,
    detailMessage = state.detailMessage,
    startedAtEpochMillis = state.startedAtEpochMillis,
    finishedAtEpochMillis = state.finishedAtEpochMillis,
    streaming = state.streaming,
    fallbackUsed = state.fallbackUsed,
    streamPhase = state.streamPhase,
    previewText = state.previewText,
    previewUpdatedAtEpochMillis = state.previewUpdatedAtEpochMillis,
    finalizingStructuredResult = state.finalizingStructuredResult,
    providerLabel = state.providerLabel,
    model = state.model,
    endpointSummary = state.endpointSummary,
    promptPreviewAvailable = hasPromptPreview,
    requestedMode = state.requestedMode,
    effectiveMode = state.effectiveMode,
)

/** 把图文档转换为 DTO，并按规模决定是否裁剪内容。 */
internal fun graphDocumentToDto(
    document: GraphDocument,
    includeFullContent: Boolean,
    layoutState: GraphLayoutState? = null,
    maxSecondaryNodes: Int,
    maxSecondaryEdges: Int,
): GraphDocumentDto {
    val shouldInlineContent = includeFullContent ||
        (document.nodes.size <= maxSecondaryNodes && document.edges.size <= maxSecondaryEdges)
    return GraphDocumentDto(
        nodes = if (shouldInlineContent) document.nodes.map { nodeToDto(it, layoutState) } else emptyList(),
        edges = if (shouldInlineContent) document.edges.map(::edgeToDto) else emptyList(),
        patch = document.patch?.let(::patchToDto),
        nodeCount = document.nodes.size,
        edgeCount = document.edges.size,
        truncated = !shouldInlineContent,
    )
}

/** 把投影索引转换为 DTO。 */
internal fun graphProjectionIndexToDto(
    projectionIndex: com.charmnight.linkgraph.application.model.GraphProjectionIndex,
): GraphProjectionIndexDto = GraphProjectionIndexDto(
    nodeMappings = projectionIndex.nodeMappings.mapValues { (_, mapping) ->
        GraphProjectionNodeMappingDto(
            projectedNodeId = mapping.projectedNodeId,
            mappingKind = mapping.mappingKind.name,
            canonicalNodeIds = mapping.canonicalNodeIds,
            editableCommandKinds = mapping.editableCommandKinds.map { it.name },
        )
    },
    edgeMappings = projectionIndex.edgeMappings.mapValues { (_, mapping) ->
        GraphProjectionEdgeMappingDto(
            projectedEdgeId = mapping.projectedEdgeId,
            mappingKind = mapping.mappingKind.name,
            canonicalEdgeIds = mapping.canonicalEdgeIds,
            canonicalPathNodeIds = mapping.canonicalPathNodeIds,
            editableCommandKinds = mapping.editableCommandKinds.map { it.name },
        )
    },
)

/** 把候选草稿变更转换为 DTO。 */
internal fun candidateDraftChangeToDto(
    change: CandidateDraftChange,
): CandidateDraftChangeDto = CandidateDraftChangeDto(
    changeId = change.changeId,
    status = change.status,
    title = change.title,
    targetStepIds = change.targetStepIds,
    targetNodeIds = change.targetNodeIds,
    beforeState = change.beforeState,
    afterState = change.afterState,
    reason = change.reason,
    impactSummary = change.impactSummary,
    claimType = change.claimType,
    evidence = change.evidence?.map(::resultEvidenceFindingToDto) ?: emptyList(),
    editScopes = change.editScopes?.map(::editScopeToDto) ?: emptyList(),
    patchIntent = change.patchIntent?.let(::candidatePatchIntentToDto),
    graphPatch = change.graphPatch?.let(::patchToDto),
)

/** 把候选补丁意图转换为 DTO。 */
internal fun candidatePatchIntentToDto(
    intent: CandidatePatchIntent,
): CandidatePatchIntentDto = CandidatePatchIntentDto(
    mode = intent.mode,
    targetNodeId = intent.targetNodeId,
    attachEdgeId = intent.attachEdgeId,
    falseBranchTargetNodeId = intent.falseBranchTargetNodeId,
)

/** 把 QA 对话单条消息转换为 DTO。 */
internal fun qaConversationMessageToDto(
    message: QaConversationMessage,
): QaConversationMessageDto = QaConversationMessageDto(
    messageId = message.messageId,
    role = message.role,
    content = message.content,
    focusTargetId = message.focusTargetId,
    turnOutcomeId = message.turnOutcomeId,
)

/** 把 QA 对话会话转换为 DTO。 */
internal fun qaConversationSessionToDto(
    session: QaConversationSession,
): QaConversationSessionDto = QaConversationSessionDto(
    sessionId = session.sessionId,
    scopeKey = session.scopeKey,
    messages = session.messages.map(::qaConversationMessageToDto),
    candidateChanges = session.candidateChanges.map(::candidateDraftChangeToDto),
    investigationThreads = session.investigationThreads.map(::investigationThreadToDto),
    turnOutcomes = session.turnOutcomes.map(::investigationTurnOutcomeToDto),
    focusTargetId = session.focusTargetId,
)

/** 把调查线程转换为 DTO。 */
internal fun investigationThreadToDto(
    thread: InvestigationThread,
): InvestigationThreadDto = InvestigationThreadDto(
    threadId = thread.threadId,
    status = thread.status,
    title = thread.title,
    targetStepIds = thread.targetStepIds,
    targetNodeIds = thread.targetNodeIds,
    summary = thread.summary,
    evidenceGap = thread.evidenceGap,
    recommendedQuestion = thread.recommendedQuestion,
    claimType = thread.claimType,
    evidence = thread.evidence.map(::resultEvidenceFindingToDto),
    latestTurnOutcomeId = thread.latestTurnOutcomeId,
    resolution = thread.resolution?.let(::riskResolutionToDto),
)

/** 把风险解决结果转换为 DTO。 */
internal fun riskResolutionToDto(
    resolution: RiskResolution,
): RiskResolutionDto = RiskResolutionDto(
    threadId = resolution.threadId,
    status = resolution.status,
    note = resolution.note,
)

/** 把单轮调查结果转换为 DTO。 */
internal fun investigationTurnOutcomeToDto(
    outcome: InvestigationTurnOutcome,
): InvestigationTurnOutcomeDto = InvestigationTurnOutcomeDto(
    outcomeId = outcome.outcomeId,
    threadId = outcome.threadId,
    status = outcome.status,
    summary = outcome.summary,
    detail = outcome.detail,
    candidateChangeId = outcome.candidateChangeId,
    blockedReason = outcome.blockedReason,
    evidenceDelta = InvestigationTurnEvidenceDeltaDto(
        addedNodeIds = outcome.evidenceDelta.addedNodeIds,
        addedFilePaths = outcome.evidenceDelta.addedFilePaths,
        previousStrongestEvidenceLevel = outcome.evidenceDelta.previousStrongestEvidenceLevel,
        currentStrongestEvidenceLevel = outcome.evidenceDelta.currentStrongestEvidenceLevel,
        hitRecommendedQuestion = outcome.evidenceDelta.hitRecommendedQuestion,
    ),
    observedNodeIds = outcome.observedNodeIds,
    observedFilePaths = outcome.observedFilePaths,
    strongestEvidenceLevel = outcome.strongestEvidenceLevel,
)

/** 把源码片段上下文转换为 DTO。 */
internal fun sourceSnippetContextToDto(
    snippet: SourceSnippetContext,
): SourceSnippetContextDto = SourceSnippetContextDto(
    nodeId = snippet.nodeId,
    filePath = snippet.filePath,
    startOffset = snippet.startOffset,
    endOffset = snippet.endOffset,
    startLine = snippet.startLine,
    endLine = snippet.endLine,
    snippet = snippet.snippet,
    origin = snippet.origin,
    decompiled = snippet.decompiled,
    virtualFileUrl = snippet.virtualFileUrl,
)

/** 把证据追踪条目转换为 DTO。 */
internal fun evidenceTraceEntryToDto(
    trace: EvidenceTraceEntry,
): EvidenceTraceEntryDto = EvidenceTraceEntryDto(
    nodeId = trace.nodeId,
    resolvedNodeId = trace.resolvedNodeId,
    filePath = trace.filePath,
    reason = trace.reason,
    startLine = trace.startLine,
    endLine = trace.endLine,
    includedInPrompt = trace.includedInPrompt,
    mappingTrace = trace.mappingTrace,
)

/** 把代码编辑作用域转换为 DTO。 */
internal fun editScopeToDto(
    scope: com.charmnight.linkgraph.agent.model.EditScope,
): EditScopeDto = EditScopeDto(
    scopeId = scope.scopeId,
    targetNodeId = scope.targetNodeId,
    filePath = scope.filePath,
    language = scope.language,
    symbolKind = scope.symbolKind,
    symbolSignature = scope.symbolSignature,
    startOffset = scope.startOffset,
    endOffset = scope.endOffset,
    startLine = scope.startLine,
    endLine = scope.endLine,
    allowedChangeKinds = scope.allowedChangeKinds,
    supportingFindingIds = scope.supportingFindingIds,
)

/** 把单条代码编辑操作转换为 DTO。 */
internal fun codeEditOperationToDto(
    operation: CodeEditOperation,
): CodeEditOperationDto = CodeEditOperationDto(
    operationId = operation.operationId,
    filePath = operation.filePath,
    scopeId = operation.scopeId,
    kind = operation.kind,
    payload = operation.payload,
    warnings = operation.warnings,
)

/** 把准备好的代码编辑转换为 DTO。 */
internal fun preparedCodeEditToDto(
    edit: PreparedCodeEdit,
): PreparedCodeEditDto = PreparedCodeEditDto(
    operationId = edit.operationId,
    filePath = edit.filePath,
    scopeId = edit.scopeId,
    kind = edit.kind,
    targetSymbolSignature = edit.targetSymbolSignature,
    startOffset = edit.startOffset,
    endOffset = edit.endOffset,
    beforeText = edit.beforeText,
    afterText = edit.afterText,
    warnings = edit.warnings,
)

/** 把草稿补丁应用结果转换为 DTO。 */
internal fun draftPatchApplyResultToDto(
    result: DraftPatchApplyResult,
): DraftPatchApplyResultDto = DraftPatchApplyResultDto(
    summary = result.summary,
    appliedOperationCount = result.appliedOperationCount,
    appliedNodeIds = result.appliedNodeIds,
    appliedEdgeIds = result.appliedEdgeIds,
    focusNodeId = result.focusNodeId,
    appliedTargets = result.appliedTargets,
)

/** 把草稿工作台条目转换为 DTO。 */
internal fun draftWorkbenchEntryToDto(
    entry: DraftWorkbenchEntry,
): DraftWorkbenchEntryDto = DraftWorkbenchEntryDto(
    entryId = entry.entryId,
    kind = entry.kind,
    title = entry.title,
    sourceChangeId = entry.sourceChangeId,
    targetStepIds = entry.targetStepIds,
    targetNodeIds = entry.targetNodeIds,
    beforeState = entry.beforeState,
    afterState = entry.afterState,
    reason = entry.reason,
    impactSummary = entry.impactSummary,
    claimType = entry.claimType,
    evidence = entry.evidence.map(::resultEvidenceFindingToDto),
    editScopes = entry.editScopes.map(::editScopeToDto),
    patchIntent = entry.patchIntent?.let(::candidatePatchIntentToDto),
    graphPatch = entry.graphPatch?.let(::patchToDto),
)

/** 把草稿工作台状态转换为 DTO。 */
internal fun draftWorkbenchStateToDto(
    state: DraftWorkbenchState,
): DraftWorkbenchStateDto = DraftWorkbenchStateDto(
    draftChanges = state.draftChanges.map(::draftWorkbenchEntryToDto),
    draftNotes = state.draftNotes.map(::draftWorkbenchEntryToDto),
)

/** 把草稿校验状态转换为 DTO。 */
internal fun draftValidationStateToDto(
    state: DraftValidationState,
): DraftValidationStateDto = DraftValidationStateDto(
    status = state.status,
    message = state.message,
    detailMessage = state.detailMessage,
    unresolvedThreadIds = state.unresolvedThreadIds,
    unresolvedThreads = state.unresolvedThreads.map(::investigationThreadToDto),
)

/** 把链路讲解结果转换为 DTO。 */
internal fun beautificationResultToDto(
    result: GraphBeautificationResult,
    promptPreviewArtifactId: String?,
): BeautificationResultDto = BeautificationResultDto(
    source = result.source,
    granularity = result.granularity,
    steps = result.steps.map { step ->
        BeautificationStepDto(
            stepId = step.stepId,
            title = step.title,
            granularity = step.granularity,
            kind = step.kind,
            description = step.description,
            primaryNodeId = step.primaryNodeId,
            codeSnippet = step.codeSnippet,
            evidence = step.evidence.map(::resultEvidenceFindingToDto),
            followUpQuestions = step.followUpQuestions,
            downstreamTargets = step.downstreamTargets,
        )
    },
    promptPreviewArtifactId = promptPreviewArtifactId,
    warnings = result.warnings,
)

/** 把补丁类结果转换为 DTO。 */
internal fun patchResultToDto(
    result: GraphPatchResult,
    promptPreviewArtifactId: String?,
): PatchResultDto = PatchResultDto(
    source = result.source,
    question = result.question,
    requestedMode = result.requestedMode,
    effectiveMode = result.effectiveMode,
    answer = result.answer,
    promptPreviewArtifactId = promptPreviewArtifactId,
    warnings = result.warnings,
    findings = result.findings.map(::resultEvidenceFindingToDto),
    candidateChanges = result.candidateChanges.map(::candidateDraftChangeToDto),
    newCandidateChanges = result.newCandidateChanges.map(::candidateDraftChangeToDto),
    investigationThreads = result.investigationThreads.map(::investigationThreadToDto),
    latestTurnOutcome = result.latestTurnOutcome?.let(::investigationTurnOutcomeToDto),
    recentTurnOutcomes = result.recentTurnOutcomes.map(::investigationTurnOutcomeToDto),
    sourceContext = result.sourceContext.map(::sourceSnippetContextToDto),
    evidenceTrace = result.evidenceTrace.map(::evidenceTraceEntryToDto),
    qaSession = result.qaSession?.let(::qaConversationSessionToDto),
    patch = result.patch?.let(::patchToDto),
)

/** 把生成代码草稿转换为 DTO。 */
internal fun generatedCodeDraftToDto(
    draft: com.charmnight.linkgraph.codegen.GeneratedCodeDraft,
    contentArtifactId: String?,
): GeneratedCodeDraftDto = GeneratedCodeDraftDto(
    id = draft.id,
    sourceNodeId = draft.sourceNodeId,
    title = draft.title,
    targetPath = draft.targetPath,
    contentArtifactId = contentArtifactId,
    content = if (contentArtifactId == null) draft.content else null,
    editOperations = draft.editOperations.map(::codeEditOperationToDto),
    editScopes = draft.editScopes.map(::editScopeToDto),
    preparedEdits = draft.preparedEdits.map(::preparedCodeEditToDto),
    warnings = draft.warnings,
)

/** 把生成计划讨论会话转换为 DTO。 */
internal fun generationPlanDiscussionSessionToDto(
    session: GenerationPlanDiscussionSession,
    promptPreviewArtifactId: String?,
): GenerationPlanDiscussionSessionDto = GenerationPlanDiscussionSessionDto(
    sessionId = session.sessionId,
    messages = session.messages.map { message ->
        GenerationPlanDiscussionMessageDto(
            messageId = message.messageId,
            role = message.role,
            content = message.content,
            focusItemId = message.focusItemId,
        )
    },
    focusItemId = session.focusItemId,
    promptPreviewArtifactId = promptPreviewArtifactId,
)
