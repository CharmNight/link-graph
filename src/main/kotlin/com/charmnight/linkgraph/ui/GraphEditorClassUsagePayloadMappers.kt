package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.usage.ClassUsageEntry
import com.charmnight.linkgraph.usage.ClassUsageGroup
import com.charmnight.linkgraph.usage.ClassUsageSearchResult
import com.charmnight.linkgraph.usage.ClassUsageSummary
import com.charmnight.linkgraph.usage.ClassUsageTarget

/**
 * 把类使用搜索结果转换为前端可消费的嵌套 map。
 * 字段名与前端 TS 类型一一对应；使用 linkedMapOf 保证序列化后字段顺序稳定。
 */
internal fun ClassUsageSearchResult.toMap(): Map<String, Any?> =
    linkedMapOf(
        "target" to target.toMap(),
        "summary" to summary.toMap(),
        "groups" to groups.map { group -> group.toMap() },
    )

/** 把目标对象转为 map。 */
private fun ClassUsageTarget.toMap(): Map<String, Any?> =
    linkedMapOf(
        "nodeId" to nodeId,
        "qualifiedName" to qualifiedName,
        "displayName" to displayName,
    )

/** 把统计摘要转为 map。包含可见/全量计数、是否截断、是否能加载更多等。 */
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

/** 把单条分组转为 map。 */
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

/** 把单条使用条目转为 map。 */
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
