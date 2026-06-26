package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.usage.ClassUsageEntry
import com.charmnight.linkgraph.usage.ClassUsageGroup
import com.charmnight.linkgraph.usage.ClassUsageKind
import com.charmnight.linkgraph.usage.ClassUsageOwnerKind
import com.charmnight.linkgraph.usage.ClassUsageSearchResult
import com.charmnight.linkgraph.usage.ClassUsageSummary
import com.charmnight.linkgraph.usage.ClassUsageTarget

/**
 * 类使用搜索结果的前端传输对象（DTO）。
 *
 * P2-6 替代之前的 `Map<String, Any?>`，给出类型稳定的契约：
 * - 字段名拼写错误会在编译期暴露
 * - 字段缺省或新增会被 IDE / 重构识别
 * - Gson 序列化时按声明顺序输出，与原 `linkedMapOf` 顺序一致
 *
 * 字段与前端 TS 类型 `ClassUsageSearchResult` 一一对应，禁止无关字段，避免「字段加在 Kotlin 忘了前端」的回归。
 */
internal data class ClassUsageTargetDto(
    val nodeId: String,
    val qualifiedName: String,
    val displayName: String,
)

internal data class ClassUsageSummaryDto(
    val targetNodeId: String,
    val targetQualifiedName: String,
    val groupCount: Int,
    val usageCount: Int,
    val visibleGroupCount: Int,
    val visibleUsageCount: Int,
    val truncated: Boolean,
    val maxUsageGroups: Int,
    val maxUsageEntries: Int,
    val includeImports: Boolean,
    val canRequestMore: Boolean,
)

internal data class ClassUsageEntryDto(
    val id: String,
    val ownerId: String,
    val kind: String,
    val filePath: String,
    val line: Int,
    val column: Int,
    val text: String,
    val virtualFileUrl: String?,
    val ownerQualifiedName: String?,
    val ownerMethodSignature: String?,
)

internal data class ClassUsageGroupDto(
    val id: String,
    val ownerNodeId: String?,
    val ownerKind: String,
    val title: String,
    val qualifiedName: String?,
    val filePath: String?,
    val virtualFileUrl: String?,
    val usages: List<ClassUsageEntryDto>,
)

internal data class ClassUsageSearchResultDto(
    val target: ClassUsageTargetDto,
    val summary: ClassUsageSummaryDto,
    val groups: List<ClassUsageGroupDto>,
)

/** 把 [ClassUsageSearchResult] 转为前端传输对象。 */
internal fun ClassUsageSearchResult.toDto(): ClassUsageSearchResultDto = ClassUsageSearchResultDto(
    target = target.toDto(),
    summary = summary.toDto(),
    groups = groups.map { group -> group.toDto() },
)

/** 把目标对象转为 DTO。 */
private fun ClassUsageTarget.toDto(): ClassUsageTargetDto = ClassUsageTargetDto(
    nodeId = nodeId,
    qualifiedName = qualifiedName,
    displayName = displayName,
)

/** 把统计摘要转为 DTO。 */
private fun ClassUsageSummary.toDto(): ClassUsageSummaryDto = ClassUsageSummaryDto(
    targetNodeId = targetNodeId,
    targetQualifiedName = targetQualifiedName,
    groupCount = groupCount,
    usageCount = usageCount,
    visibleGroupCount = visibleGroupCount,
    visibleUsageCount = visibleUsageCount,
    truncated = truncated,
    maxUsageGroups = maxUsageGroups,
    maxUsageEntries = maxUsageEntries,
    includeImports = includeImports,
    canRequestMore = canRequestMore,
)

/** 把单条分组转为 DTO。 */
private fun ClassUsageGroup.toDto(): ClassUsageGroupDto = ClassUsageGroupDto(
    id = id,
    ownerNodeId = ownerNodeId,
    ownerKind = ownerKind.dtoName(),
    title = title,
    qualifiedName = qualifiedName,
    filePath = filePath,
    virtualFileUrl = virtualFileUrl,
    usages = usages.map { usage -> usage.toDto() },
)

/** 把单条使用条目转为 DTO。 */
private fun ClassUsageEntry.toDto(): ClassUsageEntryDto = ClassUsageEntryDto(
    id = id,
    ownerId = ownerId,
    kind = kind.dtoName(),
    filePath = filePath,
    line = line,
    column = column,
    text = text,
    virtualFileUrl = virtualFileUrl,
    ownerQualifiedName = ownerQualifiedName,
    ownerMethodSignature = ownerMethodSignature,
)

/** 枚举名沿用 name 属性，封装一层便于未来加 schema 校验。 */
private fun ClassUsageKind.dtoName(): String = name

private fun ClassUsageOwnerKind.dtoName(): String = name
