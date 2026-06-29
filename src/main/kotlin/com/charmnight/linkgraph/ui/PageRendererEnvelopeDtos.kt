package com.charmnight.linkgraph.ui

/**
 * PageRenderer 信封 / 切片 / 工件摘要 DTO（P2-6 拆分自 PageRendererDtos.kt）。
 *
 * - [BootstrapPayloadDto]：完整快照根 DTO，前端 bootstrap JSON 顶层结构
 * - [ArtifactSlicePayloadDto] / [FeedbackSlicePayloadDto]：增量切片 DTO
 * - [SnapshotEnvelopePayloadDto] / [ArtifactSliceEnvelopePayloadDto] / [FeedbackSliceEnvelopePayloadDto]：
 *   信封外层包装（前端 event.detail 结构）
 * - [ArtifactContentsSliceDto]：按需 artifact 拉取响应
 * - 工件摘要 + 操作反馈 / Mermaid 问题 / Diff 条目 / 同步预览等运行时 DTO
 *
 * 信封 state 字段统一走 [TransportSliceStateDto] sealed 类型，与 [GraphEditorTransportEnvelope] 对齐。
 */

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

/**
 * Bootstrap 序列化的根 DTO（前端 bootstrap JSON 顶层结构）。
 *
 * 实现 [TransportSliceStateDto]：作为 [GraphEditorTransportEnvelope.Snapshot]
 * 携带的 state 类型，由 sealed 体系统一约束。
 */
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
) : TransportSliceStateDto

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
) : TransportSliceStateDto

/**
 * 反馈切片 DTO（仅承载反馈消息和 lastMessageType，不携带图谱内容）。
 *
 * 当本快照与上一快照只有反馈文案变化时使用，避免下发完整 bootstrap。
 */
internal data class FeedbackSlicePayloadDto(
    val snapshotRevision: Long,
    val operationFeedback: OperationFeedbackDto?,
    val lastMessageType: String?,
) : TransportSliceStateDto

/**
 * 信封 JSON 外层包装（前端 event.detail 结构）。
 *
 * type=null 表示完整快照（[GraphEditorTransportEnvelope.Snapshot]）；
 * 其他两个分别携带 [GraphEditorTransportEnvelope.ArtifactSlice]/
 * [GraphEditorTransportEnvelope.FeedbackSlice] 的 transportType。
 * state 字段持有 [TransportSliceStateDto] 的具体子类型。
 */
internal data class SnapshotEnvelopePayloadDto(
    val sessionId: String,
    val revision: Long,
    val state: TransportSliceStateDto,
)

internal data class ArtifactSliceEnvelopePayloadDto(
    val type: String,
    val sessionId: String,
    val revision: Long,
    val state: TransportSliceStateDto,
)

internal data class FeedbackSliceEnvelopePayloadDto(
    val type: String,
    val sessionId: String,
    val revision: Long,
    val state: TransportSliceStateDto,
)

/**
 * 按需 artifact 拉取响应 DTO（[GraphBrowserTransportDispatcher] 用）。
 *
 * 与 [ArtifactSlicePayloadDto] 不同：这是前端主动拉取 artifact 内容时的最小响应，
 * 只携带 artifactContents + snapshotRevision + 自定义 lastMessageType（"artifactSlice"），
 * 不携带其他 artifact 变更字段。
 *
 * 实现 [TransportSliceStateDto]：作为 [GraphEditorTransportEnvelope.ArtifactSlice]
 * 在按需拉取场景下携带的 state 类型。
 */
internal data class ArtifactContentsSliceDto(
    val artifactContents: Map<String, String>,
    val snapshotRevision: Long,
    val lastMessageType: String,
) : TransportSliceStateDto
