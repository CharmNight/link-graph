package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.model.GraphDocument

/**
 * 表示 Mermaid 文本解析后的结果。
 */
data class MermaidParseResult(
    /** 保存解析得到的图文档。 */
    val document: GraphDocument,
    /** 保存解析或校验阶段产生的问题列表。 */
    val issues: List<MermaidIssue> = emptyList(),
)
