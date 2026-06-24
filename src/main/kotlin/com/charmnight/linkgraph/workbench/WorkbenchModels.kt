package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
import com.charmnight.linkgraph.model.GraphPatch

/** 步骤粒度，决定如何把事实图节点投影为工作台步骤。 */
enum class StepGranularity {
    BUSINESS,
    METHOD_CALL,
    CODE_SEMANTIC,
}

/** 工作台步骤种类，描述该步骤在执行流中的角色。 */
enum class StepKind {
    BUSINESS_ACTION,
    METHOD_CALL,
    CONDITION,
    RETURN,
    RESOURCE_INTERACTION,
    STRUCTURE_OVERVIEW,
}

/** 候选草稿变更的确认状态。 */
enum class CandidateDraftChangeStatus {
    PENDING_CONFIRMATION,
    CONFIRMED,
    REJECTED,
    SUPERSEDED,
}

/** 调查线程的状态。 */
enum class InvestigationThreadStatus {
    OPEN,
    PROMOTED,
    DISMISSED,
    BLOCKED,
    SUPERSEDED,
}

/** 风险处置状态。 */
enum class RiskResolutionStatus {
    UNRESOLVED,
    DEFERRED,
    ACCEPTED_RISK,
    EVIDENCE_EXHAUSTED,
    DISMISSED,
    PROMOTED,
}

/** 调查轮次结果状态。 */
enum class InvestigationTurnOutcomeStatus {
    PROMOTED_TO_CANDIDATE,
    OPEN_WITH_PROGRESS,
    OPEN_NO_PROGRESS,
    DISMISSED,
    BLOCKED,
}

/** 草稿条目种类：变更或备注。 */
enum class DraftEntryKind {
    CHANGE,
    NOTE,
}

/** 候选补丁的意图模式，描述补丁对节点的操作类型。 */
enum class CandidatePatchIntentMode {
    UPDATE_EXISTING_NODE,
    INSERT_NEW_DECISION,
    INSERT_NEW_ACTION,
    ANNOTATION_ONLY,
}

/** 候选补丁意图，描述目标节点、附加边与分支目标。 */
data class CandidatePatchIntent(
    val mode: CandidatePatchIntentMode,
    val targetNodeId: String? = null,
    val attachEdgeId: String? = null,
    val falseBranchTargetNodeId: String? = null,
)

/** QA 消息角色：用户或助手。 */
enum class QaMessageRole {
    USER,
    ASSISTANT,
}

/** QA 请求种类：普通提问或调查线程。 */
enum class QaRequestKind {
    ASK,
    INVESTIGATE_THREAD,
}

/** 阶段适用性目标，描述当前评估的目标阶段（如代码草稿）。 */
enum class StageEligibilityTarget(
    val label: String,
) {
    CODE("代码草稿"),
}

/** 草稿校验状态：空、需复核或就绪。 */
enum class DraftValidationStatus {
    EMPTY,
    REVIEW_REQUIRED,
    READY,
}

/** 工作台步骤，包含步骤 ID、标题、粒度、种类与引用节点。 */
data class WorkbenchStep(
    val stepId: String,
    val title: String,
    val granularity: StepGranularity,
    val kind: StepKind,
    val description: String = "",
    val nodeRefs: List<String> = emptyList(),
    val downstreamTargets: List<String> = emptyList(),
)

/** 步骤投影结果。 */
data class StepProjectionResult(
    val steps: List<WorkbenchStep> = emptyList(),
)

/** 候选草稿变更，承载变更前后的状态、原因、影响、证据与图补丁。 */
data class CandidateDraftChange(
    val changeId: String,
    val status: CandidateDraftChangeStatus,
    val title: String = "",
    val targetStepIds: List<String> = emptyList(),
    val targetNodeIds: List<String> = emptyList(),
    val beforeState: String? = null,
    val afterState: String? = null,
    val reason: String = "",
    val impactSummary: String = "",
    val claimType: String? = null,
    val evidence: List<ResultEvidenceFinding> = emptyList(),
    val editScopes: List<EditScope> = emptyList(),
    val patchIntent: CandidatePatchIntent? = null,
    val graphPatch: GraphPatch? = null,
)

