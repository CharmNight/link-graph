package com.charmnight.linkgraph.semantic.outcome

/**
 * 定义语义分析结果的展示模式。
 *
 * 同一份语义分析结果可以以不同形态呈现给用户（事实图、流程图、架构图等），
 * 本枚举集中维护所有支持的展示模式，让 UI 与后端用同一套字符串对齐。
 */
enum class AnalysisDisplayMode {
    /** 以事实图形式展示分析结果（节点/边以原始事实呈现，不做视角裁剪）。 */
    FACT_GRAPH,

    /** 以流程图形式展示分析结果（按方法调用与控制流组织节点）。 */
    FLOWCHART,

    /** 以资源关系视图展示分析结果（聚焦资源与代码的绑定关系）。 */
    RESOURCE_RELATION_VIEW,

    /** 以项目级架构图形式展示索引投影结果（按模块/服务/层次组织）。 */
    ARCHITECTURE_GRAPH,

    /** 以类图形式展示项目级 JVM 事实索引投影结果（UML 风格的类间关系）。 */
    CLASS_DIAGRAM,

    /** 以变更评审影响面图形式展示 review graph 查询结果（聚焦变更影响范围）。 */
    REVIEW_GRAPH,
}
