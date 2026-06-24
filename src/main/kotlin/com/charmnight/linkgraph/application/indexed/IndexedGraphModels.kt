package com.charmnight.linkgraph.application.indexed

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.projection.GraphProjectionMetadata
import com.charmnight.linkgraph.projection.graphProjectionHiddenCounts
import com.charmnight.linkgraph.projection.graphProjectionHiddenNodes
import com.charmnight.linkgraph.usage.ClassUsageSearchLimits

/** 图节点分层类别，用于在前端区分项目源码、外部库、JDK、资源等。 */
enum class IndexedGraphLayerKind {
    /** 项目自身源码。 */
    PROJECT_SOURCE,
    /** 外部三方库。 */
    EXTERNAL_LIBRARY,
    /** JDK 内部类。 */
    JDK,
    /** 配置/SQL/XML 等资源。 */
    RESOURCE,
    /** 模块/包等合成聚合层。 */
    AGGREGATE,
}

/** 节点的业务角色，用于配色和图标识别。 */
enum class IndexedGraphNodeRole {
    ENTRY,
    SERVICE,
    DATA,
    CONFIG,
    API,
    TEST,
    RESOURCE,
    EXTERNAL,
    UNKNOWN,
}

/** 节点的来源类别，描述节点的物理来源。 */
enum class IndexedGraphSourceKind {
    /** 项目源码中的类。 */
    SOURCE_CLASS,
    /** 三方库中的类。 */
    LIBRARY_CLASS,
    /** JDK 中的类。 */
    JDK_CLASS,
    /** 配置/SQL/XML 等资源文件。 */
    RESOURCE_FILE,
    /** 模块/包等合成的聚合节点。 */
    SYNTHETIC_AGGREGATE,
}

/** 关系所属的分层组合，标识关系是项目内部还是跨层调用。 */
enum class IndexedGraphRelationLayer {
    /** 项目内部关系。 */
    PROJECT_INTERNAL,
    /** 项目到外部三方库。 */
    PROJECT_TO_EXTERNAL,
    /** 项目到 JDK。 */
    PROJECT_TO_JDK,
    /** 项目到资源。 */
    PROJECT_TO_RESOURCE,
    /** 聚合关系（包含等）。 */
    AGGREGATE,
}

/**
 * 分层计数：每层包含的节点数量。
 * 提供加减运算以便在不同视图之间累加或扣除。
 */
data class IndexedGraphLayerCounts(
    /** 项目源码层节点数。 */
    val projectSource: Int = 0,
    /** 外部库层节点数。 */
    val externalLibrary: Int = 0,
    /** JDK 层节点数。 */
    val jdk: Int = 0,
    /** 资源层节点数。 */
    val resource: Int = 0,
    /** 聚合层节点数。 */
    val aggregate: Int = 0,
) {
    /** 把两个计数相加。 */
    operator fun plus(other: IndexedGraphLayerCounts): IndexedGraphLayerCounts =
        IndexedGraphLayerCounts(
            projectSource = projectSource + other.projectSource,
            externalLibrary = externalLibrary + other.externalLibrary,
            jdk = jdk + other.jdk,
            resource = resource + other.resource,
            aggregate = aggregate + other.aggregate,
        )

    /** 把两个计数相减（结果不会小于 0）。 */
    operator fun minus(other: IndexedGraphLayerCounts): IndexedGraphLayerCounts =
        IndexedGraphLayerCounts(
            projectSource = (projectSource - other.projectSource).coerceAtLeast(0),
            externalLibrary = (externalLibrary - other.externalLibrary).coerceAtLeast(0),
            jdk = (jdk - other.jdk).coerceAtLeast(0),
            resource = (resource - other.resource).coerceAtLeast(0),
            aggregate = (aggregate - other.aggregate).coerceAtLeast(0),
        )

    /** 所有分层节点总数。 */
    fun total(): Int = projectSource + externalLibrary + jdk + resource + aggregate

    companion object {
        /** 构造一个只包含单个分层计数的实例。 */
        fun single(layerKind: IndexedGraphLayerKind, count: Int = 1): IndexedGraphLayerCounts =
            when (layerKind) {
                IndexedGraphLayerKind.PROJECT_SOURCE -> IndexedGraphLayerCounts(projectSource = count)
                IndexedGraphLayerKind.EXTERNAL_LIBRARY -> IndexedGraphLayerCounts(externalLibrary = count)
                IndexedGraphLayerKind.JDK -> IndexedGraphLayerCounts(jdk = count)
                IndexedGraphLayerKind.RESOURCE -> IndexedGraphLayerCounts(resource = count)
                IndexedGraphLayerKind.AGGREGATE -> IndexedGraphLayerCounts(aggregate = count)
            }
    }
}

