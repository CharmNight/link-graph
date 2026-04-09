package com.charmnight.linkgraph.semantic.model

import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 表示一次语义分析的完整结果。
 */
data class SemanticAnalysisResult(
    /** 保存本次分析的主题对象。 */
    val subject: SubjectHandle,
    /** 保存分析生成的锚点列表。 */
    val anchors: List<SemanticAnchor>,
    /** 保存识别出的语义单元列表。 */
    val semanticUnits: List<SemanticUnit>,
    /** 保存语义单元之间的关系。 */
    val relations: List<SemanticRelation>,
    /** 保存分析阶段产生的诊断信息。 */
    val diagnostics: List<SemanticDiagnostic>,
    /** 保存分析边界信息。 */
    val boundaries: List<SemanticBoundary>,
    /** 保存语义单元与源码的映射关系。 */
    val sourceMappings: List<SourceMapping>,
)
