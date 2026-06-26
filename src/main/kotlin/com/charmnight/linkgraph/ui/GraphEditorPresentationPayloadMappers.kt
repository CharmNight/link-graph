package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.indexed.IndexedGraphFreshness
import com.charmnight.linkgraph.application.indexed.IndexedGraphLayerCounts
import com.charmnight.linkgraph.application.indexed.IndexedGraphSummary
import com.charmnight.linkgraph.application.indexed.IndexedGraphVisibilityReason
import com.charmnight.linkgraph.presentation.GraphHiddenBucket
import com.charmnight.linkgraph.presentation.GraphPresentationControls
import com.charmnight.linkgraph.presentation.GraphPresentationLane
import com.charmnight.linkgraph.presentation.GraphPresentationTarget
import com.charmnight.linkgraph.presentation.GraphViewPresentation

/** 图谱视图展示目标 DTO（P2-6 严格完整 DTO 化）。 */
internal data class GraphPresentationTargetDto(
    val nodeId: String?,
    val title: String?,
    val subtitle: String?,
    val location: String?,
)

internal data class GraphPresentationLaneDto(
    val id: String,
    val label: String?,
    val axis: String,
    val order: Int,
    val role: String?,
)

internal data class GraphHiddenBucketDto(
    val id: String,
    val label: String?,
    val count: Int,
    val nodeIds: List<String>,
    val edgeIds: List<String>,
)

internal data class GraphPresentationControlsDto(
    val primaryScope: String?,
    val availableScopes: List<String>,
    val searchable: Boolean,
    val expandable: Boolean,
)

internal data class GraphViewPresentationDto(
    val target: GraphPresentationTargetDto,
    val lanes: List<GraphPresentationLaneDto>,
    val hiddenBuckets: List<GraphHiddenBucketDto>,
    val controls: GraphPresentationControlsDto,
)

/** 把图谱视图展示模型转换为前端 DTO（含目标、泳道、隐藏桶、控件）。 */
internal fun GraphViewPresentation.toDto(): GraphViewPresentationDto =
    GraphViewPresentationDto(
        target = GraphPresentationTargetDto(
            nodeId = target.nodeId,
            title = target.title,
            subtitle = target.subtitle,
            location = target.location,
        ),
        lanes = lanes.map { lane ->
            GraphPresentationLaneDto(
                id = lane.id,
                label = lane.label,
                axis = lane.axis.name,
                order = lane.order,
                role = lane.role,
            )
        },
        hiddenBuckets = hiddenBuckets.map { bucket ->
            GraphHiddenBucketDto(
                id = bucket.id,
                label = bucket.label,
                count = bucket.count,
                nodeIds = bucket.nodeIds,
                edgeIds = bucket.edgeIds,
            )
        },
        controls = GraphPresentationControlsDto(
            primaryScope = controls.primaryScope,
            availableScopes = controls.availableScopes,
            searchable = controls.searchable,
            expandable = controls.expandable,
        ),
    )

/** 把索引图谱摘要转换为前端负载 DTO，涵盖锚点、统计计数、层级分布与新鲜度等全部信息（P2-6 严格完整）。 */
internal fun IndexedGraphSummary.toDto(): IndexedGraphSummaryDto =
    IndexedGraphSummaryDto(
        view = view,
        anchorKind = anchorKind,
        anchorNodeId = anchorNodeId,
        anchorTitle = anchorTitle,
        anchorQualifiedName = anchorQualifiedName,
        scopeKind = scopeKind,
        scopeLabel = scopeLabel,
        relationKinds = relationKinds,
        depth = depth,
        projectNodeCount = projectNodeCount,
        projectClassCount = projectClassCount,
        externalNodeCount = externalNodeCount,
        jdkNodeCount = jdkNodeCount,
        scopedNodeCount = scopedNodeCount,
        visibleNodeCount = visibleNodeCount,
        hiddenNodeCount = hiddenNodeCount,
        hiddenEdgeCount = hiddenEdgeCount,
        candidateNodeCount = candidateNodeCount,
        candidateEdgeCount = candidateEdgeCount,
        truncated = truncated,
        completeness = completeness,
        cacheState = cacheState,
        includeExternalLibraries = includeExternalLibraries,
        includeJdk = includeJdk,
        projectSourceNodeCount = projectSourceNodeCount,
        externalLibraryNodeCount = externalLibraryNodeCount,
        resourceNodeCount = resourceNodeCount,
        aggregateNodeCount = aggregateNodeCount,
        projectLayerCounts = projectLayerCounts.toDto(),
        visibleLayerCounts = visibleLayerCounts.toDto(),
        scopedLayerCounts = scopedLayerCounts.toDto(),
        candidateLayerCounts = candidateLayerCounts.toDto(),
        hiddenLayerCounts = hiddenLayerCounts.toDto(),
        collapsedLayerCounts = collapsedLayerCounts.toDto(),
        freshness = freshness.toDto(),
        visibilityReasons = visibilityReasons.map { reason -> reason.toDto() },
    )

/** 把某类节点的可见性原因（含统计）转换为前端 DTO。 */
private fun IndexedGraphVisibilityReason.toDto(): IndexedGraphVisibilityReasonDto =
    IndexedGraphVisibilityReasonDto(
        code = code,
        label = label,
        nodeCount = nodeCount,
        edgeCount = edgeCount,
    )

/** 把索引新鲜度状态（脏原因、待处理文件、时间戳）转换为前端 DTO。 */
private fun IndexedGraphFreshness.toDto(): IndexedGraphFreshnessDto =
    IndexedGraphFreshnessDto(
        state = state,
        dirtyReason = dirtyReason,
        pendingFileCount = pendingFileCount,
        pendingFileSamples = pendingFileSamples,
        lastIndexedAtEpochMillis = lastIndexedAtEpochMillis,
        staleSinceEpochMillis = staleSinceEpochMillis,
    )

/** 把按来源分层的节点统计转换为前端 DTO。 */
private fun IndexedGraphLayerCounts.toDto(): IndexedGraphLayerCountsDto =
    IndexedGraphLayerCountsDto(
        projectSource = projectSource,
        externalLibrary = externalLibrary,
        jdk = jdk,
        resource = resource,
        aggregate = aggregate,
    )