/** 索引新鲜度信息，描述当前缓存索引是否仍然有效。 */
data class IndexedGraphFreshness(
    /** 新鲜度状态代码。 */
    val state: String = "FRESH",
    /** 如果失效，原因说明。 */
    val dirtyReason: String? = null,
    /** 待重新索引的文件数量。 */
    val pendingFileCount: Int = 0,
    /** 待重新索引的文件样例路径。 */
    val pendingFileSamples: List<String> = emptyList(),
    /** 上次索引完成的时间戳（毫秒）。 */
    val lastIndexedAtEpochMillis: Long? = null,
    /** 失效开始时间戳（毫秒）。 */
    val staleSinceEpochMillis: Long? = null,
)

/** 描述图节点或边被隐藏/折叠的具体原因，用于前端展示。 */
data class IndexedGraphVisibilityReason(
    /** 原因代码。 */
    val code: String,
    /** 用户可读的原因说明。 */
    val label: String,
    /** 涉及的节点数。 */
    val nodeCount: Int = 0,
    /** 涉及的边数。 */
    val edgeCount: Int = 0,
)

/**
 * 一次索引图请求的所有参数。
 *
 * 涵盖视图种类、锚点、范围、深度、是否包含外部库/JDK、
 * 完整度、关系详情、刷新策略、视口裁剪以及与类图/类使用/审查相关的子选项。
 */
data class IndexedGraphRequest(
    /** 目标视图种类。 */
    val view: IndexedGraphView,
    /** 请求锚点；为空表示无锚点。 */
    val anchor: IndexedGraphAnchor? = null,
    /** 视图范围；默认为整个项目。 */
    val scope: IndexedGraphScope = IndexedGraphScope.Project,
    /** 限定返回的关系种类；为空表示不限定。 */
    val relationKinds: Set<String> = emptySet(),
    /** 邻域展开深度。 */
    val depth: Int = 1,
    /** 是否包含项目源码层。 */
    val includeProjectSources: Boolean = true,
    /** 是否包含外部三方库层。 */
    val includeExternalLibraries: Boolean = false,
    /** 是否包含 JDK 层。 */
    val includeJdk: Boolean = false,
    /** 完整度档位，决定返回的结构详细程度。 */
    val completeness: IndexedGraphCompleteness = IndexedGraphCompleteness.Interactive,
    /** 关系详情档位，决定是否包含方法体内部调用。 */
    val relationDetail: IndexedGraphRelationDetail = IndexedGraphRelationDetail.STRUCTURE_ONLY,
    /** 索引刷新策略。 */
    val refreshPolicy: IndexedGraphRefreshPolicy = IndexedGraphRefreshPolicy.ReuseCached,
    /** 视口裁剪参数。 */
    val viewport: IndexedGraphViewportOptions = IndexedGraphViewportOptions(),
    /** 类图相关选项。 */
    val classDiagram: IndexedClassDiagramOptions = IndexedClassDiagramOptions(),
    /** 类使用处查询选项。 */
    val usage: IndexedClassUsageOptions = IndexedClassUsageOptions(),
    /** 审查图相关选项。 */
    val review: IndexedReviewGraphOptions = IndexedReviewGraphOptions(),
)

/** 视口裁剪选项，限制可见节点/边的数量。 */
data class IndexedGraphViewportOptions(
    /** 可见节点上限。 */
    val maxVisibleNodes: Int? = null,
    /** 可见边上限。 */
    val maxVisibleEdges: Int? = null,
)

/** 类图专用选项。 */
data class IndexedClassDiagramOptions(
    /** 邻域展开上限。 */
    val neighborhoodLimit: Int = 24,
    /** 每个类展示的成员上限。 */
    val memberLimit: Int = 5,
)

