package com.charmnight.linkgraph.ui

/**
 * PageRenderer Review Graph 相关 DTO（P2-6 拆分自 PageRendererDtos.kt）。
 *
 * 与差异审查相关的叶子 DTO（差异块 / 变更文件 / 基线符号 / 相关测试 / 证据片段）
 * 与复合 DTO（ReviewGraphView）集中存放，便于围绕 review 协议演化。
 *
 * 设计原则与 [PageRendererDtos] 一致。
 */

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
