package com.charmnight.linkgraph.semantic.model

import com.charmnight.linkgraph.semantic.subject.SubjectHandle

/**
 * 表示一次语义分析的完整结果。
 *
 * 把语义分析的产出（锚点、单元、关系、诊断、边界、源码映射）打包为不可变结果，
 * 让下游投影器、UI 等可以基于同一份快照工作。
 */
data class SemanticAnalysisResult(
    /** 保存本次分析的主题对象。决定了分析范围与锚点的归属。 */
    val subject: SubjectHandle,
    /** 保存分析生成的锚点列表。锚点用于固定用户注意力的语义单元。 */
    val anchors: List<SemanticAnchor>,
    /** 保存识别出的语义单元列表。每个单元是图中一个可命名的概念点。 */
    val semanticUnits: List<SemanticUnit>,
    /** 保存语义单元之间的关系。这些关系在图中通常表现为边。 */
    val relations: List<SemanticRelation>,
    /** 保存分析阶段产生的诊断信息。例如警告、未解析引用等。 */
    val diagnostics: List<SemanticDiagnostic>,
    /** 保存分析边界信息。记录哪些方向因为预算或权限被截断。 */
    val boundaries: List<SemanticBoundary>,
    /** 保存语义单元与源码的映射关系。让单元可以跳转到代码位置。 */
    val sourceMappings: List<SourceMapping>,
)
