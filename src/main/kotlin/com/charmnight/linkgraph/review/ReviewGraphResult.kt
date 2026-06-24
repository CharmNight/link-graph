package com.charmnight.linkgraph.review

import com.charmnight.linkgraph.application.indexed.IndexedGraphSummary
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.model.GraphDocument

/**
 * 描述审查视图的整体统计摘要，反映变更符号、上下游和相关测试规模。
 */
data class ReviewGraphSummary(
    /** 保存变更符号总数。 */
    val changedSymbolCount: Int = 0,
    /** 保存上游依赖符号总数。 */
    val upstreamCount: Int = 0,
    /** 保存下游依赖符号总数。 */
    val downstreamCount: Int = 0,
    /** 保存相关测试符号总数。 */
    val relatedTestCount: Int = 0,
    /** 保存受影响包数量。 */
    val affectedPackageCount: Int = 0,
    /** 保存受影响模块数量。 */
    val affectedModuleCount: Int = 0,
    /** 保存证据引用条目总数。 */
    val evidenceRefCount: Int = 0,
    /** 标记图结果是否经过截断。 */
    val truncated: Boolean = false,
    /** 保存因展示限制而隐藏的节点数量。 */
    val hiddenNodeCount: Int = 0,
    /** 保存因展示限制而隐藏的边数量。 */
    val hiddenEdgeCount: Int = 0,
    /** 保存用户在 diff 列表中选中的条目标识。 */
    val selectedDiffItemIds: List<String> = emptyList(),
    /** 保存变更类节点的最大展示上限。 */
    val maxChangedNodes: Int = 120,
    /** 保存上游节点的最大展示上限。 */
    val maxUpstreamNodes: Int = 40,
    /** 保存下游节点的最大展示上限。 */
    val maxDownstreamNodes: Int = 40,
    /** 保存相关测试节点的最大展示上限。 */
    val maxRelatedTestNodes: Int = 40,
    /** 保存底层索引的统计摘要。 */
    val indexed: IndexedGraphSummary? = null,
)

/**
 * 描述一次代码审查视图中的文件级变更信息。
 */
data class ReviewGraphChangedFile(
    /** 保存重命名或删除前的旧路径。 */
    val oldPath: String? = null,
    /** 保存重命名或新增后的新路径。 */
    val newPath: String? = null,
    /** 保存文件级变更类型，例如 MODIFIED、ADDED。 */
    val changeKind: String = "MODIFIED",
    /** 保存该文件包含的代码块数量。 */
    val hunkCount: Int = 0,
    /** 保存 rename/copy 时 Git 给出的相似度百分比。 */
    val similarity: Int? = null,
)

/**
 * 描述一次代码审查视图中的代码块级变更信息。
 */
data class ReviewGraphChangedHunk(
    /** 保存当前文件路径。 */
    val filePath: String,
    /** 保存旧文件路径。 */
    val oldFilePath: String? = null,
    /** 保存新文件路径。 */
    val newFilePath: String? = null,
    /** 保存代码块变更类型，例如 HUNK_ADDED、HUNK_MODIFIED。 */
    val changeKind: String = "HUNK_MODIFIED",
    /** 保存原始 hunk 头文本。 */
    val header: String,
    /** 保存旧文件中代码块的起始行号。 */
    val oldStartLine: Int? = null,
    /** 保存旧文件中代码块的行数。 */
    val oldLineCount: Int? = null,
    /** 保存新文件中代码块的起始行号。 */
    val newStartLine: Int? = null,
    /** 保存新文件中代码块的行数。 */
    val newLineCount: Int? = null,
    /** 保存该代码块覆盖到的符号标识列表。 */
    val matchedSymbolIds: List<String> = emptyList(),
    /** 保存附加说明，例如未匹配符号时的原因。 */
    val reason: String? = null,
)

/**
 * 描述变更符号在 UI 上需要展示的详细信息。
 */
data class ReviewGraphChangedSymbolDetail(
    /** 保存符号稳定标识。 */
    val symbolId: String,
    /** 保存符号全限定名。 */
    val qualifiedName: String,
    /** 保存符号所在源码文件路径。 */
    val filePath: String? = null,
    /** 保存符号在源码中的起始行号。 */
    val startLine: Int? = null,
    /** 保存符号在源码中的结束行号。 */
    val endLine: Int? = null,
    /** 保存符号变更类型，例如 MODIFIED、ADDED。 */
    val changeKind: String = "MODIFIED",
    /** 标记该符号的爆炸半径不完整。 */
    val blastRadiusIncomplete: Boolean = false,
    /** 保存无法计算或解析时的原因说明。 */
    val unavailableReason: String? = null,
    /** 保存该符号被判定为变更的具体原因。 */
    val reason: String = "PATH_MATCHED_SYMBOL_FILE",
)

/**
 * 描述与变更相关的测试符号展示信息。
 */
data class ReviewGraphRelatedTestDetail(
    /** 保存测试符号稳定标识。 */
    val symbolId: String,
    /** 保存测试符号全限定名。 */
    val qualifiedName: String,
    /** 保存该测试与变更的关联原因。 */
    val reason: String,
    /** 保存测试所在源码文件路径。 */
    val filePath: String? = null,
    /** 保存测试符号在源码中的起始行号。 */
    val startLine: Int? = null,
)

/**
 * 描述一段可用于 UI 展示或 LLM 上下文的证据片段。
 */
data class ReviewGraphEvidenceSnippet(
    /** 保存片段标题。 */
    val title: String,
    /** 保存片段类型，例如变更符号、关系证据、源码片段。 */
    val kind: String,
    /** 保存片段对应的源码文件路径。 */
    val filePath: String? = null,
    /** 保存片段起始行号。 */
    val startLine: Int? = null,
    /** 保存片段结束行号。 */
    val endLine: Int? = null,
    /** 保存实际代码片段文本。 */
    val snippet: String? = null,
    /** 保存片段不可用时的原因。 */
    val unavailableReason: String? = null,
)

/**
 * 描述代码审查视图的完整结果，包括可见图、完整图、变更、证据等。
 */
data class ReviewGraphResult(
    /** 保存经过裁剪后供前端展示的图。 */
    val visibleGraph: GraphDocument = GraphDocument(),
    /** 保存未裁剪的完整图，便于上层进一步处理。 */
    val fullGraph: GraphDocument = GraphDocument(),
    /** 保存视口聚焦的锚点节点标识。 */
    val anchorNodeId: String? = null,
    /** 保存统计摘要。 */
    val summary: ReviewGraphSummary = ReviewGraphSummary(),
    /** 保存投影索引，用于图节点到符号的回查。 */
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
    /** 保存变更文件列表。 */
    val changedFiles: List<ReviewGraphChangedFile> = emptyList(),
    /** 保存变更代码块列表。 */
    val changedHunks: List<ReviewGraphChangedHunk> = emptyList(),
    /** 保存未匹配到符号的代码块列表。 */
    val unmatchedHunks: List<ReviewGraphChangedHunk> = emptyList(),
    /** 保存只在基线版本中存在的符号列表。 */
    val baselineOnlySymbols: List<ReviewGraphChangedSymbolDetail> = emptyList(),
    /** 保存相关测试列表。 */
    val relatedTests: List<ReviewGraphRelatedTestDetail> = emptyList(),
    /** 保存受影响包列表。 */
    val affectedPackages: List<String> = emptyList(),
    /** 保存受影响模块列表。 */
    val affectedModules: List<String> = emptyList(),
    /** 保存证据片段列表。 */
    val evidenceSnippets: List<ReviewGraphEvidenceSnippet> = emptyList(),
)
