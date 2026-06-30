package com.charmnight.linkgraph.ui

/**
 * PageRenderer 前端传输 DTO 集合入口（P2-6 拆分）。
 *
 * 历史上所有 DTO 与 mapper 集中在本文件，1200+ 行难以导航。按领域拆分为：
 *
     * - [PageRendererLeafDtos.kt][PageRendererLeafDtos]：独立叶子 DTO（AsyncRequestState / EditScope / CandidatePatchIntent
     *   等类型：QaConversationMessage / InvestigationTurnOutcome / DraftPatchApplyResult 等）
     * - [PageRendererReviewDtos.kt][PageRendererReviewDtos]：差异审查相关 DTO（ReviewHunk / ReviewChangedFile /
     *   审查项：ReviewBaselineSymbol / ReviewRelatedTest / ReviewEvidenceSnippet）
 * - [PageRendererGraphDtos.kt][PageRendererGraphDtos]：图文档 / 投影 / 视图摘要（含 [ViewDocumentSummaryDto]
 *   密封接口、IndexedGraphSummary 等）
     * - [PageRendererInvestigationDtos.kt][PageRendererInvestigationDtos]：问答 / 调查 / 草稿 / 讲解 复合 DTO
     *   （复合项：InvestigationThread / CandidateDraftChange / QaConversationSession / DraftWorkbenchEntry / BeautificationResult /
 *   PatchResult / GeneratedCodeDraft / AssistantResultEntry 等）
 * - [PageRendererEnvelopeDtos.kt][PageRendererEnvelopeDtos]：信封 / 切片 / 工件摘要 DTO（BootstrapPayload /
 *   ArtifactSlicePayload / FeedbackSlicePayload / ArtifactContentsSlice / SnapshotEnvelopePayload 等）
 * - [PageRendererDtoMappers.kt][PageRendererDtoMappers]：从领域对象构造 DTO 的工厂函数（xxxToDto）
 *
 * 设计原则（所有 DTO 共同遵守）：
 * - 每个领域类型对应一个 DTO 数据类，字段名与前端 TS 类型一一对应
 * - Gson 反射序列化按声明顺序输出，与原 linkedMapOf 顺序一致（serializeNulls 已开）
 * - 字段类型用 String/List<DTO>/nullable 等 Kotlin 类型，避免无类型映射载荷
 * - 嵌套结构通过组合 DTO 表达，不混用 Map
 * - 多态根用密封接口（[TransportSliceStateDto] / [ViewDocumentSummaryDto]）替代 Any
 *
 * 接口稳定性：本包中的数据类字段对应前端协议，破坏性变更需要同步前端 TS 类型。
 */
