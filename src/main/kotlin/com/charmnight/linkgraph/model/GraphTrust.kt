package com.charmnight.linkgraph.model

/** 图元素最初由哪类主体或过程产生。 */
enum class GraphProvenance {
    CODE_ANALYSIS,
    USER_DRAFT,
    AI_DRAFT,
    DESIGN_IMPORT,
    DERIVED,
}

/** 当前结论的可验证程度，不与来源或绑定状态混用。 */
enum class GraphConfidence {
    VERIFIED,
    INFERRED,
    SUGGESTED,
    DECLARED,
}

/** 图元素与真实代码或设计目标之间的绑定状态。 */
enum class GraphBinding {
    CODE_BOUND,
    DESIGN_ONLY,
    GENERATABLE,
    PARTIAL,
    CONFLICTED,
}

/** 新建元素未显式指定时，按来源给出保守的置信度基线。 */
internal fun GraphProvenance.defaultConfidence(): GraphConfidence = when (this) {
    GraphProvenance.CODE_ANALYSIS -> GraphConfidence.VERIFIED
    GraphProvenance.USER_DRAFT,
    GraphProvenance.DESIGN_IMPORT,
    -> GraphConfidence.DECLARED
    GraphProvenance.AI_DRAFT -> GraphConfidence.SUGGESTED
    GraphProvenance.DERIVED -> GraphConfidence.INFERRED
}

/** 新建元素未显式指定时，按来源给出保守的绑定状态基线。 */
internal fun GraphProvenance.defaultBinding(): GraphBinding = when (this) {
    GraphProvenance.CODE_ANALYSIS -> GraphBinding.CODE_BOUND
    GraphProvenance.USER_DRAFT,
    GraphProvenance.AI_DRAFT,
    GraphProvenance.DESIGN_IMPORT,
    -> GraphBinding.DESIGN_ONLY
    GraphProvenance.DERIVED -> GraphBinding.PARTIAL
}
