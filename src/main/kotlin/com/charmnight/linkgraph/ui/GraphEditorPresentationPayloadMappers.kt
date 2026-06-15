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

internal fun GraphViewPresentation.toMap(): Map<String, Any?> =
    linkedMapOf(
        "target" to target.toMap(),
        "lanes" to lanes.map { lane -> lane.toMap() },
        "hiddenBuckets" to hiddenBuckets.map { bucket -> bucket.toMap() },
        "controls" to controls.toMap(),
    )

private fun GraphPresentationTarget.toMap(): Map<String, Any?> =
    linkedMapOf(
        "nodeId" to nodeId,
        "title" to title,
        "subtitle" to subtitle,
        "location" to location,
    )

private fun GraphPresentationLane.toMap(): Map<String, Any?> =
    linkedMapOf(
        "id" to id,
        "label" to label,
        "axis" to axis.name,
        "order" to order,
        "role" to role,
    )

private fun GraphHiddenBucket.toMap(): Map<String, Any?> =
    linkedMapOf(
        "id" to id,
        "label" to label,
        "count" to count,
        "nodeIds" to nodeIds,
        "edgeIds" to edgeIds,
    )

private fun GraphPresentationControls.toMap(): Map<String, Any?> =
    linkedMapOf(
        "primaryScope" to primaryScope,
        "availableScopes" to availableScopes,
        "searchable" to searchable,
        "expandable" to expandable,
    )

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

private fun IndexedGraphVisibilityReason.toMap(): Map<String, Any?> =
    linkedMapOf(
        "code" to code,
        "label" to label,
        "nodeCount" to nodeCount,
        "edgeCount" to edgeCount,
    )

private fun IndexedGraphFreshness.toMap(): Map<String, Any?> =
    linkedMapOf(
        "state" to state,
        "dirtyReason" to dirtyReason,
        "pendingFileCount" to pendingFileCount,
        "pendingFileSamples" to pendingFileSamples,
        "lastIndexedAtEpochMillis" to lastIndexedAtEpochMillis,
        "staleSinceEpochMillis" to staleSinceEpochMillis,
    )

private fun IndexedGraphLayerCounts.toMap(): Map<String, Int> =
    linkedMapOf(
        "projectSource" to projectSource,
        "externalLibrary" to externalLibrary,
        "jdk" to jdk,
        "resource" to resource,
        "aggregate" to aggregate,
    )
