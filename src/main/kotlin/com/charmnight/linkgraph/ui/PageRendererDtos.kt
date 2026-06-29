package com.charmnight.linkgraph.ui

/**
 * PageRenderer 前端传输 DTO 集合入口（P2-6 拆分）。
 *
 * 历史上所有 DTO 与 mapper 集中在本文件，1200+ 行难以导航。m6 拆分按领域分文件：
 *
 * - [PageRendererLeafDtos.kt][PageRendererLeafDtos]：独立叶子 DTO（AsyncRequestState / EditScope / CandidatePatchIntent
 *   / QaConversationMessage / InvestigationTurnOutcome / DraftPatchApplyResult 等）
 * - [PageRendererReviewDtos.kt][PageRendererReviewDtos]：差异审查相关 DTO（ReviewHunk / ReviewChangedFile /
 *   ReviewBaselineSymbol / ReviewRelatedTest / ReviewEvidenceSnippet）
 * - [PageRendererGraphDtos.kt][PageRendererGraphDtos]：图文档 / 投影 / 视图 summary（含 [ViewDocumentSummaryDto]
 *   sealed interface、IndexedGraphSummary 等）
 * - [PageRendererInvestigationDtos.kt][PageRendererInvestigationDtos]：问答 / 调查 / 草稿 / 讲解 复合 DTO
 *   （InvestigationThread / CandidateDraftChange / QaConversationSession / DraftWorkbenchEntry / BeautificationResult /
 *   PatchResult / GeneratedCodeDraft / AssistantResultEntry 等）
 * - [PageRendererEnvelopeDtos.kt][PageRendererEnvelopeDtos]：信封 / 切片 / 工件摘要 DTO（BootstrapPayload /
 *   ArtifactSlicePayload / FeedbackSlicePayload / ArtifactContentsSlice / SnapshotEnvelopePayload 等）
 * - [PageRendererDtoMappers.kt][PageRendererDtoMappers]：从领域对象构造 DTO 的工厂函数（xxxToDto）
 *
 * 设计原则（所有 DTO 共同遵守）：
 * - 每个领域类型对应一个 DTO data class，字段名与前端 TS 类型一一对应
 * - Gson 反射序列化按声明顺序输出，与原 linkedMapOf 顺序一致（serializeNulls 已开）
 * - 字段类型用 String/List<DTO>/nullable 等 Kotlin 类型，避免 Map<String, Any?>
 * - 嵌套结构通过组合 DTO 表达，不混用 Map
 * - 多态根用 sealed interface（[TransportSliceStateDto] / [ViewDocumentSummaryDto]）替代 Any
 *
 * 接口稳定性：本包中的 data class 字段对应前端协议，破坏性变更需要同步前端 TS 类型。
 */