/** 判断候选变更是否包含直接证据（来源/图）。 */
fun CandidateDraftChange.hasDirectEvidence(): Boolean {
    return evidence.any { finding ->
        finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE ||
            finding.evidenceLevel == ResultEvidenceLevel.DIRECT_GRAPH
    }
}

/** 判断候选变更是否处于待确认状态且有直接证据，可用于确认。 */
fun CandidateDraftChange.isEligibleForDraftConfirmation(): Boolean {
    return status == CandidateDraftChangeStatus.PENDING_CONFIRMATION && hasDirectEvidence()
}

/** 调查证据增量，描述本轮新增节点/文件以及最强证据等级的变化。 */
data class InvestigationEvidenceDelta(
    val addedNodeIds: List<String> = emptyList(),
    val addedFilePaths: List<String> = emptyList(),
    val previousStrongestEvidenceLevel: ResultEvidenceLevel? = null,
    val currentStrongestEvidenceLevel: ResultEvidenceLevel? = null,
    val hitRecommendedQuestion: Boolean = false,
)

/** 调查轮次结果。 */
data class InvestigationTurnOutcome(
    val outcomeId: String,
    val threadId: String,
    val status: InvestigationTurnOutcomeStatus,
    val summary: String = "",
    val detail: String = "",
    val candidateChangeId: String? = null,
    val blockedReason: String? = null,
    val evidenceDelta: InvestigationEvidenceDelta = InvestigationEvidenceDelta(),
    val observedNodeIds: List<String> = emptyList(),
    val observedFilePaths: List<String> = emptyList(),
    val strongestEvidenceLevel: ResultEvidenceLevel? = null,
)

/** 调查线程。 */
data class InvestigationThread(
    val threadId: String,
    val status: InvestigationThreadStatus,
    val title: String = "",
    val targetStepIds: List<String> = emptyList(),
    val targetNodeIds: List<String> = emptyList(),
    val summary: String = "",
    val evidenceGap: String = "",
    val recommendedQuestion: String = "",
    val claimType: String? = null,
    val evidence: List<ResultEvidenceFinding> = emptyList(),
    val latestTurnOutcomeId: String? = null,
    val resolution: RiskResolution? = null,
)

/** 风险处置结果。 */
data class RiskResolution(
    val threadId: String,
    val status: RiskResolutionStatus,
    val note: String = "",
)

/** 草稿工作台条目（变更或备注）。 */
data class DraftWorkbenchEntry(
    val entryId: String,
    val kind: DraftEntryKind,
    val title: String = "",
    val sourceChangeId: String? = null,
    val targetStepIds: List<String> = emptyList(),
    val targetNodeIds: List<String> = emptyList(),
    val beforeState: String? = null,
    val afterState: String? = null,
    val reason: String = "",
    val impactSummary: String = "",
    val claimType: String? = null,
    val evidence: List<ResultEvidenceFinding> = emptyList(),
    val editScopes: List<EditScope> = emptyList(),
    val patchIntent: CandidatePatchIntent? = null,
    val graphPatch: GraphPatch? = null,
)

/** QA 会话消息。 */
data class QaConversationMessage(
    val messageId: String,
    val role: QaMessageRole,
    val content: String,
    val focusTargetId: String? = null,
    val turnOutcomeId: String? = null,
)

/** QA 会话，包含消息、候选变更、调查线程与轮次结果。 */
data class QaConversationSession(
    val sessionId: String,
    val scopeKey: String,
    val messages: List<QaConversationMessage> = emptyList(),
    val candidateChanges: List<CandidateDraftChange> = emptyList(),
    val investigationThreads: List<InvestigationThread> = emptyList(),
    val turnOutcomes: List<InvestigationTurnOutcome> = emptyList(),
    val focusTargetId: String? = null,
)

