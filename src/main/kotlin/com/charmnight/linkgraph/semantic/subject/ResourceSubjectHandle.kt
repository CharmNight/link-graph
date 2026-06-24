package com.charmnight.linkgraph.semantic.subject

/**
 * 表示资源主题对应的句柄信息。
 *
 * 资源主题可以是 SQL、HTTP 端点、配置项等。句柄把资源与它的源码位置、种类、锚点等打包，
 * 让语义分析模块以统一方式处理不同种类的资源。
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
    /** 保存资源锚点信息；用于把资源关联到代码方法。 */
    val resourceAnchor: ResourceAnchor? = null,
    /** 保存资源附加属性。让具体种类可以扩展自定义字段而不破坏类型结构。 */
    val attributes: Map<String, String> = emptyMap(),
) : SubjectHandle