/** 类使用处查询选项。 */
data class IndexedClassUsageOptions(
    /** 是否启用类使用处查询。 */
    val enabled: Boolean = false,
    /** 目标节点 ID。 */
    val targetNodeId: String? = null,
    /** 目标类全限定名。 */
    val targetQualifiedName: String? = null,
    /** 起源虚拟文件 URL。 */
    val sourceVirtualFileUrl: String? = null,
    /** 起源物理路径。 */
    val sourcePath: String? = null,
    /** 使用处分组上限。 */
    val maxUsageGroups: Int = ClassUsageSearchLimits.DEFAULT_USAGE_GROUPS,
    /** 使用处条目上限。 */
    val maxUsageEntries: Int = ClassUsageSearchLimits.DEFAULT_USAGE_ENTRIES,
    /** 是否包含 import 引用。 */
    val includeImports: Boolean = false,
)

/** 审查图专用选项。 */
data class IndexedReviewGraphOptions(
    /** 变更节点上限。 */
    val maxChangedNodes: Int = 120,
    /** 相关测试节点上限。 */
    val maxRelatedTestNodes: Int = 40,
    /** 上游节点上限。 */
    val maxUpstreamNodes: Int = 40,
    /** 下游节点上限。 */
    val maxDownstreamNodes: Int = 40,
)

/** 索引图视图种类。 */
enum class IndexedGraphView {
    /** 架构总览图。 */
    ARCHITECTURE,
    /** 类图。 */
    CLASS_DIAGRAM,
    /** 审查图。 */
    REVIEW,
}

/** 索引图锚点；用于在请求中指定"以谁为中心展开"。 */
sealed interface IndexedGraphAnchor {
    /** 通过节点 ID 指定一个类作为锚点。 */
    data class ClassId(val nodeId: String) : IndexedGraphAnchor
    /** 通过类全限定名指定锚点。 */
    data class ClassName(val qualifiedName: String) : IndexedGraphAnchor
    /** 通过架构节点 ID 指定锚点。 */
    data class ArchitectureNode(val nodeId: String) : IndexedGraphAnchor
    /** 通过差异条目 ID 指定锚点。 */
    data class DiffItem(val id: String) : IndexedGraphAnchor
    /** 以当前编辑器为锚点；可要求编辑器必须停留在类上。 */
    data class CurrentEditor(val requireClass: Boolean = false) : IndexedGraphAnchor
}

/** 索引图范围；用于限定返回内容的边界。 */
sealed interface IndexedGraphScope {
    /** 整个项目。 */
    data object Project : IndexedGraphScope
    /** 指定包名范围。 */
    data class Package(val qualifiedName: String) : IndexedGraphScope
    /** 指定架构节点范围。 */
    data class ArchitectureNode(val nodeId: String) : IndexedGraphScope
    /** 指定类的邻域范围（带深度）。 */
    data class ClassNeighborhood(val depth: Int) : IndexedGraphScope
    /** 审查选择的差异条目集合。 */
    data class ReviewSelection(val selectedDiffItemIds: List<String>) : IndexedGraphScope
}

/** 索引图完整度档位。 */
enum class IndexedGraphCompleteness {
    /** 仅返回结构。 */
    StructureOnly,
    /** 交互式返回（默认）。 */
    Interactive,
    /** 范围内全量返回。 */
    CompleteWithinScope,
}

/** 关系详情档位。 */
enum class IndexedGraphRelationDetail {
    /** 仅结构（无方法体）。 */
    STRUCTURE_ONLY,
    /** 当前范围内的方法体调用。 */
    SCOPED_BODY_RELATIONS,
    /** 完整方法体调用。 */
    COMPLETE,
}

/** 索引刷新策略。 */
enum class IndexedGraphRefreshPolicy {
    /** 复用缓存。 */
    ReuseCached,
    /** 复用索引但重新投影。 */
    ReprojectCached,
    /** 强制重建。 */
    ForceRebuild,
}

/**
 * 索引图汇总信息，描述投影结果的统计指标。
 * 前端使用这些信息显示加载状态、隐藏数量、新鲜度等。
 */
