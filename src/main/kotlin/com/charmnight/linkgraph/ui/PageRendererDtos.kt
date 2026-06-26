package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.CodeEditOperation
import com.charmnight.linkgraph.codegen.PreparedCodeEdit
import com.charmnight.linkgraph.llm.EvidenceTraceEntry
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.presentation.GraphViewPresentation
import com.charmnight.linkgraph.ui.DraftPatchApplyResult
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
 * PageRenderer 全部前端传输 DTO 集合（P2-6 严格完整修复）。
 *
 * 设计原则：
 * - 每个领域类型对应一个 DTO data class，字段名与前端 TS 类型一一对应
 * - Gson 反射序列化按声明顺序输出，与原 linkedMapOf 顺序一致（serializeNulls 已开）
 * - 字段类型用 String/List<DTO>/nullable 等 Kotlin 类型，避免 Map<String, Any?>
 * - 嵌套结构通过组合 DTO 表达，不混用 Map
 *
 * 接口稳定性：本文件中的 data class 字段对应前端协议，破坏性变更需要同步前端 TS 类型。
 */

// ---------- 独立基础 DTO ----------

internal data class AsyncRequestStateDto(
    val phase: String,
    val requestId: Long?,
    val scene: String?,
    val executionMode: String?,
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
    val requestedMode: String?,
    val effectiveMode: String?,
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
    val kind: String,
    val payload: String,
    val warnings: List<String>,
)

internal data class PreparedCodeEditDto(
    val operationId: String,
    val filePath: String,
    val scopeId: String?,
    val kind: String,
    val targetSymbolSignature: String?,
    val startOffset: Int,
    val endOffset: Int,
    val beforeText: String?,
    val afterText: String?,
    val warnings: List<String>,
)

internal data class CandidatePatchIntentDto(
    val mode: String,
    val targetNodeId: String?,
    val attachEdgeId: String?,
    val falseBranchTargetNodeId: String?,
)

internal data class RiskResolutionDto(
    val threadId: String,
    val status: String,
    val note: String?,
)

internal data class QaConversationMessageDto(
    val messageId: String,
    val role: String,
    val content: String,
    val focusTargetId: String?,
    val turnOutcomeId: String?,
)

internal data class InvestigationTurnOutcomeDto(
    val outcomeId: String,
    val threadId: String,
    val status: String,
    val summary: String?,
    val detail: String?,
    val candidateChangeId: String?,
    val blockedReason: String?,
    val evidenceDelta: InvestigationTurnEvidenceDeltaDto,
    val observedNodeIds: List<String>,
    val observedFilePaths: List<String>,
    val strongestEvidenceLevel: String?,
)

