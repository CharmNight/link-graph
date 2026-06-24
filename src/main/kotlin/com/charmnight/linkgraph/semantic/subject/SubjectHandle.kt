package com.charmnight.linkgraph.semantic.subject

/**
 * 表示可被语义分析处理的主题句柄。
 *
 * 主题（Subject）是语义分析的入口单位，可以是代码（方法/类）或资源（SQL/配置等）。
 * 句柄（Handle）把主题的身份、位置和展示信息打包在一起，
 * 让分析模块可以统一处理不同种类的主题。
 */
sealed interface SubjectHandle {
    /** 保存主题的唯一标识。 */
    val subjectId: String
    /** 保存主题所在的源码路径。 */
    val sourcePath: String
    /** 保存主题对应的源码范围。 */
    val sourceRange: SourceRange
    /** 保存面向用户展示的名称。 */
    val displayName: String
}

/**
 * 定义代码主题的种类。
 *
 * 每种种类对应一个语义分析 Provider。
 * 区分 Java/Kotlin 与函数/构造器/属性访问器，是因为它们在 PSI 中的访问方式不同。
 */
enum class CodeSubjectKind {
    /** 表示 Java 方法。 */
    JAVA_METHOD,
    /** 表示 Kotlin 函数。 */
    KOTLIN_FUNCTION,
    /** 表示 Kotlin 属性访问器（getter/setter）。 */
    KOTLIN_PROPERTY_ACCESSOR,
    /** 表示 Kotlin 主构造函数。 */
    KOTLIN_PRIMARY_CONSTRUCTOR,
    /** 表示 Kotlin 次构造函数。 */
    KOTLIN_SECONDARY_CONSTRUCTOR,
}

/**
 * 定义资源主题的种类。
 *
 * 资源主题覆盖项目中各类"非代码"文件，让它们也能进入语义分析。
 * 每种资源有自己的 Provider 实现。
 */
enum class ResourceSubjectKind {
    /** 表示配置项（YAML/properties）。 */
    CONFIG_ITEM,
    /** 表示 MyBatis 语句（XML 中的 SQL）。 */
    MYBATIS_STATEMENT,
    /** 表示 XML 资源（非 MyBatis/Spring 的普通 XML）。 */
    XML_RESOURCE,
    /** 表示 Markdown 页面（文档）。 */
    MARKDOWN_PAGE,
    /** 表示 SQL 文件（独立 .sql 文件）。 */
    SQL_FILE,
}
