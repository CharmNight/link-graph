package com.charmnight.linkgraph.semantic.subject

/**
 * 表示可被语义分析处理的主题句柄。
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
 */
enum class CodeSubjectKind {
    /** 表示 Java 方法。 */
    JAVA_METHOD,
    /** 表示 Kotlin 函数。 */
    KOTLIN_FUNCTION,
    /** 表示 Kotlin 属性访问器。 */
    KOTLIN_PROPERTY_ACCESSOR,
    /** 表示 Kotlin 主构造函数。 */
    KOTLIN_PRIMARY_CONSTRUCTOR,
    /** 表示 Kotlin 次构造函数。 */
    KOTLIN_SECONDARY_CONSTRUCTOR,
}

/**
 * 定义资源主题的种类。
 */
enum class ResourceSubjectKind {
    /** 表示配置项。 */
    CONFIG_ITEM,
    /** 表示 MyBatis 语句。 */
    MYBATIS_STATEMENT,
    /** 表示 XML 资源。 */
    XML_RESOURCE,
    /** 表示 Markdown 页面。 */
    MARKDOWN_PAGE,
    /** 表示 SQL 文件。 */
    SQL_FILE,
}
