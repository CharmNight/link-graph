package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.presentation.GraphViewPresentation

/**
 * PageRenderer 图谱 + 视图相关 DTO（P2-6 拆分自 PageRendererDtos.kt）。
 *
 * 包含：
 * - 图文档 / 投影索引的传输 DTO（[GraphDocumentDto] 等）
 * - 视图（FactGraph / Flowchart / ResourceRelation / Architecture / ClassDiagram / ReviewGraph）相关 DTO
 * - 索引图摘要 / 新鲜度 / 可见性相关 DTO
 *
 * 视图摘要走密封接口 [ViewDocumentSummaryDto]，由各视图 DTO 实现。
 */

// ---------- 图文档基础 DTO ----------

internal data class GraphDocumentDto(
    val nodes: List<GraphNodeDto>,
    val edges: List<GraphEdgeDto>,
    val patch: GraphPatchDto?,
    val nodeCount: Int,
    val edgeCount: Int,
    val truncated: Boolean,
    val invocationExpansionRegistry: InvocationExpansionRegistryDto,
)

internal data class InvocationExpansionRegistryDto(
    val entries: List<InvocationExpansionRegistryEntryDto> = emptyList(),
)

internal data class InvocationExpansionRegistryEntryDto(
    val expansionId: String,
    val sourceInvocationNodeId: String?,
    val rootNodeId: String?,
    val targetSignature: String?,
    val createdAt: String?,
    val parentExpansionId: String?,
    val depth: Int,
    val ownedNodeIds: List<String>,
    val borrowedNodeIds: List<String>,
    val callEdgeIds: List<String>,
    val internalEdgeIds: List<String>,
    val childExpansionIds: List<String>,
    val warnings: List<String>,
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

// ---------- indexed graph 摘要 ----------

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

// ---------- 视图 summary + ViewDocument ----------

/**
 * 视图 summary 多态根类型。
 *
 * 各视图（FactGraph / Flowchart / ResourceRelation / Architecture / ClassDiagram / ReviewGraph）
 * 的 summary DTO 实现本接口，[ViewDocumentDto.summary] 用 sealed 类型替代原先的 `Any`——
 * Gson 序列化行为不变（按运行时实例反射），但 Kotlin 端编译期就能限制 summary 的合法类型。
 */
sealed interface ViewDocumentSummaryDto

internal data class ViewDocumentDto(
    val visibleGraph: GraphDocumentDto,
    val fullGraph: GraphDocumentDto,
    val anchorNodeId: String?,
    val projectionIndex: GraphProjectionIndexDto,
    val summary: ViewDocumentSummaryDto,
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
) : ViewDocumentSummaryDto

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
) : ViewDocumentSummaryDto

internal data class ResourceRelationViewSummaryDto(
    val visibleNodeCount: Int,
    val relationCount: Int,
    val resourceCount: Int,
    val fallbackReason: String?,
    val laneCounts: Map<String, Int>,
) : ViewDocumentSummaryDto

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
) : ViewDocumentSummaryDto

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
) : ViewDocumentSummaryDto

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
) : ViewDocumentSummaryDto

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
