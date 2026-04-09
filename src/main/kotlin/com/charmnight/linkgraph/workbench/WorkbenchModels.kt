package com.charmnight.linkgraph.workbench

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

enum class DraftEntryKind {
    CHANGE,
    NOTE,
}

enum class AuditMessageRole {
    USER,
    ASSISTANT,
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
)

data class AuditConversationMessage(
    val messageId: String,
    val role: AuditMessageRole,
    val content: String,
    val focusTargetId: String? = null,
)

data class AuditConversationSession(
    val sessionId: String,
    val scopeKey: String,
    val messages: List<AuditConversationMessage> = emptyList(),
    val candidateChanges: List<CandidateDraftChange> = emptyList(),
    val focusTargetId: String? = null,
)

data class AuditModelTurn(
    val answer: String,
    val candidateChanges: List<CandidateDraftChange> = emptyList(),
)

data class AuditConversationTurnResult(
    val session: AuditConversationSession,
    val newCandidateChanges: List<CandidateDraftChange> = emptyList(),
    val draftWrites: List<DraftWorkbenchEntry> = emptyList(),
)

data class DraftWorkbenchState(
    val draftChanges: List<DraftWorkbenchEntry> = emptyList(),
    val draftNotes: List<DraftWorkbenchEntry> = emptyList(),
)

data class DraftConfirmationResult(
    val draftState: DraftWorkbenchState,
    val draftChanges: List<DraftWorkbenchEntry> = emptyList(),
    val graphChanged: Boolean = false,
)
