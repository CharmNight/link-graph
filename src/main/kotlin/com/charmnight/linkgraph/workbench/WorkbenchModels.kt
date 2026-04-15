package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel

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

enum class AuditInvestigationLeadStatus {
    OPEN,
    PROMOTED,
    DISMISSED,
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
    val claimType: String? = null,
    val evidence: List<ResultEvidenceFinding> = emptyList(),
    val editScopes: List<EditScope> = emptyList(),
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

data class AuditInvestigationLead(
    val leadId: String,
    val status: AuditInvestigationLeadStatus,
    val title: String = "",
    val targetStepIds: List<String> = emptyList(),
    val targetNodeIds: List<String> = emptyList(),
    val summary: String = "",
    val evidenceGap: String = "",
    val recommendedQuestion: String = "",
    val claimType: String? = null,
    val evidence: List<ResultEvidenceFinding> = emptyList(),
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
    val investigationLeads: List<AuditInvestigationLead> = emptyList(),
    val focusTargetId: String? = null,
)

data class AuditModelTurn(
    val answer: String,
    val candidateChanges: List<CandidateDraftChange> = emptyList(),
    val investigationLeads: List<AuditInvestigationLead> = emptyList(),
    val sourceLeadId: String? = null,
)

data class AuditConversationTurnResult(
    val session: AuditConversationSession,
    val newCandidateChanges: List<CandidateDraftChange> = emptyList(),
    val newInvestigationLeads: List<AuditInvestigationLead> = emptyList(),
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

data class DraftRemovalResult(
    val draftState: DraftWorkbenchState,
    val removedEntry: DraftWorkbenchEntry? = null,
    val graphChanged: Boolean = false,
)
