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

/** 把图谱视图展示模型转换为前端可消费的键值映射，包含目标、泳道、隐藏桶与控件等结构。 */
internal fun GraphViewPresentation.toMap(): Map<String, Any?> =
    linkedMapOf(
        "target" to target.toMap(),
        "lanes" to lanes.map { lane -> lane.toMap() },
        "hiddenBuckets" to hiddenBuckets.map { bucket -> bucket.toMap() },
        "controls" to controls.toMap(),
    )

/** 把当前展示目标（锚点节点信息）转换为前端使用的字段映射。 */
private fun GraphPresentationTarget.toMap(): Map<String, Any?> =
    linkedMapOf(
        "nodeId" to nodeId,
        "title" to title,
        "subtitle" to subtitle,
        "location" to location,
    )

/** 把单条展示泳道元数据（坐标轴、顺序、角色）转换为前端字段映射。 */
private fun GraphPresentationLane.toMap(): Map<String, Any?> =
    linkedMapOf(
        "id" to id,
        "label" to label,
        "axis" to axis.name,
        "order" to order,
        "role" to role,
    )

/** 把隐藏节点/边的聚合桶转换为前端可展示的统计映射。 */
private fun GraphHiddenBucket.toMap(): Map<String, Any?> =
    linkedMapOf(
        "id" to id,
        "label" to label,
        "count" to count,
        "nodeIds" to nodeIds,
        "edgeIds" to edgeIds,
    )

/** 把视图交互控件配置（作用域、是否可搜索/展开）转换为前端字段映射。 */
private fun GraphPresentationControls.toMap(): Map<String, Any?> =
    linkedMapOf(
        "primaryScope" to primaryScope,
        "availableScopes" to availableScopes,
        "searchable" to searchable,
        "expandable" to expandable,
    )

/** 把索引图谱摘要转换为前端负载映射，涵盖锚点、统计计数、层级分布与新鲜度等全部信息。 */
internal fun IndexedGraphSummary.toMap(): Map<String, Any?> =
    linkedMapOf(
        "view" to view,
        "anchorKind" to anchorKind,
        "anchorNodeId" to anchorNodeId,
        "anchorTitle" to anchorTitle,
        "anchorQualifiedName" to anchorQualifiedName,
        "scopeKind" to scopeKind,
        "scopeLabel" to scopeLabel,
        "relationKinds" to relationKinds,
        "depth" to depth,
        "projectNodeCount" to projectNodeCount,
        "projectClassCount" to projectClassCount,
        "externalNodeCount" to externalNodeCount,
        "jdkNodeCount" to jdkNodeCount,
        "scopedNodeCount" to scopedNodeCount,
        "visibleNodeCount" to visibleNodeCount,
        "hiddenNodeCount" to hiddenNodeCount,
        "hiddenEdgeCount" to hiddenEdgeCount,
        "candidateNodeCount" to candidateNodeCount,
        "candidateEdgeCount" to candidateEdgeCount,
        "truncated" to truncated,
        "completeness" to completeness,
        "cacheState" to cacheState,
        "includeExternalLibraries" to includeExternalLibraries,
        "includeJdk" to includeJdk,
        "projectSourceNodeCount" to projectSourceNodeCount,
        "externalLibraryNodeCount" to externalLibraryNodeCount,
        "resourceNodeCount" to resourceNodeCount,
        "aggregateNodeCount" to aggregateNodeCount,
        "projectLayerCounts" to projectLayerCounts.toMap(),
        "visibleLayerCounts" to visibleLayerCounts.toMap(),
        "scopedLayerCounts" to scopedLayerCounts.toMap(),
        "candidateLayerCounts" to candidateLayerCounts.toMap(),
        "hiddenLayerCounts" to hiddenLayerCounts.toMap(),
        "collapsedLayerCounts" to collapsedLayerCounts.toMap(),
        "freshness" to freshness.toMap(),
        "visibilityReasons" to visibilityReasons.map { reason -> reason.toMap() },
    )

/** 把某类节点的可见性原因（含统计）转换为前端字段映射。 */
private fun IndexedGraphVisibilityReason.toMap(): Map<String, Any?> =
    linkedMapOf(
        "code" to code,
        "label" to label,
        "nodeCount" to nodeCount,
        "edgeCount" to edgeCount,
    )

/** 把索引新鲜度状态（脏原因、待处理文件、时间戳）转换为前端字段映射。 */
private fun IndexedGraphFreshness.toMap(): Map<String, Any?> =
    linkedMapOf(
        "state" to state,
        "dirtyReason" to dirtyReason,
        "pendingFileCount" to pendingFileCount,
        "pendingFileSamples" to pendingFileSamples,
        "lastIndexedAtEpochMillis" to lastIndexedAtEpochMillis,
        "staleSinceEpochMillis" to staleSinceEpochMillis,
    )

/** 把按来源分层的节点统计转换为前端字段映射。 */
private fun IndexedGraphLayerCounts.toMap(): Map<String, Int> =
    linkedMapOf(
        "projectSource" to projectSource,
        "externalLibrary" to externalLibrary,
        "jdk" to jdk,
        "resource" to resource,
        "aggregate" to aggregate,
    )