data class IndexedGraphSummary(
    /** 当前请求的视图种类名称，对应 [IndexedGraphView] 枚举。 */
    val view: String,
    /** 锚点种类代码，由请求的锚点派生，前端用于区分以何者为中心展开。 */
    val anchorKind: String? = null,
    /** 锚点节点 ID；若锚点为类、架构节点或差异条目，则填入解析后的中心节点 ID。 */
    val anchorNodeId: String? = null,
    /** 锚点的展示标题，用于标题栏与面包屑显示。 */
    val anchorTitle: String? = null,
    /** 锚点的全限定名（若可解析），便于跨视图复用。 */
    val anchorQualifiedName: String? = null,
    /** 当前请求的范围种类代码，对应 [IndexedGraphScope] 子类。 */
    val scopeKind: String,
    /** 当前请求范围的展示文本，用于 UI 标识。 */
    val scopeLabel: String,
    /** 限定返回的关系种类集合；空集表示未限定。 */
    val relationKinds: Set<String> = emptySet(),
    /** 实际生效的邻域展开深度。 */
    val depth: Int,
    /** 项目整体索引中的节点总数（含隐藏与折叠节点）。 */
    val projectNodeCount: Int,
    /** 项目源码中的类节点数量（用于校验索引覆盖度）。 */
    val projectClassCount: Int,
    /** 三方库节点数量。 */
    val externalNodeCount: Int,
    /** JDK 节点数量。 */
    val jdkNodeCount: Int,
    /** 范围内（应用 scope 后）的节点总数。 */
    val scopedNodeCount: Int,
    /** 实际可见节点数量（应用 viewport 折叠后）。 */
    val visibleNodeCount: Int,
    /** 因 viewport 限制被隐藏的节点数量。 */
    val hiddenNodeCount: Int,
    /** 因 viewport 限制被隐藏的边数量。 */
    val hiddenEdgeCount: Int,
    /** 候选节点总数（含可见与隐藏），用于描述投影规模。 */
    val candidateNodeCount: Int,
    /** 候选边总数（含可见与隐藏）。 */
    val candidateEdgeCount: Int,
    /** 是否发生了 viewport 截断，前端用于显示截断提示。 */
    val truncated: Boolean,
    /** 完整度档位名称，对应 [IndexedGraphCompleteness]。 */
    val completeness: String,
    /** 索引缓存状态代码（如 CACHE_MISS / REUSED_FULL_INDEX 等）。 */
    val cacheState: String,
    /** 是否包含外部三方库层。 */
    val includeExternalLibraries: Boolean = true,
    /** 是否包含 JDK 层。 */
    val includeJdk: Boolean = true,
    /** 项目源码分层节点数（兼容旧字段）。 */
    val projectSourceNodeCount: Int = 0,
    /** 外部库分层节点数（兼容旧字段）。 */
    val externalLibraryNodeCount: Int = 0,
    /** 资源分层节点数。 */
    val resourceNodeCount: Int = 0,
    /** 聚合（合成）节点数。 */
    val aggregateNodeCount: Int = 0,
    /** 项目整体索引中各分层节点计数。 */
    val projectLayerCounts: IndexedGraphLayerCounts = IndexedGraphLayerCounts(),
    /** 可见节点各分层计数。 */
    val visibleLayerCounts: IndexedGraphLayerCounts = IndexedGraphLayerCounts(),
    /** 范围内节点各分层计数。 */
    val scopedLayerCounts: IndexedGraphLayerCounts = IndexedGraphLayerCounts(),
    /** 候选节点各分层计数。 */
    val candidateLayerCounts: IndexedGraphLayerCounts = IndexedGraphLayerCounts(),
    /** 隐藏节点各分层计数。 */
    val hiddenLayerCounts: IndexedGraphLayerCounts = IndexedGraphLayerCounts(),
    /** 折叠为桶的节点各分层计数。 */
    val collapsedLayerCounts: IndexedGraphLayerCounts = IndexedGraphLayerCounts(),
    /** 索引新鲜度状态。 */
    val freshness: IndexedGraphFreshness = IndexedGraphFreshness(),
    /** 可见性原因列表，用于前端展示为何某些节点/边被隐藏。 */
    val visibilityReasons: List<IndexedGraphVisibilityReason> = emptyList(),
)

