package com.charmnight.linkgraph.application.indexed

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.projection.GraphProjectionMetadata
import com.charmnight.linkgraph.projection.graphProjectionHiddenCounts
import com.charmnight.linkgraph.projection.graphProjectionHiddenNodes

enum class IndexedGraphLayerKind {
    PROJECT_SOURCE,
    EXTERNAL_LIBRARY,
    JDK,
    RESOURCE,
    AGGREGATE,
}

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

enum class IndexedGraphSourceKind {
    SOURCE_CLASS,
    LIBRARY_CLASS,
    JDK_CLASS,
    RESOURCE_FILE,
    SYNTHETIC_AGGREGATE,
}

enum class IndexedGraphRelationLayer {
    PROJECT_INTERNAL,
    PROJECT_TO_EXTERNAL,
    PROJECT_TO_JDK,
    PROJECT_TO_RESOURCE,
    AGGREGATE,
}

data class IndexedGraphLayerCounts(
    val projectSource: Int = 0,
    val externalLibrary: Int = 0,
    val jdk: Int = 0,
    val resource: Int = 0,
    val aggregate: Int = 0,
) {
    operator fun plus(other: IndexedGraphLayerCounts): IndexedGraphLayerCounts =
        IndexedGraphLayerCounts(
            projectSource = projectSource + other.projectSource,
            externalLibrary = externalLibrary + other.externalLibrary,
            jdk = jdk + other.jdk,
            resource = resource + other.resource,
            aggregate = aggregate + other.aggregate,
        )

    operator fun minus(other: IndexedGraphLayerCounts): IndexedGraphLayerCounts =
        IndexedGraphLayerCounts(
            projectSource = (projectSource - other.projectSource).coerceAtLeast(0),
            externalLibrary = (externalLibrary - other.externalLibrary).coerceAtLeast(0),
            jdk = (jdk - other.jdk).coerceAtLeast(0),
            resource = (resource - other.resource).coerceAtLeast(0),
            aggregate = (aggregate - other.aggregate).coerceAtLeast(0),
        )

    fun total(): Int = projectSource + externalLibrary + jdk + resource + aggregate

    companion object {
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

data class IndexedGraphFreshness(
    val state: String = "FRESH",
    val dirtyReason: String? = null,
    val pendingFileCount: Int = 0,
    val pendingFileSamples: List<String> = emptyList(),
    val lastIndexedAtEpochMillis: Long? = null,
    val staleSinceEpochMillis: Long? = null,
)

data class IndexedGraphVisibilityReason(
    val code: String,
    val label: String,
    val nodeCount: Int = 0,
    val edgeCount: Int = 0,
)

data class IndexedGraphRequest(
    val view: IndexedGraphView,
    val anchor: IndexedGraphAnchor? = null,
    val scope: IndexedGraphScope = IndexedGraphScope.Project,
    val relationKinds: Set<String> = emptySet(),
    val depth: Int = 1,
    val includeProjectSources: Boolean = true,
    val includeExternalLibraries: Boolean = false,
    val includeJdk: Boolean = false,
    val completeness: IndexedGraphCompleteness = IndexedGraphCompleteness.Interactive,
    val refreshPolicy: IndexedGraphRefreshPolicy = IndexedGraphRefreshPolicy.ReuseCached,
    val viewport: IndexedGraphViewportOptions = IndexedGraphViewportOptions(),
    val classDiagram: IndexedClassDiagramOptions = IndexedClassDiagramOptions(),
    val review: IndexedReviewGraphOptions = IndexedReviewGraphOptions(),
)

data class IndexedGraphViewportOptions(
    val maxVisibleNodes: Int? = null,
    val maxVisibleEdges: Int? = null,
)

data class IndexedClassDiagramOptions(
    val neighborhoodLimit: Int = 24,
    val memberLimit: Int = 5,
)

data class IndexedReviewGraphOptions(
    val maxChangedNodes: Int = 120,
    val maxRelatedTestNodes: Int = 40,
    val maxUpstreamNodes: Int = 40,
    val maxDownstreamNodes: Int = 40,
)

enum class IndexedGraphView {
    ARCHITECTURE,
    CLASS_DIAGRAM,
    REVIEW,
}

sealed interface IndexedGraphAnchor {
    data class ClassId(val nodeId: String) : IndexedGraphAnchor
    data class ClassName(val qualifiedName: String) : IndexedGraphAnchor
    data class ArchitectureNode(val nodeId: String) : IndexedGraphAnchor
    data class DiffItem(val id: String) : IndexedGraphAnchor
    data class CurrentEditor(val requireClass: Boolean = false) : IndexedGraphAnchor
}

sealed interface IndexedGraphScope {
    data object Project : IndexedGraphScope
    data class Package(val qualifiedName: String) : IndexedGraphScope
    data class ArchitectureNode(val nodeId: String) : IndexedGraphScope
    data class ClassNeighborhood(val depth: Int) : IndexedGraphScope
    data class ReviewSelection(val selectedDiffItemIds: List<String>) : IndexedGraphScope
}

enum class IndexedGraphCompleteness {
    StructureOnly,
    Interactive,
    CompleteWithinScope,
}

enum class IndexedGraphRefreshPolicy {
    ReuseCached,
    ReprojectCached,
    ForceRebuild,
}

data class IndexedGraphSummary(
    val view: String,
    val anchorKind: String? = null,
    val anchorNodeId: String? = null,
    val anchorTitle: String? = null,
    val anchorQualifiedName: String? = null,
    val scopeKind: String,
    val scopeLabel: String,
    val relationKinds: Set<String> = emptySet(),
    val depth: Int,
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
    val completeness: String,
    val cacheState: String,
    val includeExternalLibraries: Boolean = true,
    val includeJdk: Boolean = true,
    val projectSourceNodeCount: Int = 0,
    val externalLibraryNodeCount: Int = 0,
    val resourceNodeCount: Int = 0,
    val aggregateNodeCount: Int = 0,
    val projectLayerCounts: IndexedGraphLayerCounts = IndexedGraphLayerCounts(),
    val visibleLayerCounts: IndexedGraphLayerCounts = IndexedGraphLayerCounts(),
    val scopedLayerCounts: IndexedGraphLayerCounts = IndexedGraphLayerCounts(),
    val candidateLayerCounts: IndexedGraphLayerCounts = IndexedGraphLayerCounts(),
    val hiddenLayerCounts: IndexedGraphLayerCounts = IndexedGraphLayerCounts(),
    val collapsedLayerCounts: IndexedGraphLayerCounts = IndexedGraphLayerCounts(),
    val freshness: IndexedGraphFreshness = IndexedGraphFreshness(),
    val visibilityReasons: List<IndexedGraphVisibilityReason> = emptyList(),
)

fun IndexedGraphRequest.classDiagramScopeNodeId(): String? =
    when {
        view != IndexedGraphView.CLASS_DIAGRAM -> null
        anchor is IndexedGraphAnchor.ArchitectureNode -> anchor.nodeId
        anchor is IndexedGraphAnchor.ClassId -> anchor.nodeId
        scope is IndexedGraphScope.ArchitectureNode -> scope.nodeId
        else -> null
    }

fun IndexedGraphRequest.reviewSelectedDiffItemIds(): List<String> =
    (scope as? IndexedGraphScope.ReviewSelection)?.selectedDiffItemIds
        ?: anchor?.let { anchor ->
            when (anchor) {
                is IndexedGraphAnchor.DiffItem -> listOf(anchor.id)
                else -> emptyList()
            }
        }
        ?: emptyList()

fun IndexedGraphRequest.cacheState(hadCachedFullIndex: Boolean): String =
    when (refreshPolicy) {
        IndexedGraphRefreshPolicy.ForceRebuild -> "FORCE_REBUILD"
        IndexedGraphRefreshPolicy.ReprojectCached -> if (hadCachedFullIndex) "REPROJECT_CACHED" else "CACHE_MISS"
        IndexedGraphRefreshPolicy.ReuseCached -> if (hadCachedFullIndex) "REUSED_FULL_INDEX" else "CACHE_MISS"
    }

fun IndexedGraphRequest.scopeKind(): String =
    when (scope) {
        IndexedGraphScope.Project -> "PROJECT"
        is IndexedGraphScope.Package -> "PACKAGE"
        is IndexedGraphScope.ArchitectureNode -> "ARCHITECTURE_NODE"
        is IndexedGraphScope.ClassNeighborhood -> "CLASS_NEIGHBORHOOD"
        is IndexedGraphScope.ReviewSelection -> "REVIEW_SELECTION"
    }

fun IndexedGraphRequest.scopeLabel(): String =
    when (scope) {
        IndexedGraphScope.Project -> "Project"
        is IndexedGraphScope.Package -> scope.qualifiedName
        is IndexedGraphScope.ArchitectureNode -> scope.nodeId
        is IndexedGraphScope.ClassNeighborhood -> "Class neighborhood depth ${scope.depth}"
        is IndexedGraphScope.ReviewSelection -> "Review selection (${scope.selectedDiffItemIds.size})"
    }

fun IndexedGraphRequest.anchorKind(): String? =
    when (anchor) {
        null -> null
        is IndexedGraphAnchor.ClassId -> "CLASS_ID"
        is IndexedGraphAnchor.ClassName -> "CLASS_NAME"
        is IndexedGraphAnchor.ArchitectureNode -> "ARCHITECTURE_NODE"
        is IndexedGraphAnchor.DiffItem -> "DIFF_ITEM"
        is IndexedGraphAnchor.CurrentEditor -> "CURRENT_EDITOR"
    }

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

fun Iterable<GraphNode>.indexedLayerCounts(): IndexedGraphLayerCounts =
    fold(IndexedGraphLayerCounts()) { counts, node ->
        counts + IndexedGraphLayerCounts.single(node.indexedLayerKind())
    }

fun GraphNode.indexedLayerKind(): IndexedGraphLayerKind =
    metadata["indexed.layerKind"]
        ?.let { raw -> IndexedGraphLayerKind.entries.firstOrNull { it.name == raw } }
        ?: IndexedGraphLayerKind.AGGREGATE

private fun GraphDocument.indexedOverflowLayerCounts(): IndexedGraphLayerCounts =
    nodes.fold(IndexedGraphLayerCounts()) { counts, node ->
        counts + node.indexedCollapsedLayerCounts()
    }

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
