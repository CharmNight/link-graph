package com.charmnight.linkgraph.usage

enum class ClassUsageKind {
    TYPE_REFERENCE,
    FIELD_TYPE,
    METHOD_PARAMETER,
    METHOD_RETURN,
    CONSTRUCTOR_CALL,
    ANNOTATION,
    IMPORT,
    EXTENDS,
    IMPLEMENTS,
    OTHER,
}

enum class ClassUsageOwnerKind {
    CLASS,
    METHOD,
    FILE,
}

data class ClassUsageTarget(
    val nodeId: String,
    val qualifiedName: String,
    val displayName: String,
)

data class ClassUsageEntry(
    val id: String,
    val ownerId: String,
    val kind: ClassUsageKind,
    val filePath: String,
    val line: Int,
    val column: Int,
    val text: String,
    val virtualFileUrl: String? = null,
    val ownerQualifiedName: String? = null,
    val ownerMethodSignature: String? = null,
)

data class ClassUsageGroup(
    val id: String,
    val ownerNodeId: String?,
    val ownerKind: ClassUsageOwnerKind,
    val title: String,
    val qualifiedName: String? = null,
    val filePath: String? = null,
    val virtualFileUrl: String? = null,
    val usages: List<ClassUsageEntry> = emptyList(),
)

data class ClassUsageSummary(
    val targetNodeId: String,
    val targetQualifiedName: String,
    val groupCount: Int,
    val usageCount: Int,
    val visibleGroupCount: Int,
    val visibleUsageCount: Int,
    val truncated: Boolean = false,
    val maxUsageGroups: Int = ClassUsageSearchLimits.DEFAULT_USAGE_GROUPS,
    val maxUsageEntries: Int = ClassUsageSearchLimits.DEFAULT_USAGE_ENTRIES,
    val includeImports: Boolean = false,
    val canRequestMore: Boolean = false,
)

data class ClassUsageSearchResult(
    val target: ClassUsageTarget,
    val groups: List<ClassUsageGroup>,
    val summary: ClassUsageSummary,
)
