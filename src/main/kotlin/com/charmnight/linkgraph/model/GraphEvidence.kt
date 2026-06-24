package com.charmnight.linkgraph.model

/**
 * 表示支撑图元素结论的一条证据信息。
 *
 * 图中的节点/边往往不是凭空产生的，而是基于静态分析、运行时反馈或用户输入推导出来。
 * 把推导依据以证据形式记录下来，便于事后审查、解释与回溯。
 */
data class GraphEvidence(
    /** 记录证据来源，例如解析器、文件或推导来源。 */
    val source: String,
    /** 记录可选的证据详情文本。 */
    val detail: String? = null,
)