/** 生成方案讨论消息。 */
data class GenerationPlanDiscussionMessage(
    val messageId: String,
    val role: QaMessageRole,
    val content: String,
    val focusItemId: String? = null,
)

/** 生成方案讨论会话。 */
data class GenerationPlanDiscussionSession(
    val sessionId: String,
    val messages: List<GenerationPlanDiscussionMessage> = emptyList(),
    val focusItemId: String? = null,
    val promptPreview: String? = null,
)

/** 生成方案讨论单次结果。 */
data class GenerationPlanDiscussionResult(
    val source: com.charmnight.linkgraph.llm.LlmResultSource,
    val question: String,
    val answer: String,
    val promptPreview: String,
    val focusItemId: String? = null,
    val session: GenerationPlanDiscussionSession,
    val warnings: List<String> = emptyList(),
)

/** 单次 QA 模型回合的结果。 */
data class QaModelTurn(
    val answer: String,
    val candidateChanges: List<CandidateDraftChange> = emptyList(),
    val investigationThreads: List<InvestigationThread> = emptyList(),
    val sourceThreadId: String? = null,
    val observedNodeIds: List<String> = emptyList(),
    val observedFilePaths: List<String> = emptyList(),
    val blockedReason: String? = null,
)

/** QA 会话轮次的对外结果。 */
data class QaConversationTurnResult(
    val session: QaConversationSession,
    val newCandidateChanges: List<CandidateDraftChange> = emptyList(),
    val newInvestigationThreads: List<InvestigationThread> = emptyList(),
    val latestTurnOutcome: InvestigationTurnOutcome? = null,
    val recentTurnOutcomes: List<InvestigationTurnOutcome> = emptyList(),
    val draftWrites: List<DraftWorkbenchEntry> = emptyList(),
)

/** 可重放的 QA 请求，用于失败恢复或重新执行。 */
data class ReplayableQaRequest(
    val requestId: String,
    val kind: QaRequestKind,
    val question: String,
    val mode: QaMode = QaMode.AUTO,
    val selectedNodeIds: List<String> = emptyList(),
    val sourceThreadId: String? = null,
    val baseSession: QaConversationSession? = null,
)

/** QA 请求恢复状态。 */
data class QaRequestRecoveryState(
    val lastSubmittedRequest: ReplayableQaRequest? = null,
    val lastFailedRequest: ReplayableQaRequest? = null,
)

/** 草稿校验状态详情。 */
data class DraftValidationState(
    val status: DraftValidationStatus,
    val message: String,
    val detailMessage: String? = null,
    val unresolvedThreadIds: List<String> = emptyList(),
    val unresolvedThreads: List<InvestigationThread> = emptyList(),
)

/** 阶段适用性决策：是否允许进入下一阶段（如确认草稿）以及阻塞原因。 */
data class StageEligibilityDecision(
    val target: StageEligibilityTarget,
    val allowed: Boolean,
    val message: String,
    val detailMessage: String? = null,
    val blockingThreadIds: List<String> = emptyList(),
    val unresolvedThreadIds: List<String> = emptyList(),
) {
    val stageLabel: String
        get() = target.label
}

/** 草稿工作台状态：变更条目与备注条目。 */
data class DraftWorkbenchState(
    val draftChanges: List<DraftWorkbenchEntry> = emptyList(),
    val draftNotes: List<DraftWorkbenchEntry> = emptyList(),
)

/** 草稿确认结果。 */
data class DraftConfirmationResult(
    val draftState: DraftWorkbenchState,
    val draftChanges: List<DraftWorkbenchEntry> = emptyList(),
    val graphChanged: Boolean = false,
    val failureReason: String? = null,
)

/** 草稿移除结果。 */
data class DraftRemovalResult(
    val draftState: DraftWorkbenchState,
    val removedEntry: DraftWorkbenchEntry? = null,
    val graphChanged: Boolean = false,
)