/** 类图视图专用：从请求中解析当前类图的中心节点 ID。 */
fun IndexedGraphRequest.classDiagramScopeNodeId(): String? =
    when {
        view != IndexedGraphView.CLASS_DIAGRAM -> null
        anchor is IndexedGraphAnchor.ArchitectureNode -> anchor.nodeId
        anchor is IndexedGraphAnchor.ClassId -> anchor.nodeId
        scope is IndexedGraphScope.ArchitectureNode -> scope.nodeId
        else -> null
    }

/** 审查图视图专用：从请求中提取当前选中的差异条目 ID 列表，支持通过 scope 或单条锚点传入。 */
fun IndexedGraphRequest.reviewSelectedDiffItemIds(): List<String> =
    (scope as? IndexedGraphScope.ReviewSelection)?.selectedDiffItemIds
        ?: anchor?.let { anchor ->
            when (anchor) {
                is IndexedGraphAnchor.DiffItem -> listOf(anchor.id)
                else -> emptyList()
            }
        }
        ?: emptyList()

/** 依据刷新策略和是否存在全量索引缓存，返回缓存状态代码。 */
fun IndexedGraphRequest.cacheState(hadCachedFullIndex: Boolean): String =
    when (refreshPolicy) {
        IndexedGraphRefreshPolicy.ForceRebuild -> "FORCE_REBUILD"
        IndexedGraphRefreshPolicy.ReprojectCached -> if (hadCachedFullIndex) "REPROJECT_CACHED" else "CACHE_MISS"
        IndexedGraphRefreshPolicy.ReuseCached -> if (hadCachedFullIndex) "REUSED_FULL_INDEX" else "CACHE_MISS"
    }

/** 将 scope 子类映射成可读的字符串代码，用于前端区分范围类型。 */
fun IndexedGraphRequest.scopeKind(): String =
    when (scope) {
        IndexedGraphScope.Project -> "PROJECT"
        is IndexedGraphScope.Package -> "PACKAGE"
        is IndexedGraphScope.ArchitectureNode -> "ARCHITECTURE_NODE"
        is IndexedGraphScope.ClassNeighborhood -> "CLASS_NEIGHBORHOOD"
        is IndexedGraphScope.ReviewSelection -> "REVIEW_SELECTION"
    }

/** 将 scope 子类映射成面向用户展示的简短文本。 */
fun IndexedGraphRequest.scopeLabel(): String =
    when (scope) {
        IndexedGraphScope.Project -> "Project"
        is IndexedGraphScope.Package -> scope.qualifiedName
        is IndexedGraphScope.ArchitectureNode -> scope.nodeId
        is IndexedGraphScope.ClassNeighborhood -> "Class neighborhood depth ${scope.depth}"
        is IndexedGraphScope.ReviewSelection -> "Review selection (${scope.selectedDiffItemIds.size})"
    }

/** 将 anchor 子类（或空）映射成锚点种类代码，前端据此渲染锚点标签。 */
fun IndexedGraphRequest.anchorKind(): String? =
    when (anchor) {
        null -> null
        is IndexedGraphAnchor.ClassId -> "CLASS_ID"
        is IndexedGraphAnchor.ClassName -> "CLASS_NAME"
        is IndexedGraphAnchor.ArchitectureNode -> "ARCHITECTURE_NODE"
        is IndexedGraphAnchor.DiffItem -> "DIFF_ITEM"
        is IndexedGraphAnchor.CurrentEditor -> "CURRENT_EDITOR"
    }

/**
 * 在请求参数、索引数据与投影结果之上聚合出最终的图汇总信息。
 *
 * 内部会按分层统计可见、隐藏、折叠节点，解析锚点的标题与全限定名，
 * 并组装视口截断、可见性原因等附加元数据。
 */
