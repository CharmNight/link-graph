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
}
