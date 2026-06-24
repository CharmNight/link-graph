package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.model.GraphDocument

/**
 * 表示 Mermaid 文本解析后的结果。
 *
 * 解析成功时 [document] 持有转换得到的图文档；
 * 解析过程中遇到的非致命问题（例如未知节点类型被忽略）通过 [issues] 返回，便于调用方提示用户。
 */
data class MermaidParseResult(
    /** 保存解析得到的图文档。 */
    val document: GraphDocument,
    /** 保存解析或校验阶段产生的问题列表；空列表表示无任何告警。 */
    val issues: List<MermaidIssue> = emptyList(),
)
