package com.charmnight.linkgraph.review

import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.model.GraphDocument

data class ReviewGraphSummary(
    val changedSymbolCount: Int = 0,
    val upstreamCount: Int = 0,
    val downstreamCount: Int = 0,
    val relatedTestCount: Int = 0,
    val affectedPackageCount: Int = 0,
    val affectedModuleCount: Int = 0,
    val evidenceRefCount: Int = 0,
    val truncated: Boolean = false,
    val hiddenNodeCount: Int = 0,
    val hiddenEdgeCount: Int = 0,
)

data class ReviewGraphChangedFile(
    val oldPath: String? = null,
    val newPath: String? = null,
    val changeKind: String = "MODIFIED",
    val hunkCount: Int = 0,
    val similarity: Int? = null,
)

data class ReviewGraphChangedHunk(
    val filePath: String,
    val oldFilePath: String? = null,
    val newFilePath: String? = null,
    val changeKind: String = "HUNK_MODIFIED",
    val header: String,
    val oldStartLine: Int? = null,
    val oldLineCount: Int? = null,
    val newStartLine: Int? = null,
    val newLineCount: Int? = null,
    val matchedSymbolIds: List<String> = emptyList(),
)

data class ReviewGraphChangedSymbolDetail(
    val symbolId: String,
    val qualifiedName: String,
    val filePath: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val changeKind: String = "MODIFIED",
    val blastRadiusIncomplete: Boolean = false,
    val unavailableReason: String? = null,
)

data class ReviewGraphRelatedTestDetail(
    val symbolId: String,
    val qualifiedName: String,
    val reason: String,
    val filePath: String? = null,
    val startLine: Int? = null,
)

data class ReviewGraphEvidenceSnippet(
    val title: String,
    val kind: String,
    val filePath: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val snippet: String? = null,
    val unavailableReason: String? = null,
)

data class ReviewGraphViewDocument(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val anchorNodeId: String? = null,
    val summary: ReviewGraphSummary = ReviewGraphSummary(),
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
    val changedFiles: List<ReviewGraphChangedFile> = emptyList(),
    val changedHunks: List<ReviewGraphChangedHunk> = emptyList(),
    val unmatchedHunks: List<ReviewGraphChangedHunk> = emptyList(),
    val baselineOnlySymbols: List<ReviewGraphChangedSymbolDetail> = emptyList(),
    val relatedTests: List<ReviewGraphRelatedTestDetail> = emptyList(),
    val affectedPackages: List<String> = emptyList(),
    val affectedModules: List<String> = emptyList(),
    val evidenceSnippets: List<ReviewGraphEvidenceSnippet> = emptyList(),
)