internal data class InvestigationTurnEvidenceDeltaDto(
    val addedNodeIds: List<String>,
    val addedFilePaths: List<String>,
    val previousStrongestEvidenceLevel: String?,
    val currentStrongestEvidenceLevel: String?,
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

internal data class ReviewHunkDto(
    val filePath: String,
    val oldFilePath: String?,
    val newFilePath: String?,
    val changeKind: String?,
    val header: String?,
    val oldStartLine: Int?,
    val oldLineCount: Int?,
    val newStartLine: Int?,
    val newLineCount: Int?,
    val matchedSymbolIds: List<String>,
    val reason: String?,
)

internal data class ReviewChangedFileDto(
    val oldPath: String?,
    val newPath: String?,
    val changeKind: String?,
    val hunkCount: Int,
    val similarity: Int?,
)

internal data class ReviewBaselineSymbolDto(
    val symbolId: String,
    val qualifiedName: String?,
    val filePath: String?,
    val startLine: Int?,
    val endLine: Int?,
    val changeKind: String?,
    val blastRadiusIncomplete: Boolean,
    val unavailableReason: String?,
    val reason: String?,
)

internal data class ReviewRelatedTestDto(
    val symbolId: String,
    val qualifiedName: String?,
    val reason: String?,
    val filePath: String?,
    val startLine: Int?,
)

internal data class ReviewEvidenceSnippetDto(
    val title: String?,
    val kind: String?,
    val filePath: String?,
    val startLine: Int?,
    val endLine: Int?,
    val snippet: String?,
    val unavailableReason: String?,
)

// ---------- 依赖基础 DTO 的中间 DTO ----------

internal data class InvestigationThreadDto(
    val threadId: String,
    val status: String,
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
    val status: String,
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
    val kind: String,
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
    val status: String,
    val message: String,
    val detailMessage: String?,
    val unresolvedThreadIds: List<String>,
    val unresolvedThreads: List<InvestigationThreadDto>,
)

internal data class BeautificationStepDto(
    val stepId: String,
    val title: String,
    val granularity: String,
    val kind: String,
    val description: String,
    val primaryNodeId: String?,
    val codeSnippet: String?,
    val evidence: List<ResultEvidenceFindingDto>,
    val followUpQuestions: List<String>,
    val downstreamTargets: List<String>,
)

internal data class BeautificationResultDto(
    val source: String,
    val granularity: String,
    val steps: List<BeautificationStepDto>,
    val promptPreviewArtifactId: String?,
    val warnings: List<String>,
)

internal data class PatchResultDto(
    val source: String,
    val question: String,
    val requestedMode: String,
    val effectiveMode: String,
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

internal data class GraphDocumentDto(
    val nodes: List<GraphNodeDto>,
    val edges: List<GraphEdgeDto>,
    val patch: GraphPatchDto?,
    val nodeCount: Int,
    val edgeCount: Int,
    val truncated: Boolean,
)

internal data class GraphProjectionNodeMappingDto(
    val projectedNodeId: String,
    val mappingKind: String,
    val canonicalNodeIds: List<String>,
    val editableCommandKinds: List<String>,
)

internal data class GraphProjectionEdgeMappingDto(
    val projectedEdgeId: String,
    val mappingKind: String,
    val canonicalEdgeIds: List<String>,
    val canonicalPathNodeIds: List<String>,
    val editableCommandKinds: List<String>,
)

internal data class GraphProjectionIndexDto(
    val nodeMappings: Map<String, GraphProjectionNodeMappingDto>,
    val edgeMappings: Map<String, GraphProjectionEdgeMappingDto>,
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
    val role: String,
    val content: String,
    val focusItemId: String?,
)

internal data class GenerationPlanDiscussionSessionDto(
    val sessionId: String,
    val messages: List<GenerationPlanDiscussionMessageDto>,
    val focusItemId: String?,
    val promptPreviewArtifactId: String?,
)

/** 助手单轮结果 entry（按 kind 区分实际承载的字段，未承载的字段为 null）。 */
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

/** 运行时产物摘要（artifactId / artifactType / title / description）。 */
internal data class RuntimeArtifactSummaryDto(
    val artifactId: String,
    val artifactType: String,
    val title: String?,
    val description: String?,
)

/** Mermaid 解析问题（类别 + 行号 + 节点/边引用）。 */
internal data class MermaidIssueDto(
    val category: String,
    val code: String?,
    val message: String?,
    val line: Int?,
    val nodeId: String?,
    val edgeId: String?,
)

/** Diff 条目（节点 ID + 标题 + 状态 + 描述）。 */
internal data class DiffItemDto(
    val id: String,
    val title: String,
    val status: String,
    val description: String,
)

/** 同步预览项（风险等级 + 标题 + 描述）。 */
internal data class SyncPreviewItemDto(
    val id: String,
    val title: String?,
    val description: String?,
    val risk: String,
)

/** 代码草稿写入报告（写入文件 + 跳过文件 + 警告）。 */
internal data class GeneratedCodeDraftWriteReportDto(
    val writtenFiles: List<String>,
    val skippedFiles: List<String>,
    val warnings: List<String>,
)

/** 操作反馈（级别 + 消息）。 */
internal data class OperationFeedbackDto(
    val level: String,
    val message: String,
)

/** Bootstrap 序列化的根 DTO（前端 bootstrap JSON 顶层结构）。 */
internal data class BootstrapPayloadDto(
    val analysisDisplayMode: String,
    val currentSceneId: String,
    val workspaceGraph: GraphDocumentDto,
    val workspaceBaseGraph: GraphDocumentDto,
    val semanticFactGraph: GraphDocumentDto,
    val designBaselineGraph: GraphDocumentDto?,
    val sceneStates: Map<String, GraphSceneStateDto>,
    val factGraphView: ViewDocumentDto,
    val flowchartView: ViewDocumentDto,
    val resourceRelationView: ViewDocumentDto,
    val architectureGraphView: ViewDocumentDto,
    val classDiagramView: ViewDocumentDto,
    val reviewGraphView: ReviewGraphViewDto,
    val indexedGraphRequestStates: Map<String, AsyncRequestStateDto>,
    val draftPatchPreview: GraphPatchDto?,
    val draftWorkbenchState: DraftWorkbenchStateDto,
    val canUndoDraftPatchApply: Boolean,
    val lastAppliedDraftPatchSummary: String?,
    val lastDraftPatchApplyResult: DraftPatchApplyResultDto?,
    val qaResult: PatchResultDto?,
    val qaRequestState: AsyncRequestStateDto,
    val qaRequestRecoveryState: QaRequestRecoveryStateDto,
    val runtimeArtifactSummaries: Map<String, List<RuntimeArtifactSummaryDto>>,
    val diffReviewResult: PatchResultDto?,
    val diffReviewRequestState: AsyncRequestStateDto,
    val graphBeautificationResult: BeautificationResultDto?,
    val graphBeautificationRequestState: AsyncRequestStateDto,
    val mermaidIssues: List<MermaidIssueDto>,
    val diffItems: List<DiffItemDto>,
    val syncPreviewItems: List<SyncPreviewItemDto>,
    val draftVersion: Long,
    val generationPlan: GenerationPlanDto?,
    val generationPlanDraftVersion: Long?,
    val generationPlanRequestState: AsyncRequestStateDto,
    val draftValidationState: DraftValidationStateDto?,
    val generationPlanDiscussionSession: GenerationPlanDiscussionSessionDto?,
    val generationPlanDiscussionRequestState: AsyncRequestStateDto,
    val generatedCodeDrafts: List<GeneratedCodeDraftDto>,
    val generatedCodeDraftVersion: Long?,
    val generatedCodeDraftWarnings: List<String>,
    val generatedCodeDraftSource: String?,
    val generatedCodeDraftPromptPreviewArtifactId: String?,
    val codeDraftRequestState: AsyncRequestStateDto,
    val codeEligibilityDecision: StageEligibilityDecisionDto?,
    val generatedCodeDraftWriteReport: GeneratedCodeDraftWriteReportDto?,
    val semanticRevision: Long?,
    val workspaceRevision: Long?,
    val snapshotRevision: Long,
    val sourceNavigationState: SourceNavigationStateDto,
    val assistantSessionState: AssistantSessionStateDto,
    val assistantResultStore: Map<String, AssistantResultEntryDto>,
    val lastMessageType: String?,
    val lastGraphSource: String?,
    val operationFeedback: OperationFeedbackDto?,
)

/**
 * Artifact 增量切片 DTO（前端按 artifact 变化接收的 partial 更新）。
 *
 * 所有字段可选：只承载发生变化的字段（与上一版 artifact refs 对比决定）。
 * Gson 序列化时 serializeNulls 已开，未设置的字段会以 null 出现，前端按需消费。
 */
internal data class ArtifactSlicePayloadDto(
    val snapshotRevision: Long,
    val qaResult: PatchResultDto? = null,
    val qaRequestState: AsyncRequestStateDto? = null,
    val diffReviewResult: PatchResultDto? = null,
    val diffReviewRequestState: AsyncRequestStateDto? = null,
    val graphBeautificationResult: BeautificationResultDto? = null,
    val graphBeautificationRequestState: AsyncRequestStateDto? = null,
    val generationPlan: GenerationPlanDto? = null,
    val generationPlanRequestState: AsyncRequestStateDto? = null,
    val generationPlanDiscussionSession: GenerationPlanDiscussionSessionDto? = null,
    val generationPlanDiscussionRequestState: AsyncRequestStateDto? = null,
    val generatedCodeDrafts: List<GeneratedCodeDraftDto>? = null,
    val generatedCodeDraftPromptPreviewArtifactId: String? = null,
    val codeDraftRequestState: AsyncRequestStateDto? = null,
    val assistantResultStore: Map<String, AssistantResultEntryDto>? = null,
    val artifactContents: Map<String, String> = emptyMap(),
)

/**
 * 反馈切片 DTO（仅承载反馈消息和 lastMessageType，不携带图谱内容）。
 *
 * 当本快照与上一快照只有反馈文案变化时使用，避免下发完整 bootstrap。
 */
internal data class FeedbackSlicePayloadDto(
    val snapshotRevision: Long,
    val operationFeedback: OperationFeedbackDto?,
    val lastMessageType: String?,
)

/**
 * 信封 JSON 外层包装（前端 event.detail 结构）。
 *
 * type=null 表示完整快照（[Snapshot]）；其他两个分别携带 [ArtifactSlice]/[FeedbackSlice] 的 transportType。
 * state 字段持有具体 DTO（[BootstrapPayloadDto]/[ArtifactSlicePayloadDto]/[FeedbackSlicePayloadDto]）。
 */
internal data class SnapshotEnvelopePayloadDto(
    val sessionId: String,
    val revision: Long,
    val state: Any,
)

internal data class ArtifactSliceEnvelopePayloadDto(
    val type: String,
    val sessionId: String,
    val revision: Long,
    val state: Any,
)

internal data class FeedbackSliceEnvelopePayloadDto(
    val type: String,
    val sessionId: String,
    val revision: Long,
    val state: Any,
)

/**
 * 按需 artifact 拉取响应 DTO（[GraphBrowserTransportDispatcher] 用）。
 *
 * 与 [ArtifactSlicePayloadDto] 不同：这是前端主动拉取 artifact 内容时的最小响应，
 * 只携带 artifactContents + snapshotRevision + 自定义 lastMessageType（"artifactSlice"），
 * 不携带其他 artifact 变更字段。
 */
internal data class ArtifactContentsSliceDto(
    val artifactContents: Map<String, String>,
    val snapshotRevision: Long,
    val lastMessageType: String,
)

// ---------- 视图相关 DTO ----------

internal data class IndexedGraphLayerCountsDto(
    val projectSource: Int,
    val externalLibrary: Int,
    val jdk: Int,
    val resource: Int,
    val aggregate: Int,
)

internal data class IndexedGraphVisibilityReasonDto(
    val code: String,
    val label: String?,
    val nodeCount: Int,
    val edgeCount: Int,
)

internal data class IndexedGraphFreshnessDto(
    val state: String,
    val dirtyReason: String?,
    val pendingFileCount: Int,
    val pendingFileSamples: List<String>,
    val lastIndexedAtEpochMillis: Long?,
    val staleSinceEpochMillis: Long?,
)

internal data class IndexedGraphSummaryDto(
    val view: String?,
    val anchorKind: String?,
    val anchorNodeId: String?,
    val anchorTitle: String?,
    val anchorQualifiedName: String?,
    val scopeKind: String?,
    val scopeLabel: String?,
    val relationKinds: Set<String>,
    val depth: Int?,
    val projectNodeCount: Int,
    val projectClassCount: Int,
    val externalNodeCount: Int,
    val jdkNodeCount: Int,
    val scopedNodeCount: Int,
    val visibleNodeCount: Int,
    val hiddenNodeCount: Int,
    val hiddenEdgeCount: Int,
    val candidateNodeCount: Int,
    val candidateEdgeCount: Int,
    val truncated: Boolean,
    val completeness: String?,
    val cacheState: String?,
    val includeExternalLibraries: Boolean,
    val includeJdk: Boolean,
    val projectSourceNodeCount: Int,
    val externalLibraryNodeCount: Int,
    val resourceNodeCount: Int,
    val aggregateNodeCount: Int,
    val projectLayerCounts: IndexedGraphLayerCountsDto?,
    val visibleLayerCounts: IndexedGraphLayerCountsDto?,
    val scopedLayerCounts: IndexedGraphLayerCountsDto?,
    val candidateLayerCounts: IndexedGraphLayerCountsDto?,
    val hiddenLayerCounts: IndexedGraphLayerCountsDto?,
    val collapsedLayerCounts: IndexedGraphLayerCountsDto?,
    val freshness: IndexedGraphFreshnessDto?,
    val visibilityReasons: List<IndexedGraphVisibilityReasonDto>,
)

/** ClassDiagramView / ReviewGraphView 等场景下 indexed 字段对应 IndexedGraphSummary 整体，直接用 IndexedGraphSummaryDto。 */

internal data class ProjectStructureRelationGroupDto(
    val id: String,
    val fromNodeId: String?,
    val toNodeId: String?,
    val displayRelationKind: String?,
    val displayRelation: String?,
    val relationKinds: List<String>,
    val count: Int,
    val confidence: String?,
    val sourceRelationIds: List<String>,
    val sampleEvidenceRefs: List<String>,
    val defaultVisible: Boolean,
    val hiddenReason: String?,
)

internal data class ViewDocumentDto(
    val visibleGraph: GraphDocumentDto,
    val fullGraph: GraphDocumentDto,
    val anchorNodeId: String?,
    val projectionIndex: GraphProjectionIndexDto,
    val summary: Any, // 各视图 summary 类型不同，用 Any 让 Gson 反射序列化
    val presentation: GraphViewPresentation? = null,
    /** ClassDiagram 视图专有：类使用搜索结果；其他视图为 null 不渲染。 */
    val usage: ClassUsageSearchResultDto? = null,
)

internal data class FactGraphViewSummaryDto(
    val anchorTitle: String?,
    val visibleNodeCount: Int,
    val fullNodeCount: Int,
    val hiddenNodeCount: Int,
    val hiddenEdgeCount: Int,
    val truncated: Boolean,
)

internal data class FlowchartViewSummaryDto(
    val nodeCount: Int,
    val branchCount: Int,
    val exceptionPathCount: Int,
    val fullNodeCount: Int,
    val fullEdgeCount: Int,
    val incompleteNodeCount: Int,
    val incompleteEdgeCount: Int,
    val semanticallyIncomplete: Boolean,
    val syntheticEdgeCount: Int,
    val syntheticEntryEdgeCount: Int,
)

internal data class ResourceRelationViewSummaryDto(
    val visibleNodeCount: Int,
    val relationCount: Int,
    val resourceCount: Int,
    val fallbackReason: String?,
    val laneCounts: Map<String, Int>,
)

internal data class ArchitectureGraphViewSummaryDto(
    val moduleCount: Int,
    val packageCount: Int,
    val serviceCount: Int,
    val componentCount: Int,
    val resourceCount: Int,
    val layerCount: Int,
    val libraryCount: Int,
    val jdkCount: Int,
    val relationCount: Int,
    val classCount: Int,
    val relationshipNodeCount: Int,
    val inventoryOnlyNodeCount: Int,
    val unconnectedPackageCount: Int,
    val truncated: Boolean,
    val hiddenNodeCount: Int,
    val hiddenEdgeCount: Int,
    val unconnectedComponentCount: Int,
    val unconnectedServiceBoundaryCount: Int,
    val unconnectedResourceCount: Int,
    val externalDependencyGroupCount: Int,
    val jdkGroupCount: Int,
    val indexed: IndexedGraphSummaryDto?,
    val projectStructureRelationGroups: List<ProjectStructureRelationGroupDto>,
)

internal data class ClassDiagramViewSummaryDto(
    val classCount: Int,
    val fieldCount: Int,
    val interfaceCount: Int,
    val enumCount: Int,
    val annotationCount: Int,
    val recordCount: Int,
    val objectCount: Int,
    val relationCount: Int,
    val spiProviderCount: Int,
    val reflectionRelationCount: Int,
    val relationCompleteness: String?,
    val scopeTypeCount: Int,
    val projectTypeCount: Int,
    val projectClassCount: Int,
    val scopeBasis: String?,
    val anchorTypeNodeId: String?,
    val anchorTypeTitle: String?,
    val anchorTypeQualifiedName: String?,
    val neighborhoodLimit: Int?,
    val memberLimit: Int?,
    val neighborhoodCandidateTypeCount: Int,
    val neighborhoodTruncated: Boolean,
    val truncated: Boolean,
    val hiddenNodeCount: Int,
    val hiddenEdgeCount: Int,
    val indexed: IndexedGraphSummaryDto?,
)

internal data class ReviewGraphViewSummaryDto(
    val changedSymbolCount: Int,
    val upstreamCount: Int,
    val downstreamCount: Int,
    val relatedTestCount: Int,
    val affectedPackageCount: Int,
    val affectedModuleCount: Int,
    val evidenceRefCount: Int,
    val truncated: Boolean,
    val hiddenNodeCount: Int,
    val hiddenEdgeCount: Int,
    val selectedDiffItemIds: List<String>,
    val maxChangedNodes: Int,
    val maxUpstreamNodes: Int,
    val maxDownstreamNodes: Int,
    val maxRelatedTestNodes: Int,
    val indexed: IndexedGraphSummaryDto?,
)

internal data class ReviewGraphViewDto(
    val visibleGraph: GraphDocumentDto,
    val fullGraph: GraphDocumentDto,
    val anchorNodeId: String?,
    val projectionIndex: GraphProjectionIndexDto,
    val summary: ReviewGraphViewSummaryDto,
    val changedFiles: List<ReviewChangedFileDto>,
    val changedHunks: List<ReviewHunkDto>,
    val unmatchedHunks: List<ReviewHunkDto>,
    val baselineOnlySymbols: List<ReviewBaselineSymbolDto>,
    val relatedTests: List<ReviewRelatedTestDto>,
    val affectedPackages: List<String>,
    val affectedModules: List<String>,
    val evidenceSnippets: List<ReviewEvidenceSnippetDto>,
)

// ---------- 工厂函数（从领域对象构造 DTO） ----------

/** 把异步请求状态转换为 DTO。 */
internal fun asyncRequestStateToDto(
    state: AsyncRequestState,
    hasPromptPreview: Boolean = state.promptPreviewAvailable,
): AsyncRequestStateDto = AsyncRequestStateDto(
    phase = state.phase.name,
    requestId = state.requestId,
    scene = state.scene,
    executionMode = state.executionMode?.name,
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
    requestedMode = state.requestedMode?.name,
    effectiveMode = state.effectiveMode?.name,
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
    status = change.status.name,
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
    mode = intent.mode.name,
    targetNodeId = intent.targetNodeId,
    attachEdgeId = intent.attachEdgeId,
    falseBranchTargetNodeId = intent.falseBranchTargetNodeId,
)

/** 把 QA 对话单条消息转换为 DTO。 */
internal fun qaConversationMessageToDto(
    message: QaConversationMessage,
): QaConversationMessageDto = QaConversationMessageDto(
    messageId = message.messageId,
    role = message.role.name,
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
    status = thread.status.name,
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
    status = resolution.status.name,
    note = resolution.note,
)

/** 把单轮调查结果转换为 DTO。 */
internal fun investigationTurnOutcomeToDto(
    outcome: InvestigationTurnOutcome,
): InvestigationTurnOutcomeDto = InvestigationTurnOutcomeDto(
    outcomeId = outcome.outcomeId,
    threadId = outcome.threadId,
    status = outcome.status.name,
    summary = outcome.summary,
    detail = outcome.detail,
    candidateChangeId = outcome.candidateChangeId,
    blockedReason = outcome.blockedReason,
    evidenceDelta = InvestigationTurnEvidenceDeltaDto(
        addedNodeIds = outcome.evidenceDelta.addedNodeIds,
        addedFilePaths = outcome.evidenceDelta.addedFilePaths,
        previousStrongestEvidenceLevel = outcome.evidenceDelta.previousStrongestEvidenceLevel?.name,
        currentStrongestEvidenceLevel = outcome.evidenceDelta.currentStrongestEvidenceLevel?.name,
        hitRecommendedQuestion = outcome.evidenceDelta.hitRecommendedQuestion,
    ),
    observedNodeIds = outcome.observedNodeIds,
    observedFilePaths = outcome.observedFilePaths,
    strongestEvidenceLevel = outcome.strongestEvidenceLevel?.name,
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
    scope: com.charmnight.linkgraph.llm.EditScope,
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
    kind = operation.kind.name,
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
    kind = edit.kind.name,
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
    kind = entry.kind.name,
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
    status = state.status.name,
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
    source = result.source.name,
    granularity = result.granularity.name,
    steps = result.steps.map { step ->
        BeautificationStepDto(
            stepId = step.stepId,
            title = step.title,
            granularity = step.granularity.name,
            kind = step.kind.name,
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
    source = result.source.name,
    question = result.question,
    requestedMode = result.requestedMode.name,
    effectiveMode = result.effectiveMode.name,
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
            role = message.role.name,
            content = message.content,
            focusItemId = message.focusItemId,
        )
    },
    focusItemId = session.focusItemId,
    promptPreviewArtifactId = promptPreviewArtifactId,
)
