package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.usage.ClassUsageEntry
import com.charmnight.linkgraph.usage.ClassUsageGroup
import com.charmnight.linkgraph.usage.ClassUsageSearchResult
import com.charmnight.linkgraph.usage.ClassUsageSummary
import com.charmnight.linkgraph.usage.ClassUsageTarget

internal fun ClassUsageSearchResult.toMap(): Map<String, Any?> =
    linkedMapOf(
        "target" to target.toMap(),
        "summary" to summary.toMap(),
        "groups" to groups.map { group -> group.toMap() },
    )

private fun ClassUsageTarget.toMap(): Map<String, Any?> =
    linkedMapOf(
        "nodeId" to nodeId,
        "qualifiedName" to qualifiedName,
        "displayName" to displayName,
    )

private fun ClassUsageSummary.toMap(): Map<String, Any?> =
    linkedMapOf(
        "targetNodeId" to targetNodeId,
        "targetQualifiedName" to targetQualifiedName,
        "groupCount" to groupCount,
        "usageCount" to usageCount,
        "visibleGroupCount" to visibleGroupCount,
        "visibleUsageCount" to visibleUsageCount,
        "truncated" to truncated,
        "maxUsageGroups" to maxUsageGroups,
        "maxUsageEntries" to maxUsageEntries,
        "includeImports" to includeImports,
        "canRequestMore" to canRequestMore,
    )

private fun ClassUsageGroup.toMap(): Map<String, Any?> =
    linkedMapOf(
        "id" to id,
        "ownerNodeId" to ownerNodeId,
        "ownerKind" to ownerKind.name,
        "title" to title,
        "qualifiedName" to qualifiedName,
        "filePath" to filePath,
        "virtualFileUrl" to virtualFileUrl,
        "usages" to usages.map { usage -> usage.toMap() },
    )

private fun ClassUsageEntry.toMap(): Map<String, Any?> =
    linkedMapOf(
        "id" to id,
        "ownerId" to ownerId,
        "kind" to kind.name,
        "filePath" to filePath,
        "line" to line,
        "column" to column,
        "text" to text,
        "virtualFileUrl" to virtualFileUrl,
        "ownerQualifiedName" to ownerQualifiedName,
        "ownerMethodSignature" to ownerMethodSignature,
    )
