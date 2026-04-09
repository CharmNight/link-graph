package com.charmnight.linkgraph.mermaid

/**
 * 表示 Mermaid 导入或校验阶段发现的一条问题。
 */
data class MermaidIssue(
    /** 记录问题所属的分类。 */
    val category: Category,
    /** 记录问题代码，便于前后端识别。 */
    val code: String,
    /** 记录面向用户展示的问题说明。 */
    val message: String,
    /** 记录问题所在的 Mermaid 行号。 */
    val line: Int? = null,
    /** 记录关联的节点标识。 */
    val nodeId: String? = null,
    /** 记录关联的边标识。 */
    val edgeId: String? = null,
) {
    /**
     * 定义 Mermaid 问题的分类。
     */
    enum class Category {
        /** 表示语法解析错误。 */
        SYNTAX,
        /** 表示图结构不合法。 */
        STRUCTURE,
        /** 表示语义层面的关系或节点异常。 */
        SEMANTIC,
        /** 表示与代码绑定或映射过程中的问题。 */
        BINDING,
    }
}