fun IndexedGraphRequest.toSummary(
    index: ArchitectureGraphIndex,
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
    anchorNodeId: String?,
    anchorTitle: String? = null,
    anchorQualifiedName: String? = null,
    scopedNodeCount: Int = fullGraph.nodes.size,
    candidateNodeCount: Int = fullGraph.nodes.size,
    candidateEdgeCount: Int = fullGraph.edges.size,
    hiddenNodeCount: Int = graphProjectionHiddenCounts(visibleGraph = visibleGraph, fullGraph = fullGraph).hiddenNodeCount,
    hiddenEdgeCount: Int = graphProjectionHiddenCounts(visibleGraph = visibleGraph, fullGraph = fullGraph).hiddenEdgeCount,
    truncated: Boolean = hiddenNodeCount > 0 || hiddenEdgeCount > 0,
    candidateLayerCounts: IndexedGraphLayerCounts? = null,
    cacheState: String,
    freshness: IndexedGraphFreshness = IndexedGraphFreshness(),
): IndexedGraphSummary {
    val projectLayerCounts = index.graph.nodes
        .fold(IndexedGraphLayerCounts()) { counts, node ->
            counts + IndexedGraphLayerCounts.single(node.indexedLayerKind(index))
        }
    val visibleLayerCounts = visibleGraph.nodes.indexedLayerCounts()
    val scopedLayerCounts = fullGraph.nodes.indexedLayerCounts()
    val hiddenLayerCounts = graphProjectionHiddenNodes(visibleGraph = visibleGraph, fullGraph = fullGraph)
        .fold(IndexedGraphLayerCounts()) { counts, node ->
            counts + IndexedGraphLayerCounts.single(node.indexedLayerKind())
        }
    val collapsedLayerCounts = visibleGraph.indexedOverflowLayerCounts()
    return IndexedGraphSummary(
        view = view.name,
        anchorKind = anchorKind(),
        anchorNodeId = anchorNodeId,
        anchorTitle = anchorTitle
            ?: anchorNodeId?.let { nodeId -> fullGraph.nodes.firstOrNull { it.id == nodeId } ?: visibleGraph.nodes.firstOrNull { it.id == nodeId } }?.title,
        anchorQualifiedName = anchorQualifiedName
            ?: anchorNodeId?.let { nodeId -> index.node(nodeId)?.qualifiedName ?: index.findSymbol(nodeId)?.qualifiedName },
        scopeKind = scopeKind(),
        scopeLabel = scopeLabel(),
        relationKinds = relationKinds,
        depth = depth,
        projectNodeCount = index.graph.nodes.size,
        projectClassCount = index.symbolIndex.classesByQualifiedName.values.count { symbol ->
            !symbol.external && !symbol.library && !symbol.jdk && symbol.kind == JvmClassKind.CLASS
        },
        externalNodeCount = index.symbolIndex.classesByQualifiedName.values.count(JvmClassSymbol::library),
        jdkNodeCount = index.symbolIndex.classesByQualifiedName.values.count(JvmClassSymbol::jdk),
        scopedNodeCount = scopedNodeCount,
        visibleNodeCount = visibleGraph.nodes.size,
        hiddenNodeCount = hiddenNodeCount,
        hiddenEdgeCount = hiddenEdgeCount,
        candidateNodeCount = candidateNodeCount,
        candidateEdgeCount = candidateEdgeCount,
        truncated = truncated,
        completeness = completeness.name,
        cacheState = cacheState,
        includeExternalLibraries = includeExternalLibraries,
        includeJdk = includeJdk,
        projectSourceNodeCount = projectLayerCounts.projectSource,
        externalLibraryNodeCount = projectLayerCounts.externalLibrary,
        resourceNodeCount = projectLayerCounts.resource,
        aggregateNodeCount = index.graph.nodes.count { node -> node.indexedSourceKind(index) == IndexedGraphSourceKind.SYNTHETIC_AGGREGATE },
        projectLayerCounts = projectLayerCounts,
        visibleLayerCounts = visibleLayerCounts,
        scopedLayerCounts = scopedLayerCounts,
        candidateLayerCounts = candidateLayerCounts ?: scopedLayerCounts,
        hiddenLayerCounts = hiddenLayerCounts,
        collapsedLayerCounts = collapsedLayerCounts,
        freshness = freshness,
        visibilityReasons = indexedVisibilityReasons(
            hiddenNodeCount = hiddenNodeCount,
            hiddenEdgeCount = hiddenEdgeCount,
            truncated = truncated,
            includeExternalLibraries = includeExternalLibraries,
            includeJdk = includeJdk,
            collapsedLayerCounts = collapsedLayerCounts,
        ),
    )
}

