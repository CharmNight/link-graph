package com.charmnight.linkgraph.semantic.subject

/**
 * 表示资源主题对应的句柄信息。
 */
data class ResourceSubjectHandle(
    /** 保存主题唯一标识。 */
    override val subjectId: String,
    /** 保存资源路径。 */
    override val sourcePath: String,
    /** 保存源码范围。 */
    override val sourceRange: SourceRange,
    /** 保存展示名称。 */
    override val displayName: String,
    /** 保存资源主题种类。 */
    val kind: ResourceSubjectKind,
    /** 保存资源锚点信息。 */
    val resourceAnchor: ResourceAnchor? = null,
    /** 保存资源附加属性。 */
    val attributes: Map<String, String> = emptyMap(),
) : SubjectHandle
