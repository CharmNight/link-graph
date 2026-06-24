package com.charmnight.linkgraph.usage

/**
 * 类使用位置的语义种类。
 *
 * 描述"某类型在另一处是如何被使用的"——出现在 import、继承列表、字段类型、
 * 方法参数、构造器调用、注解等位置都属于不同种类。
 */
enum class ClassUsageKind {
    /** 普通类型引用（未细分）。 */
    TYPE_REFERENCE,
    /** 字段类型。 */
    FIELD_TYPE,
    /** 方法参数类型。 */
    METHOD_PARAMETER,
    /** 方法返回值类型。 */
    METHOD_RETURN,
    /** 构造器调用。 */
    CONSTRUCTOR_CALL,
    /** 注解使用。 */
    ANNOTATION,
    /** import 语句。 */
    IMPORT,
    /** extends 列表。 */
    EXTENDS,
    /** implements 列表。 */
    IMPLEMENTS,
    /** 其他类型（兜底）。 */
    OTHER,
}

/**
 * 类使用的归属种类。
 *
 * 一次使用可能归属于某个类、某个方法或仅归属于文件（例如 import）。
 */
enum class ClassUsageOwnerKind {
    /** 归属于类。 */
    CLASS,
    /** 归属于方法。 */
    METHOD,
    /** 归属于文件（无明确归属）。 */
    FILE,
}

/**
 * 类使用搜索的目标信息。
 *
 * @property nodeId 目标节点 ID
 * @property qualifiedName 目标全限定名
 * @property displayName 面向用户展示的名称
 */
data class ClassUsageTarget(
    val nodeId: String,
    val qualifiedName: String,
    val displayName: String,
)

/**
 * 单条类使用记录。
 *
 * 描述"在某文件某行某列以某种方式使用了目标类"，
 * 与具体归属（owner）相关联。
 */
data class ClassUsageEntry(
    /** 条目唯一 ID。 */
    val id: String,
    /** 归属 ID（指向 owner）。 */
    val ownerId: String,
    /** 使用种类。 */
    val kind: ClassUsageKind,
    /** 文件路径。 */
    val filePath: String,
    /** 行号。 */
    val line: Int,
    /** 列号。 */
    val column: Int,
    /** 命中文本（便于在 UI 上展示）。 */
    val text: String,
    /** 虚拟文件 URL。 */
    val virtualFileUrl: String? = null,
    /** 归属的全限定名。 */
    val ownerQualifiedName: String? = null,
    /** 归属方法的签名。 */
    val ownerMethodSignature: String? = null,
)

/**
 * 类使用分组：把同一归属下的所有使用聚合为一组。
 *
 * @property id 分组 ID
 * @property ownerNodeId 归属节点 ID；为空表示无具体归属
 * @property ownerKind 归属种类
 * @property title 标题（通常是类简单名或文件名）
 * @property qualifiedName 归属全限定名
 * @property filePath 文件路径
 * @property virtualFileUrl 虚拟文件 URL
 * @property usages 组内使用条目列表
 */
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

/**
 * 类使用搜索的统计摘要。
 *
 * 把搜索结果汇总为多个计数指标，让 UI 不必重新计算。
 */
data class ClassUsageSummary(
    /** 目标节点 ID。 */
    val targetNodeId: String,
    /** 目标全限定名。 */
    val targetQualifiedName: String,
    /** 总分组数。 */
    val groupCount: Int,
    /** 总使用条目数。 */
    val usageCount: Int,
    /** 可见分组数（受 limit 限制）。 */
    val visibleGroupCount: Int,
    /** 可见条目数。 */
    val visibleUsageCount: Int,
    /** 是否被截断。 */
    val truncated: Boolean = false,
    /** 当前 maxUsageGroups 值。 */
    val maxUsageGroups: Int = ClassUsageSearchLimits.DEFAULT_USAGE_GROUPS,
    /** 当前 maxUsageEntries 值。 */
    val maxUsageEntries: Int = ClassUsageSearchLimits.DEFAULT_USAGE_ENTRIES,
    /** 是否包含 import 条目。 */
    val includeImports: Boolean = false,
    /** 是否还能请求更多结果。 */
    val canRequestMore: Boolean = false,
)

/**
 * 一次完整的类使用搜索结果。
 *
 * @property target 目标信息
 * @property groups 分组列表
 * @property summary 统计摘要
 */
data class ClassUsageSearchResult(
    val target: ClassUsageTarget,
    val groups: List<ClassUsageGroup>,
    val summary: ClassUsageSummary,
)
