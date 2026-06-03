package com.charmnight.linkgraph.semantic.outcome

/**
 * 定义语义分析结果的展示模式。
 */
enum class AnalysisDisplayMode {
    /** 以事实图形式展示分析结果。 */
    FACT_GRAPH,
    /** 以流程图形式展示分析结果。 */
    FLOWCHART,
    /** 以资源关系视图展示分析结果。 */
    RESOURCE_RELATION_VIEW,
    /** 以项目级架构图形式展示索引投影结果。 */
    ARCHITECTURE_GRAPH,
    /** 以类图形式展示项目级 JVM 事实索引投影结果。 */
    CLASS_DIAGRAM,
    /** 以变更评审影响面图形式展示 review graph 查询结果。 */
    REVIEW_GRAPH,
}
