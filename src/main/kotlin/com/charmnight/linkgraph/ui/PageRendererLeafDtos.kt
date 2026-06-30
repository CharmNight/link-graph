package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode
import com.charmnight.linkgraph.application.model.AsyncRequestPhase
import com.charmnight.linkgraph.codegen.CodeEditOperationKind
import com.charmnight.linkgraph.agent.model.ResultEvidenceLevel
import com.charmnight.linkgraph.workbench.CandidatePatchIntentMode
import com.charmnight.linkgraph.workbench.InvestigationTurnOutcomeStatus
import com.charmnight.linkgraph.workbench.QaMessageRole
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.RiskResolutionStatus

/**
 * PageRenderer 独立叶子 DTO（P2-6 拆分自 PageRendererDtos.kt）。
 *
 * 这些 DTO 不依赖其他 DTO（只引用基本类型 / String / List / 枚举），可独立序列化。
 * 复合 DTO 在 [PageRendererInvestigationDtos] / [PageRendererEnvelopeDtos] 等文件中。
 *
 * 设计原则与 [PageRendererDtos] 一致：
 * - 字段名与前端 TS 类型一一对应
 * - Gson 反射序列化按声明顺序输出（serializeNulls 已开）
 * - 字段类型用具体 Kotlin 类型（含枚举），避免无类型映射载荷或铺平的字符串
 *
 * m3 enum 化：原先 String 字段（phase / mode / kind / status / role 等）改为对应领域 enum——
 * Gson 默认按 enum.name() 序列化，与原 String 字面量完全等价，wire format 不变；
 * Kotlin 端获得编译期类型校验，TS 端 union 类型与 enum name 自然对应。
 */

// ---------- 独立基础 DTO ----------

internal data class AsyncRequestStateDto(
    val phase: AsyncRequestPhase,
    val requestId: Long?,
    val scene: String?,
    val executionMode: AsyncRequestExecutionMode?,
    val statusMessage: String?,
    val errorMessage: String?,
    val detailMessage: String?,
    val startedAtEpochMillis: Long?,
    val finishedAtEpochMillis: Long?,
    val streaming: Boolean?,
    val fallbackUsed: Boolean?,
    val streamPhase: String?,
    val previewText: String?,
    val previewUpdatedAtEpochMillis: Long?,
    val finalizingStructuredResult: Boolean?,
    val providerLabel: String?,
    val model: String?,
    val endpointSummary: String?,
    val promptPreviewAvailable: Boolean,
    val requestedMode: QaMode?,
    val effectiveMode: QaMode?,
)

internal data class SourceSnippetContextDto(
    val nodeId: String?,
    val filePath: String?,
    val startOffset: Int?,
    val endOffset: Int?,
    val startLine: Int?,
    val endLine: Int?,
    val snippet: String?,
    val origin: String?,
    val decompiled: Boolean?,
    val virtualFileUrl: String?,
)

internal data class EvidenceTraceEntryDto(
    val nodeId: String?,
    val resolvedNodeId: String?,
    val filePath: String?,
    val reason: String?,
    val startLine: Int?,
    val endLine: Int?,
    val includedInPrompt: Boolean?,
    val mappingTrace: List<String>?,
)

internal data class EditScopeDto(
    val scopeId: String,
    val targetNodeId: String,
    val filePath: String?,
    val language: String?,
    val symbolKind: String?,
    val symbolSignature: String?,
    val startOffset: Int?,
    val endOffset: Int?,
    val startLine: Int?,
    val endLine: Int?,
    val allowedChangeKinds: List<String>,
    val supportingFindingIds: List<String>,
)

internal data class CodeEditOperationDto(
    val operationId: String,
    val filePath: String,
    val scopeId: String?,
    val kind: CodeEditOperationKind,
    val payload: String,
    val warnings: List<String>,
)

internal data class PreparedCodeEditDto(
    val operationId: String,
    val filePath: String,
    val scopeId: String?,
    val kind: CodeEditOperationKind,
    val targetSymbolSignature: String?,
    val startOffset: Int,
    val endOffset: Int,
    val beforeText: String?,
    val afterText: String?,
    val warnings: List<String>,
)

internal data class CandidatePatchIntentDto(
    val mode: CandidatePatchIntentMode,
    val targetNodeId: String?,
    val attachEdgeId: String?,
    val falseBranchTargetNodeId: String?,
)

internal data class RiskResolutionDto(
    val threadId: String,
    val status: RiskResolutionStatus,
    val note: String?,
)

internal data class QaConversationMessageDto(
    val messageId: String,
    val role: QaMessageRole,
    val content: String,
    val focusTargetId: String?,
    val turnOutcomeId: String?,
)

internal data class InvestigationTurnOutcomeDto(
    val outcomeId: String,
    val threadId: String,
    val status: InvestigationTurnOutcomeStatus,
    val summary: String?,
    val detail: String?,
    val candidateChangeId: String?,
    val blockedReason: String?,
    val evidenceDelta: InvestigationTurnEvidenceDeltaDto,
    val observedNodeIds: List<String>,
    val observedFilePaths: List<String>,
    val strongestEvidenceLevel: ResultEvidenceLevel?,
)

internal data class InvestigationTurnEvidenceDeltaDto(
    val addedNodeIds: List<String>,
    val addedFilePaths: List<String>,
    val previousStrongestEvidenceLevel: ResultEvidenceLevel?,
    val currentStrongestEvidenceLevel: ResultEvidenceLevel?,
    val hitRecommendedQuestion: Boolean?,
)

internal data class DraftPatchApplyResultDto(
    val summary: String?,
    val appliedOperationCount: Int,
    val appliedNodeIds: List<String>,
    val appliedEdgeIds: List<String>,
    val focusNodeId: String?,
    val appliedTargets: List<String>,
)
