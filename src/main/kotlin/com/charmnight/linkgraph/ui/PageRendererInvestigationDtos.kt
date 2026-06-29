package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftValidationStatus
import com.charmnight.linkgraph.workbench.InvestigationThreadStatus
import com.charmnight.linkgraph.workbench.QaMessageRole
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.StepGranularity
import com.charmnight.linkgraph.workbench.StepKind

/**
 * PageRenderer 问答 / 调查 / 草稿 / 讲解 复合 DTO（P2-6 拆分自 PageRendererDtos.kt）。
 *
 * 这些 DTO 引用 [PageRendererLeafDtos] 中的基础类型（EditScope / CandidatePatchIntent /
 * InvestigationTurnOutcome 等），组合出问答会话、候选变更、草稿工作台等高层结构。
 *
 * m3 enum 化：status / kind / source / granularity / role / mode 等字段从 String 改为对应领域 enum。
 */

internal data class InvestigationThreadDto(
    val threadId: String,
    val status: InvestigationThreadStatus,
    val title: String,
    val targetStepIds: List<String>,
    val targetNodeIds: List<String>,
    val summary: String,
    val evidenceGap: String,
    val recommendedQuestion: String,
    val claimType: String?,
    val evidence: List<ResultEvidenceFindingDto>,
    val latestTurnOutcomeId: String?,
    val resolution: RiskResolutionDto?,
)

internal data class CandidateDraftChangeDto(
    val changeId: String,
    val status: CandidateDraftChangeStatus,
    val title: String,
    val targetStepIds: List<String>,
    val targetNodeIds: List<String>,
    val beforeState: String?,
    val afterState: String?,
    val reason: String,
    val impactSummary: String,
    val claimType: String?,
    val evidence: List<ResultEvidenceFindingDto>,
    val editScopes: List<EditScopeDto>,
    val patchIntent: CandidatePatchIntentDto?,
    val graphPatch: GraphPatchDto?,
)

internal data class QaConversationSessionDto(
    val sessionId: String,
    val scopeKey: String?,
    val messages: List<QaConversationMessageDto>,
    val candidateChanges: List<CandidateDraftChangeDto>,
    val investigationThreads: List<InvestigationThreadDto>,
    val turnOutcomes: List<InvestigationTurnOutcomeDto>,
    val focusTargetId: String?,
)

internal data class DraftWorkbenchEntryDto(
    val entryId: String,
    val kind: DraftEntryKind,
    val title: String,
    val sourceChangeId: String?,
    val targetStepIds: List<String>,
    val targetNodeIds: List<String>,
    val beforeState: String?,
    val afterState: String?,
    val reason: String?,
    val impactSummary: String?,
    val claimType: String?,
    val evidence: List<ResultEvidenceFindingDto>,
    val editScopes: List<EditScopeDto>,
    val patchIntent: CandidatePatchIntentDto?,
    val graphPatch: GraphPatchDto?,
)

internal data class DraftWorkbenchStateDto(
    val draftChanges: List<DraftWorkbenchEntryDto>,
    val draftNotes: List<DraftWorkbenchEntryDto>,
)

internal data class DraftValidationStateDto(
    val status: DraftValidationStatus,
    val message: String,
    val detailMessage: String?,
    val unresolvedThreadIds: List<String>,
    val unresolvedThreads: List<InvestigationThreadDto>,
)

internal data class BeautificationStepDto(
    val stepId: String,
    val title: String,
    val granularity: StepGranularity,
    val kind: StepKind,
    val description: String,
    val primaryNodeId: String?,
    val codeSnippet: String?,
    val evidence: List<ResultEvidenceFindingDto>,
    val followUpQuestions: List<String>,
    val downstreamTargets: List<String>,
)

internal data class BeautificationResultDto(
    val source: LlmResultSource,
    val granularity: StepGranularity,
    val steps: List<BeautificationStepDto>,
    val promptPreviewArtifactId: String?,
    val warnings: List<String>,
)

internal data class PatchResultDto(
    val source: LlmResultSource,
    val question: String,
    val requestedMode: QaMode,
    val effectiveMode: QaMode,
    val answer: String,
    val promptPreviewArtifactId: String?,
    val warnings: List<String>,
    val findings: List<ResultEvidenceFindingDto>,
    val candidateChanges: List<CandidateDraftChangeDto>,
    val newCandidateChanges: List<CandidateDraftChangeDto>,
    val investigationThreads: List<InvestigationThreadDto>,
    val latestTurnOutcome: InvestigationTurnOutcomeDto?,
    val recentTurnOutcomes: List<InvestigationTurnOutcomeDto>,
    val sourceContext: List<SourceSnippetContextDto>,
    val evidenceTrace: List<EvidenceTraceEntryDto>,
    val qaSession: QaConversationSessionDto?,
    val patch: GraphPatchDto?,
)

internal data class GeneratedCodeDraftDto(
    val id: String,
    val sourceNodeId: String,
    val title: String,
    val targetPath: String,
    val contentArtifactId: String?,
    val content: String?,
    val editOperations: List<CodeEditOperationDto>,
    val editScopes: List<EditScopeDto>,
    val preparedEdits: List<PreparedCodeEditDto>,
    val warnings: List<String>,
)

internal data class GenerationPlanDiscussionMessageDto(
    val messageId: String,
    val role: QaMessageRole,
    val content: String,
    val focusItemId: String?,
)

internal data class GenerationPlanDiscussionSessionDto(
    val sessionId: String,
    val messages: List<GenerationPlanDiscussionMessageDto>,
    val focusItemId: String?,
    val promptPreviewArtifactId: String?,
)

/**
 * 助手单轮结果 entry（按 kind 区分实际承载的字段，未承载的字段为 null）。
 *
 * kind 字段保留 String：值由 [AssistantTurnKind] 派生，但 DTO 用作 discriminator，
 * 前端 union 按 kind 字符串分发，Kotlin enum 化反而会让序列化多一层间接。
 * 如未来 union 类型稳定，可考虑引入 @JsonClassSerializedName 对应 sealed。
 */
internal data class AssistantResultEntryDto(
    val kind: String,
    val failure: AssistantFailureResultDto?,
    val explanation: BeautificationResultDto?,
    val qa: PatchResultDto?,
    val generationPlan: GenerationPlanDto?,
    val generationDiscussionSession: GenerationPlanDiscussionSessionDto?,
    val codeDraftWarnings: List<String>?,
    val codeDrafts: List<GeneratedCodeDraftDto>?,
    val check: PatchResultDto?,
)
