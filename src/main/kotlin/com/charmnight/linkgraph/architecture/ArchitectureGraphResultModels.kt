package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.application.indexed.IndexedGraphSummary
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.presentation.GraphViewPresentation
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.usage.ClassUsageSearchResult

/**
 * 项目结构关系分组，描述两个节点在项目结构视图上展示的聚合关系。
 */
data class ProjectStructureRelationGroup(
    /** 分组唯一标识。 */
    val id: String,
    /** 起点 ID。 */
    val fromNodeId: String,
    /** 终点 ID。 */
    val toNodeId: String,
    /** 用于展示的关系种类字符串。 */
    val displayRelationKind: String,
    /** 关系展示名。 */
    val displayRelation: String,
    /** 实际包含的关系种类列表。 */
    val relationKinds: List<String> = emptyList(),
    /** 聚合后的关系总数。 */
    val count: Int = 0,
    /** 置信度字符串。 */
    val confidence: String = "UNKNOWN",
    /** 关联的原始关系 ID。 */
    val sourceRelationIds: List<String> = emptyList(),
    /** 样例证据引用列表。 */
    val sampleEvidenceRefs: List<String> = emptyList(),
    /** 是否默认可见。 */
    val defaultVisible: Boolean = false,
    /** 被隐藏时的原因说明。 */
    val hiddenReason: String? = null,
)

/** 架构图统计摘要，描述节点与边的数量分布等概览信息。 */
data class ArchitectureGraphSummary(
    val moduleCount: Int = 0,
    val packageCount: Int = 0,
    val serviceCount: Int = 0,
    val componentCount: Int = 0,
    val resourceCount: Int = 0,
    val layerCount: Int = 0,
    val libraryCount: Int = 0,
    val jdkCount: Int = 0,
    val relationCount: Int = 0,
    val classCount: Int = 0,
    val relationshipNodeCount: Int = 0,
    val inventoryOnlyNodeCount: Int = 0,
    val unconnectedPackageCount: Int = 0,
    val unconnectedComponentCount: Int = 0,
    val unconnectedServiceBoundaryCount: Int = 0,
    val unconnectedResourceCount: Int = 0,
    val externalDependencyGroupCount: Int = 0,
    val jdkGroupCount: Int = 0,
    val truncated: Boolean = false,
    val hiddenNodeCount: Int = 0,
    val hiddenEdgeCount: Int = 0,
    val indexed: IndexedGraphSummary? = null,
    val projectStructureRelationGroups: List<ProjectStructureRelationGroup> = emptyList(),
)

/** 架构图渲染结果，包含可见图、完整图、锚点节点、摘要、投影索引和呈现信息。 */
data class ArchitectureGraphResult(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val anchorNodeId: String? = null,
    val summary: ArchitectureGraphSummary = ArchitectureGraphSummary(),
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
    val presentation: GraphViewPresentation = GraphViewPresentation(),
)

/** 类图统计摘要，记录类、成员、关系、截断与作用域相关数量。 */
data class ClassDiagramSummary(
    val classCount: Int = 0,
    val fieldCount: Int = 0,
    val interfaceCount: Int = 0,
    val enumCount: Int = 0,
    val annotationCount: Int = 0,
    val recordCount: Int = 0,
    val objectCount: Int = 0,
    val relationCount: Int = 0,
    val spiProviderCount: Int = 0,
    val reflectionRelationCount: Int = 0,
    val relationCompleteness: String = "COMPLETE",
    val scopeTypeCount: Int = 0,
    val projectTypeCount: Int = 0,
    val projectClassCount: Int = 0,
    val scopeBasis: String = "CLASS_NEIGHBORHOOD",
    val anchorTypeNodeId: String? = null,
    val anchorTypeTitle: String? = null,
    val anchorTypeQualifiedName: String? = null,
    val neighborhoodLimit: Int = 0,
    val memberLimit: Int = 0,
    val neighborhoodCandidateTypeCount: Int = 0,
    val neighborhoodTruncated: Boolean = false,
    val truncated: Boolean = false,
    val hiddenNodeCount: Int = 0,
    val hiddenEdgeCount: Int = 0,
    val indexed: IndexedGraphSummary? = null,
)

/** 类图渲染结果，包含可见图、完整图、锚点节点、摘要、投影索引、呈现信息与使用情况。 */
data class ClassDiagramResult(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val anchorNodeId: String? = null,
    val summary: ClassDiagramSummary = ClassDiagramSummary(),
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
    val presentation: GraphViewPresentation = GraphViewPresentation(),
    val usage: ClassUsageSearchResult? = null,
)