/** 根据隐藏/折叠数量与启用开关汇总出可见性原因列表，用于前端显示为何节点被省略。 */
private fun indexedVisibilityReasons(
    hiddenNodeCount: Int,
    hiddenEdgeCount: Int,
    truncated: Boolean,
    includeExternalLibraries: Boolean,
    includeJdk: Boolean,
    collapsedLayerCounts: IndexedGraphLayerCounts,
): List<IndexedGraphVisibilityReason> = buildList {
    if (hiddenNodeCount > 0 || hiddenEdgeCount > 0 || truncated) {
        add(
            IndexedGraphVisibilityReason(
                code = "VIEWPORT_NODE_LIMIT",
                label = "窗口限制隐藏了部分节点或关系",
                nodeCount = hiddenNodeCount,
                edgeCount = hiddenEdgeCount,
            ),
        )
    }
    if (!includeExternalLibraries) {
        add(IndexedGraphVisibilityReason(code = "EXTERNAL_LAYER_DISABLED", label = "三方库层未启用"))
    }
    if (!includeJdk) {
        add(IndexedGraphVisibilityReason(code = "JDK_LAYER_DISABLED", label = "JDK 层未启用"))
    }
    val collapsed = collapsedLayerCounts.total()
    if (collapsed > 0) {
        add(IndexedGraphVisibilityReason(code = "COLLAPSED_BUCKET", label = "部分节点折叠为桶", nodeCount = collapsed))
    }
}

/** 遍历一组节点并按各自分层归类累加，得到该节点集合的分层计数。 */
fun Iterable<GraphNode>.indexedLayerCounts(): IndexedGraphLayerCounts =
    fold(IndexedGraphLayerCounts()) { counts, node ->
        counts + IndexedGraphLayerCounts.single(node.indexedLayerKind())
    }

/** 从节点 metadata 中读取分层类别，缺省时回落到聚合层。 */
fun GraphNode.indexedLayerKind(): IndexedGraphLayerKind =
    metadata["indexed.layerKind"]
        ?.let { raw -> IndexedGraphLayerKind.entries.firstOrNull { it.name == raw } }
        ?: IndexedGraphLayerKind.AGGREGATE

/** 在整张图上汇总被折叠（overflow bucket）节点的分层计数，用于在汇总信息中区分可见与折叠部分。 */
private fun GraphDocument.indexedOverflowLayerCounts(): IndexedGraphLayerCounts =
    nodes.fold(IndexedGraphLayerCounts()) { counts, node ->
        counts + node.indexedCollapsedLayerCounts()
    }

/** 解析单个节点的折叠数：优先使用显式分层折叠 metadata，其次读取 GRAPH_WINDOW overflow，最后回落到 collapsed_count。 */
private fun GraphNode.indexedCollapsedLayerCounts(): IndexedGraphLayerCounts {
    val explicit = IndexedGraphLayerCounts(
        projectSource = metadata[GraphProjectionMetadata.Indexed.Collapsed.PROJECT_SOURCE]?.toIntOrNull() ?: 0,
        externalLibrary = metadata[GraphProjectionMetadata.Indexed.Collapsed.EXTERNAL_LIBRARY]?.toIntOrNull() ?: 0,
        jdk = metadata[GraphProjectionMetadata.Indexed.Collapsed.JDK]?.toIntOrNull() ?: 0,
        resource = metadata[GraphProjectionMetadata.Indexed.Collapsed.RESOURCE]?.toIntOrNull() ?: 0,
        aggregate = metadata[GraphProjectionMetadata.Indexed.Collapsed.AGGREGATE]?.toIntOrNull() ?: 0,
    )
    if (explicit.total() > 0) {
        return explicit
    }
    if (metadata[GraphProjectionMetadata.Overflow.KIND] == "GRAPH_WINDOW") {
        val hiddenNodeCount = metadata[GraphProjectionMetadata.Hidden.NODE_COUNT]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        return IndexedGraphLayerCounts(aggregate = hiddenNodeCount)
    }
    val collapsedCount = metadata[GraphProjectionMetadata.Indexed.COLLAPSED_COUNT]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    return if (collapsedCount > 0) {
        IndexedGraphLayerCounts.single(indexedLayerKind(), collapsedCount)
    } else {
        IndexedGraphLayerCounts()
    }
}
