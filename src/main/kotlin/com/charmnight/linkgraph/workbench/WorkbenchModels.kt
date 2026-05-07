package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
import com.charmnight.linkgraph.model.GraphPatch

enum class StepGranularity {
    BUSINESS,
    METHOD_CALL,
    CODE_SEMANTIC,
}

enum class StepKind {
    BUSINESS_ACTION,
    METHOD_CALL,
    CONDITION,
    RETURN,
    RESOURCE_INTERACTION,
}

enum class CandidateDraftChangeStatus {
    PENDING_CONFIRMATION,
    CONFIRMED,
    REJECTED,
    SUPERSEDED,
}

enum class InvestigationThreadStatus {
    OPEN,
    PROMOTED,
    DISMISSED,
    BLOCKED,
    SUPERSEDED,
}

enum class RiskResolutionStatus {
    UNRESOLVED,
    DEFERRED,
    ACCEPTED_RISK,
    EVIDENCE_EXHAUSTED,
    DISMISSED,
    PROMOTED,
}

enum class InvestigationTurnOutcomeStatus {
    PROMOTED_TO_CANDIDATE,
    OPEN_WITH_PROGRESS,
    OPEN_NO_PROGRESS,
    DISMISSED,
    BLOCKED,
}

enum class DraftEntryKind {
    CHANGE,
    NOTE,
}

enum class CandidatePatchIntentMode {
    UPDATE_EXISTING_NODE,
    INSERT_NEW_DECISION,
    INSERT_NEW_ACTION,
    ANNOTATION_ONLY,
}

data class CandidatePatchIntent(
    val mode: CandidatePatchIntentMode,
    val targetNodeId: String? = null,
    val attachEdgeId: String? = null,
    val falseBranchTargetNodeId: String? = null,
)

enum class AuditMessageRole {
    USER,
    ASSISTANT,
}

enum class QaRequestKind {
    ASK,
    INVESTIGATE_THREAD,
}

enum class StageEligibilityTarget(
    val label: String,
) {
    CODE("代码草稿"),
}

enum class DraftValidationStatus {
    EMPTY,
    REVIEW_REQUIRED,
    READY,
}

data class WorkbenchStep(
    val stepId: String,
    val title: String,
    val granularity: StepGranularity,
    val kind: StepKind,
    val description: String = "",
    val nodeRefs: List<String> = emptyList(),
    val downstreamTargets: List<String> = emptyList(),
)

data class StepProjectionResult(
    val steps: List<WorkbenchStep> = emptyList(),
)

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

fun CandidateDraftChange.hasDirectEvidence(): Boolean {
    return evidence.any { finding ->
        finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE ||
            finding.evidenceLevel == ResultEvidenceLevel.DIRECT_GRAPH
    }
}

fun CandidateDraftChange.isEligibleForDraftConfirmation(): Boolean {
    return status == CandidateDraftChangeStatus.PENDING_CONFIRMATION && hasDirectEvidence()
}

data class InvestigationEvidenceDelta(
    val addedNodeIds: List<String> = emptyList(),
    val addedFilePaths: List<String> = emptyList(),
    val previousStrongestEvidenceLevel: ResultEvidenceLevel? = null,
    val currentStrongestEvidenceLevel: ResultEvidenceLevel? = null,
    val hitRecommendedQuestion: Boolean = false,
)

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

data class RiskResolution(
    val threadId: String,
    val status: RiskResolutionStatus,
    val note: String = "",
)

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

data class AuditConversationMessage(
    val messageId: String,
    val role: AuditMessageRole,
    val content: String,
    val focusTargetId: String? = null,
    val turnOutcomeId: String? = null,
)

data class AuditConversationSession(
    val sessionId: String,
    val scopeKey: String,
    val messages: List<AuditConversationMessage> = emptyList(),
    val candidateChanges: List<CandidateDraftChange> = emptyList(),
    val investigationThreads: List<InvestigationThread> = emptyList(),
    val turnOutcomes: List<InvestigationTurnOutcome> = emptyList(),
    val focusTargetId: String? = null,
)

data class GenerationPlanDiscussionMessage(
    val messageId: String,
    val role: AuditMessageRole,
    val content: String,
    val focusItemId: String? = null,
)

data class GenerationPlanDiscussionSession(
    val sessionId: String,
    val messages: List<GenerationPlanDiscussionMessage> = emptyList(),
    val focusItemId: String? = null,
    val promptPreview: String? = null,
)

data class GenerationPlanDiscussionResult(
    val source: com.charmnight.linkgraph.llm.LlmResultSource,
    val question: String,
    val answer: String,
    val promptPreview: String,
    val focusItemId: String? = null,
    val session: GenerationPlanDiscussionSession,
    val warnings: List<String> = emptyList(),
)

data class AuditModelTurn(
    val answer: String,
    val candidateChanges: List<CandidateDraftChange> = emptyList(),
    val investigationThreads: List<InvestigationThread> = emptyList(),
    val sourceThreadId: String? = null,
    val observedNodeIds: List<String> = emptyList(),
    val observedFilePaths: List<String> = emptyList(),
    val blockedReason: String? = null,
)

data class AuditConversationTurnResult(
    val session: AuditConversationSession,
    val newCandidateChanges: List<CandidateDraftChange> = emptyList(),
    val newInvestigationThreads: List<InvestigationThread> = emptyList(),
    val latestTurnOutcome: InvestigationTurnOutcome? = null,
    val recentTurnOutcomes: List<InvestigationTurnOutcome> = emptyList(),
    val draftWrites: List<DraftWorkbenchEntry> = emptyList(),
)

data class ReplayableQaRequest(
    val requestId: String,
    val kind: QaRequestKind,
    val question: String,
    val mode: QaMode = QaMode.AUTO,
    val selectedNodeIds: List<String> = emptyList(),
    val sourceThreadId: String? = null,
    val baseSession: AuditConversationSession? = null,
)

data class QaRequestRecoveryState(
    val lastSubmittedRequest: ReplayableQaRequest? = null,
    val lastFailedRequest: ReplayableQaRequest? = null,
)

data class DraftValidationState(
    val status: DraftValidationStatus,
    val message: String,
    val detailMessage: String? = null,
    val unresolvedThreadIds: List<String> = emptyList(),
    val unresolvedThreads: List<InvestigationThread> = emptyList(),
)

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

data class DraftWorkbenchState(
    val draftChanges: List<DraftWorkbenchEntry> = emptyList(),
    val draftNotes: List<DraftWorkbenchEntry> = emptyList(),
)

data class DraftConfirmationResult(
    val draftState: DraftWorkbenchState,
    val draftChanges: List<DraftWorkbenchEntry> = emptyList(),
    val graphChanged: Boolean = false,
    val failureReason: String? = null,
)

data class DraftRemovalResult(
    val draftState: DraftWorkbenchState,
    val removedEntry: DraftWorkbenchEntry? = null,
    val graphChanged: Boolean = false,
)
