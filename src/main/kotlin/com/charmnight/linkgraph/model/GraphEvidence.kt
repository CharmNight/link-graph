package com.charmnight.linkgraph.model

/**
 * 表示支撑图元素结论的一条证据信息。
 */
data class GraphEvidence(
    /** 记录证据来源，例如解析器、文件或推导来源。 */
    val source: String,
    /** 记录可选的证据详情文本。 */
    val detail: String? = null,
)
